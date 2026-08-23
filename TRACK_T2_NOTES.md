# TRACK_T2_NOTES — Relay core (transaction layer) + SBB logic

Trạng thái: `mvn -pl dra-core test` XANH 50/50 (20 test mới của T2).
`mvn -pl dra-app -am test`: 30/30 test T2 XANH; module còn đỏ do
`MigrationSqlSanityTest` (T4, xem §4.1 — tồn tại trước khi T2 đụng vào,
T2 bị cấm sửa `app/persist/**`).

## 1. dra-core/tx (impl mới, không sửa contract)

| File | Nội dung |
|------|----------|
| `tx/DefaultTxTable.java` | `ConcurrentHashMap<Long,TxState>` key=hbhOut; `LongAdder active`; `put` = putIfAbsent (hbhOut trùng đang sống → no-op, không ghi đè); `remove` chỉ decrement khi remove thành công; `forEachExpired` = snapshot-atomiic: duyệt entrySet, `table.remove(key, tx)` theo điều kiện (mỗi tx expired được deliver đúng 1 lần dù nhiều thread sweep đồng thời), gom batch rồi mới gọi consumer (tránh re-entry vào table trong callback). |
| `tx/HbhAllocator.java` | Wheel CAS trên `AtomicInteger`, chu trình [1..Integer.MAX_VALUE] (>0, 31-bit), không bao giờ emit 0/âm kể cả khi wrap qua Integer.MIN_VALUE; probe `occupied` predicate tối đa `maxProbes`(default 1024) rồi throw `IllegalStateException` (fail-loud, không block). RelayCore truyền predicate `v -> txTable.byHbhOut(v)!=null || pending.containsKey(v)`. |
| `tx/RelaySupport.java` | Immutable: `retryable(cmd)` dùng `RetryableCommands.DEFAULT_RETRYABLE` (ULR/AIR/PUR/NOR; CCR KHÔNG retryable); `deadlineFrom(now)=now+twMillis`; `canRetry(cmd, retryCount)` = retryable && retryCount<maxRetries. Reject twMillis<1, maxRetries<0 lúc construct. |

Tests (`dra-core/src/test/.../tx/`): DefaultTxTableTest ×8 (round-trip, dup-hbh
no-clobber, expiry sweep chính xác, 4-thread sweep mỗi-tx-đúng-một-lần, stress
8×2000 put/get/remove activeCount về 0, mixed-churn+sweeper leak-guard),
HbhAllocatorTest ×8 (31-bit dương, 8×25k next không trùng, skip occupied,
saturation throw, wrap MAX_INT, CAS race 4×50k), RelaySupportTest ×4.

## 2. dra-app `et.elisa.dra.app.sbbs(.relay)` — T2 sở hữu toàn bộ app/sbbs/**

### RelayCore (`sbbs/relay/RelayCore.java`) — plain Java, toàn bộ logic relay

Constructor (public, theo mission): `(RuleEngine, TxTable, HbhAllocator,
DraRaPort, BindingStore, ServerInitiatedResolver, OverloadGate, Screener,
TopologyHider, CandidateSource, selfOriginHost, twMillis, maxRetries)` +
package-private ctor thêm `(RelaySupport, LongSupplier clock)` để unit test
đồng hồ deterministic.

Luồng `onRequest(ingressPeerId, req)`:
1. Guard chain (thứ tự mission): `screener.ingressCheck` → lỗi trả
   `asAnswer(code)` về ingress ngay; loop-guard = Route-Record(282) chứa
   `selfOriginHost` → **3005 LOOP_DETECTED** (RFC 6733; UNABLE_TO_DELIVER=3002
   dùng riêng cho fail-closed/timeout — constant đúng từ `common.DraResultCodes`);
   `overload.admit(drmp)` false → **3004 TOO_BUSY**.
2. `engine.resolve(engine.contextFor(...))`:
   - **Forward**: sticky hit (`bindings.get("<KEY>:<value>", format T3 khoá`)
     override peer; không hit → `preferredPeerId()` của decision (LB là việc
     engine); cả hai blank → answer 3002 fail-closed. ThMode≠OFF →
     `th.hideOutbound(msg, stickyValue)`; sau đó apply `decision.ops()` qua
     `AvpOps.apply` (AppendRouteRecord/Set/Drop trên bản copy DiaMsg bất biến).
     TxState: hbhIn=req.hbh, hbhOut=allocator.next, e2eIn=e2eOut=req.e2e,
     deadline=now+tw; `pending` map (keyed hbhOut) giữ originalRequest +
     outbound body + stickyKeyFull + groupId + ttl + origin host/realm + thFlag;
     `txTable.put` → `sendToPeer(peer, body.withHopByHop(hbhOut))`.
     **Send exception ⇒ đi ngay timeout path** (failover/give-up, không retry-send cùng peer).
   - **Redirect**: answer **3006 REDIRECT_INDICATION** + AVP 292 Redirect-Host +
     420 Redirect-Max-Cache-Time từ decision → sendAnswerOnLink ingress.
   - **Reject**: answer `rej.resultCode()`.
