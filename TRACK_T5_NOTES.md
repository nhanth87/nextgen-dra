# TRACK_T5_NOTES — Overload control + screening

Owner: T5. Scope: `dra-core` packages `et.elisa.dra.core.overload` +
`et.elisa.dra.core.screen`. Verify: `mvn -q -pl dra-core test` — 75 tests xanh
(45 mới của T5 + 30 cũ của T4/bind).

## ⚠️ AVP codes: brief sai so với RFC — đã implement theo RFC

Brief nhiệm vụ hoán đổi/sai một số code con. Nguồn chuẩn = RFC text trong
`docs/specs/rfc/`, code đã theo RFC:

| AVP | Brief nói | RFC thật (dùng trong code) | Nguồn |
|-----|-----------|----------------------------|-------|
| OC-Sequence-Number | 624 Uint32 | **624 Unsigned64** | RFC 7683 §7.4 |
| OC-Validity-Duration | 627 | **625** Unsigned32 (default 30s khi thiếu) | RFC 7683 §7.5 |
| OC-Report-Type | 625 | **626** Enumerated (0=HOST_REPORT, 1=REALM_REPORT) | RFC 7683 §7.6 |
| OC-Reduction-Percentage | 626 | **627** Unsigned32 0..100 | RFC 7683 §7.7 |
| Load-Type | 699 | **651** Enumerated (0=HOST, 1=PEER) | RFC 8583 §7.2 |
| Load-Value | 700 | **652** Unsigned64, phạm vi dùng 0..65535 | RFC 8583 §7.3 |