3. Counters: TX_TOTAL, ANSWER_2XX/3XX/4XX/5XX, THROTTLED_TOTAL (MetricsNames
   frozen) + sbbs-local (SbbMetrics): DROP_UNKNOWN_TX, LOOP_DETECTED, REDIRECT,
   REJECT, BINDING_CAPTURED, SERVER_INITIATED_FAIL_CLOSED, SCREEN_REJECTED,
   SEND_FAILED, UNDELIVERED.

Luồng `onAnswer(ans, egressPeerId)`: `byHbhOut(ans.hbh)` null → DROP_UNKNOWN_TX++
(không trả gì — late/dup answer). Match → `overload.onAnswer`; nếu
cmd ∈ {ULR,AIR,PUR,NOR} && rc==2001 && pending có stickyKeyFull → capture
`BindingEntry(key, groupId, egressPeerId, originHost(req), originRealm(req),
ingressPeerId(tx), now, now+ttl)` → `bindings.put`. Rewrite
`ans.withHopByHop(tx.hbhIn)`; pending.thEnabled → `th.restoreInbound` trước khi
gửi; release (pending+table) TRƯỚC `sendAnswerOnLink(tx.ingressPeerId, …)`
(tránh double-process nếu send throw).

Timeout/sweep: `sweep(now)` = `txTable.forEachExpired(now, this::onExpired)`.
`onExpired`: pending null → release; ngược lại nếu
`support.canRetry(cmd, retryCount)` và `candidates.candidatesOf(groupId,
Set.copyOf(triedPeers))` còn peer chưa tried ≠ egress hiện tại →
**failover**: triedPeers.add(egress cũ), retryCount++, hbhOut MỚI (allocator),
deadline mới, re-forward **cùng outbound body đã lưu** (withHopByHop(hbhMới)),
e2e giữ nguyên, counter FAILOVER_TOTAL++. Không còn ứng viên / non-retryable /
hết maxRetries → give-up: answer **3002 UNABLE_TO_DELIVER** từ
originalRequest.asAnswer về ingress + release. Send-fail trong failover đệ quy
qua onExpired — depth bị chặn bởi retryCount<maxRetries.

Server-initiated `serverInitiated(ingress, req)`: guard chain như onRequest →
`resolver.resolve(ctx)`: empty && Dest-Host blank → **3002 fail-closed**
(KHÔNG đoán); có `PeerRouteTarget` → `restoreInbound` nếu group bật TH, rewrite
Dest-Host = `destHostRewrite()` (field + AVP 293), forward tới
`preferredPeerId()` (MME-link từ binding), **không capture binding chiều này**
(stickyKey=null trong pending); empty nhưng có Dest-Host → chạy rule engine
bình thường (capture vẫn tắt).

### Hỗ trợ

- `AvpOps` (static, immutable rebuild): `apply(msg, List<AvpOp>)`
  (AppendRouteRecord/Set-replace-or-append/Drop; Set TYPE_UINT32 parse long),
  `withDestinationHost`, `firstUtf8/firstUint32/stringsOf`,
  `drmpPriority` (AVP 301 Long hoặc String, default `RoutingContext.DRMP_DEFAULT`=10).
- `SbbMetrics`: registry `ConcurrentHashMap<String,LongAdder>` thuần, có
  `snapshot()` cho telemetry scrape; không micrometer.
- `CandidateSource`: functional interface inject vào constructor — nguồn
  candidate cho failover (integrator wire từ PeerRegistry/LB ở bootstrap).
- `PendingForward` (package-private record): pending-info keyed hbhOut.

### SBB shells (mỏng, compiles-pass với APT hiện tại)

`DraRelaySbb`, `DraBindingSbb`, `DraOverloadSbb` implements
`com.microjainslee.api.{Sbb, SleeEventHandler}`, không annotation APT nào →
APT jainslee không sinh artifact, build xanh (đã chứng minh trong run này).
Logic 100% nằm ở RelayCore; shell chỉ delegate qua seam:

- `RaEventBridge` (`Optional<IngressRequest{peerId,msg}> asRequest(SleeEvent)`,
  `Optional<IngressAnswer{msg,peerId}> asAnswer(SleeEvent)`).
- `DraBindingSbb.onSweepTick()` gọi `core.sweep(clock)` — scheduler Quarkus
  (@Scheduled 1s) wire ở bootstrap.
- `DraOverloadSbb.gate()` expose OverloadGate cho admin/telemetry.

## 3. Việc integrator phải wire lúc bootstrap

1. `RelayCore` ← `RuleEngineImpl` (T3), `OverloadGateImpl` (T5),
   `ScreenerImpl` (T5), `TopologyHiderImpl` (T6), `BindingStore` =
   `InMemoryBindingStore` + write-behind (T4), `ServerInitiatedResolverImpl` (T4),
   `HbhAllocator()` + `DefaultTxTable()` (T2, mới), `DraRaPort` = adapter RA (T1).
2. `CandidateSource` ← nhóm peer theo group từ PeerRegistry (T1) + LB:
   `groupId -> List<peerId ready>`, excludePeers đã tried.
3. Scheduler 1 tick/s → `DraBindingSbb.onSweepTick()` (hoặc Agrona
   DeadlineTimerWheel container gọi thẳng `relayCore.sweep(now)`).
4. RA bridge (T1): khi `DiameterRequestEvent` có sẵn `ingressPeerId` + raw
   `DiaMsg`, implement `RaEventBridge` thật (hiện jar ra-diameter trong sandbox
   chưa có 2 field này — xem §5). Wire `DiameterRequestEvent→asRequest`,
   `DiameterAnswerEvent→asAnswer`.
5. Telemetry: scrape định kỳ `relayCore.metrics().snapshot()` đẩy sang
   Prometheus exporter (map thẳng tên counter, tất cả đã là `dra_*`).

## 4. Gaps / vấn đề cross-track

### 4.1 `MigrationSqlSanityTest` (T4) đang ĐỎ — pre-existing, ngoài quyền T2
3 failure độc lập với code T2 (chỉ đọc resource SQL + regex):
- `dra_binding`/`route_config`: regex expect `"KEY TEXT PRIMARY KEY"` /
  `"ID BIGSERIAL PRIMARY KEY"` nhưng V1__dra_baseline.sql căn cột bằng nhiều
  space (`key             TEXT PRIMARY KEY`) → `.contains` fail.
- Statement count: test expect 6, SQL hiện có 5 statement (3 table + 2 index).
Cần T4 sửa một trong hai phía (test trim whitespace, hoặc SQL bỏ alignment).
T2 bị cấm đụng `app/persist/**`.

### 4.2 `WriteBehindPersistenceTest.submitDoesNotBlockAndFlushWritesBatch` (T4) flaky
Assert wall-clock `submitNanos < 200ms` cho 500 submit — thỉnh thoảng đỏ khi
machine load (bắt gặp 1/3 lần chạy song song). Nên đổi sang await-with-deadline
như các assert khác trong cùng test. Đã tự hồi phục khi chạy đơn lẻ.

### 4.3 Origin-Host rewrite chiều outbound
Mission không yêu cầu và constructor không có selfRealm → RelayCore hiện
GIỮ NGUYÊN Origin-Host/Realm khi forward nội bộ (TH bật thì `hideOutbound`
chịu trách nhiệm). Nếu NNI cần DRA tự đề danh nghĩa: T3 phát hành
`AvpOp.Set(264,…)` trong ops, hoặc bổ sung selfRealm vào constructor ở Gate
(chỉnh 1 dòng trong `standardForward`).

## 5. Open questions

1. **RA event shape**: `DiameterRequestEvent` hiện không mang `ingressPeerId`
   lẫn raw `DiaMsg` (chỉ Map<Integer,String> avps) — T1 cần extend event hoặc
   cung cấp decode hook để `RaEventBridge` thật lossless (Route-Record/DRMP
   phải đọc được). Cho tới khi đó, bridge là seam injection, test bằng fake.
2. **Duplicate-request replay** (plan T2 mục "duplicate-request replay"):
   hiện duplicate request cùng hbhIn từ ingress khi tx đang sống sẽ tạo tx mới
   hbhOut mới (không dedup). Cần quyết định: replay-detect theo
   (ingressPeerId, hbhIn, cmd) trong pending? Chưa làm — chờ chính sách house.
3. **TH chiều answer server-initiated**: hiện áp `restoreInbound` cho answer
   khi group bật TH (pending.thEnabled). Về lý thuyết IR.88 chiều ra biên cần
   hide (Origin-Host real→pseudo) — interface TopologyHider hiện chỉ có
   restore/hide(request) — cần T6 xác nhận map 2 chiều dùng chung hay cần API
   `hideAnswer`.
4. **Agrona DeadlineTimerWheel**: mission nhắc wheel container; T2 dùng sweep
   polling (`forEachExpired`) — đủ O(n) expired/tick và test được thuần;
   nối wheel là việc bootstrap wiring (gọi sweep theo tick), không đổi API.