Constants tập trung ở `overload/DoicAvps.java` (AvpCodes là common/** frozen,
không đụng). OC-* không mang vendor-id, nhưng parser khớp theo `code()` thôi —
vendor 10415 hay vendor 0 đều parse được (có test).

## Files

Main (`dra-core/src/main/java/et/elisa/dra/core/`):

| File | Nội dung |
|------|----------|
| `overload/OverloadGateImpl.java` | impl seam `OverloadGate`; parse answer → OLR/Load cache; extension `tryAdmit(peerId, drmpPriority, cmdCode)` |
| `overload/OlrCache.java` | DOIC reacting-node OCS per reporting-peer: seq guard (stale ≤ bỏ qua + rollover unsigned 1% RFC §5.2.1.3), validity expiry lazy-purge, clamp reduction 0..100 |
| `overload/LoadCache.java` | RFC 8583 HOST-type load per peerId; PEER-type chỉ đếm counter hop-by-hop (không dùng LB) |
| `overload/DrmpPolicy.java` | RFC 7944: DEFAULT=10, clamp out-of-range→10, `throttleOrder(p)=15-clamp(p)` (order nhỏ = bị cắt trước), CRITICAL_COMMANDS={ULR 316, AIR 318, PUR 321, NOR 323} |
| `overload/AdmissionController.java` | token bucket global + per-ingress-peer; lazy refill theo nanotime (injectable `LongSupplier`, không timer thread); LongAdder admitted/throttled |
| `overload/OverloadEligibility.java` | helper tĩnh cho LB eligibility (T3) |
| `screen/ScreeningConfig.java` | record immutable + defensive copy; `PeeringRules(appIds, cmdCodes, realmSuffixes, ipPrefixes, trustedNoProxy)`; rỗng = allow-all |
| `screen/ScreeningServiceImpl.java` | impl seam `Screener`; app→cmd→realm→proxy-state; `checkIp(peerId, addr)` public |
| `screen/IpV4Cidr.java` | IPv4 CIDR matcher tự viết (int mask math), strict parse (chặn leading-zero) |

Tests (`dra-core/src/test/java/et/elisa/dra/core/`): `overload/{OlrCacheTest,
LoadCacheTest, DrmpPolicyTest, AdmissionControllerTest, OverloadGateImplTest}`,
`screen/{IpV4CidrTest, ScreeningServiceImplTest}`.

## Cách T2 wire OverloadGateImpl (DraRelaySbb / DraOverloadSbb)

```java
AdmissionController admission = new AdmissionController(globalRate, globalBurst, peerRate, peerBurst);
OverloadGate gate = new OverloadGateImpl(new OlrCache(), new LoadCache(), admission);
```

- Request path (trước TxTable.register):
  - Cách đơn giản qua seam: `gate.admit(ingressPeerId, drmpPriority)` —
    KHÔNG cmd-aware (treat mọi cmd non-critical, DRMP vẫn tính).
  - Khuyên dùng (cast trực tiếp, cùng module dra-app sbbs relay của T2):
    `((OverloadGateImpl) gate).tryAdmit(ingressPeerId, drmpPriority, cmdCode)`
    — cmd ∈ {316,318,321,323} được bảo vệ đến ngưỡng cuối.
  - Throttle ⇒ trả ingress `DIAMETER_TOO_BUSY` (3004); DRMP ≥10 và non-critical
    rơi vào đầu danh sách bị cắt.
- Answer path: `gate.onAnswer(answerFromEgress, egressPeerId)` ngay khi nhận
  ULA/IDA/… từ egress link (cả error answers — DOIC piggyback cả trên lỗi).
  - OLR chỉ được honor khi answer có `OC-Supported-Features` (621) somewhere
    top-level (RFC 7683 §5.1.2 — có test chứng minh OLR-mồ-côi bị bỏ qua).
- `reductionPercentFor(egressPeerId)` đọc từ OlrCache (contract method).
- Ngưỡng scarcity: bucket còn < 1/16 capacity ⇒ kể cả critical bị từ chối
  ("ngưỡng cuối cùng"); PRIORITY_10 mặc định bị cắt khi bucket ≤ ~69%.
- `admit()`/`tryAdmit()` tự nhân hệ số abatement = `1 - maxActiveReduction/100`
  (max trên mọi reporting node đang active) lên tốc độ refill token — end-to-end
  test: 50% reduction ⇒ admit giảm đúng ~nửa.

## Cách T3 tiêu thụ (RuleEngine eligibility + LOAD_AWARE LB)

- Eligibility filter (03-routing-rules §5):
  ```java
  p -> p.isPeerReady() && advertises(p, appId)
       && OverloadEligibility.eligible(p, gate.reductionPercentFor(p.peerId()), cfg.olrThreshold())
       && p.id != ctx.ingressPeerId()
  ```
  `eligible()` = `peer.healthy() && reductionPercent < threshold`. Đề xuất
  threshold default 100 (mọi OLR active đều loại candidate host-report) hoặc
  51 (chỉ loại reduction nặng) — operator chọn.
- LOAD_AWARE strategy: weight ∝ `LoadCache.loadValue(peerId)` (HOST-type,
  RFC 8583) — chính là field `Integer loadValue` sẵn có trong `PeerHandle`
  (frozen contract, đã khớp). PEER-type load KHÔNG đưa vào LB (hop-by-hop,
  chỉ metrics counter `peerReportCount()`).
- Diversion thay vì throttle: nếu group còn peer eligible (OLR reduction thấp)
  ⇒ route sang peer kia = diversion abatement RFC 7683 §5.2.2.

## Config keys đề xuất

```properties
# dra.overload.*  (T2/T3 đọc để dựng AdmissionController + thresholds)
dra.overload.enabled=true
dra.overload.global-rate=20000          # token/giây toàn node
dra.overload.global-burst=4000
dra.overload.peer-rate=5000             # token/giây mỗi ingress peer
dra.overload.peer-burst=1000
dra.overload.default-drmp=10            # RFC 7944 default PRIORITY_10
dra.overload.critical-cmds=316,318,321,323
dra.overload.olr-eligibility-threshold=100   # % reduction loại candidate LB

# dra.screening.*  (per-peering NNI; key suffix = peerId từ peers.json)
dra.screening.mme-farm.app-ids=16777251
dra.screening.mme-farm.cmd-codes=316,318,321
dra.screening.mme-farm.realm-suffixes=.epc.mnc01.mcc452.3gppnetwork.org
dra.screening.mme-farm.ip-prefixes=10.20.0.0/16,10.21.0.0/24
dra.screening.mme-farm.trusted-no-proxy=true   # request có Proxy-State(33) lạ => chỉ đếm counter
```

Peering không khai báo key nào ⇒ allow-all hoàn toàn (pass-through, có test).
Config map nạp thẳng vào `ScreeningConfig.of(Map<String,PeeringRules>)`.

## Semantics đã khoá (tránh drift với T2/T3)

- `throttleOrder(p) = 15 - clamp(p)`: order NHỎ hơn = bị throttle TRƯỚC.
  Priority cao-số (15 = thấp quý) order 0 = cắt đầu tiên; priority 0 order 15 =
  cắt sau cùng (critical cmd ép eff-priority = 0).
- Scarcity cutoff: `deny(effPriority) iff effPriority > floor(minFrac*16)-1`,
  minFrac = min(global, per-peer) fraction sau refill. f≥1 ⇒ không ai bị cắt.
- OlrCache stale rule: seq_in ≤ seq_stored ⇒ bỏ qua (RFC: less-than-OR-equal);
  rollover chấp nhận khi stored nằm trong 1% đỉnh và incoming trong 1% đáy
  (unsigned64 compare).
- Validity 0 giây ⇒ report hết hạn NGAY (RFC: overload kết thúc); thiếu
  OC-Validity-Duration ⇒ default 30s (RFC §7.5); thiếu OC-Reduction-Percentage
  ⇒ 0 (không abatement); thiếu OC-Sequence-Number ⇒ cả OLR bỏ qua.
- Screening check order: app-ID (3007) → cmd-code (3002) → Origin-Realm suffix
  (3002) → Proxy-State counter (không bao giờ lỗi). Suffix match: equality hoặc
  endsWith có boundary '.' (chống "evilmcc452…" giả mạo), case-insensitive.
- `checkIp`: peer không có IP rules ⇒ true; address không parse được IPv4 khi
  có rules ⇒ false (fail-closed); IPv6 chưa hỗ trợ (transport SCTP/TCP nội bộ
  GRX IPv4 lab) — ghi rõ nếu cần mở rộng.

## Đã biết giới hạn / handoff cho integrator

- Chưa có OC-Supported-Features INSERT phía request (reacting-node DCA) — T2
  muốn full DOIC reacting thì append AVP 621 (feature-vector loss-algo bit 0)
  lúc rewrite; phần parse/honor đã đủ.
- Realm-report (OC-Report-Type=1) hiện gộp chung key theo egress-peer (brief
  định `OlrCache.update(peerId,…)`); nếu T3 muốn phân biệt HOST/REALM scope
  thì `reportTypeFor()` cần thêm — hiện lưu trong Entry, expose dễ.
- AdmissionController compensation loop bounded 32 vòng (global lấy được nhưng
  peer hết token ⇒ refund global, thử lại) — an toàn single/multi-thread,
  không blocking IO, không lock.
- Counters expose: OlrCache{updatesAcceptedCount, staleIgnoredCount,
  expiredPurgedCount}, LoadCache{hostUpdateCount, peerReportCount},
  AdmissionController{admittedCount, throttledCount},
  ScreeningServiceImpl{appRejectCount, cmdRejectCount, realmRejectCount,
  foreignProxyStateCount}. Nếu muốn map vào MetricsNames registry (T7): đề xuất
  `dra_throttled_total` ← throttledCount, thêm `dra_olr_active`,
  `dra_screen_reject_total{reason}` — cần integrator wire vì metrics/** frozen.
