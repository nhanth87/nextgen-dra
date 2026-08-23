# 04 — Implementation Plan (P0–P6 CHẠY SONG SONG, GATE TỔNG CUỐI)

> Mô hình: các track **P0–P6 phát triển song song** từ đầu (mỗi track một nhánh
> làm việc riêng), chỉ **gate验收 MỘT LẦN duy nhất ở cuối** khi tất cả track đã
> hoàn thành — "Gate Tổng A-FINAL". Không gate trung gian chặn nhịp; mỗi track
> tự chịu trách nhiệm verify unit-level của mình trong quá trình chạy.
>
> Java 25 (mise zulu-25) mọi nơi. Commit authorship nhanth87.
> "Prove the artifact" áp dụng cho lần chứng minh cuối cùng.

## Các track song song

### T1 — Skeleton + PeerFabric (multi-peer RA extension)
- Init repo: parent pom + `dra-core` + `dra-app` (Quarkus, adapter-quarkus,
  `ra-diameter` dep), mise.toml zulu-25, package-dist.sh (copy từ elisa).
- Mở rộng `DiameterRaConfig`: `peers[]` (id/host/port/role/transport/apps/
  group/weight) thay cho single-peer; giữ backward-compat field cũ.
- `PeerRegistry`: N corsac link, per-peer state, capability map từ CEA,
  `peersReady()` health truth (tái dùng isPeerReady law).
- API mới `DiameterRaEndpoint`: `sendToPeer(peerId, req)`,
  `sendAnswerOnLink(peerId, hbh, e2e, ans)`; thêm `ingressPeerId` vào event.
- Any-command: register mọi ApplicationIDs + fallback raw-event trong
  CorsacEventBridge (không drop command lạ).
- Lab harness: 2 fake-HSS + 2 fake-MME (corsac LoadTest style / jdiameter
  oracle) nối vào DRA.

### T2 — Relay core (transaction layer)
- `TxTable`: hbhIn↔hbhOut map, Agrona deadline wheel, leak-guard gauge.
- DraRelaySbb: nhận request → forward → answer correlate → rewrite hbh/e2e →
  trả ingress link.
- Timeout Tw + `DIAMETER_UNABLE_TO_DELIVER`; duplicate-request replay.
- Wire benchmark harness (seeder client, Prometheus scrape).

### T3 — Rule engine + config plane
- Toàn bộ `dra-core/engine` theo spec 03 + test matrix §9 (unit thuần).
- PG schema (`route_config`, `audit_log`) + Flyway; JSON seed loader;
  validate-before-apply + last-good rollback + atomic swap RuleSet.
- Admin REST/HTMX v1: xem peers/rules/binding, apply config version mới,
  enable/disable peer; dashboard hiển thị peer-truth (không badge LISTEN).

### T4 — Binding store + server-initiated routing
- `BindingStore` (IMSI/FRAMED_IP/MSISDN keys), TTL sweep, write-behind PG
  batch async (không nằm trên đường request).
- Server-initiated path: IDR/CLR/RAR từ HSS resolve binding → đúng MME-link;
  fail-closed 3002 khi không có binding.
- Cluster mode: Infinispan DIST_SYNC binding replication + endpoint lease
  (kế thừa HA sẵn của ra-diameter); 2 node DRA.

### T5 — Resilience & overload
- Failover-on-error (retryable set), circuit-breaker per-peer.
- DOIC reacting (OC-OLR honor), RFC 8583 load-aware LB, DRMP-aware throttle,
  admission control token-bucket per-ingress-peer.
- Screening: allowlist app/cmd/IP per peering, anti-spoof Origin-Realm.

### T6 — Topology hiding + security biên
- ThMode PSEUDO_HOST_DETERMINISTIC + FULL_EDGE; mapping tables trong config.
- TLS decision record: SslHandler Netty trong fork corsac (TCP) và/hoặc IPsec
  TS 33.210 ở mạng; SCTP-DTLS hoãn, ghi rõ lý do.
- Security review: secret không log, admin auth (bcrypt pattern elisa).

### T7 — Capacity + docs + đóng gói
- Bench NODE_10K: **10k TPS/node sustained 60s**, p99 ≤ 5 ms agent-added,
  0 OOM; stretch đo 25k (codec reflection là biến số — ghi kết quả trung thực
  + đề xuất tối ưu fork corsac nếu chặn).
- Docs: sequence diagrams (khung ở 02), runbook vận hành, capacity doc kiểu
  elisa; README dist.
- Chuẩn bị prove-artifact: package dist/dra → rsync runtime → restart →
  dashboard READY + ULR live + jar mtime/PID classpath.

## Điểm tích hợp giữa các track (contract sớm, code muộn)

Các track code song song nhưng phải khoá contract trước tuần 1 để không lệch:

| Contract | Chủ | Tiêu thụ |
|----------|-----|----------|
| `RouteDecision` / `RoutingContext` types | T3 | T2, T4 |
| `TxState` / TxTable API | T2 | T4, T5 |
| `PeerHealth` / PeerRegistry API | T1 | T3, T5 |
| `BindingStore` interface | T4 | T2, T3 |
| Metrics registry names (`dra_*`) | T7 | tất cả |

Contract = record/interface Java trong `dra-core`, commit ngay tuần đầu;
thay đổi sau đó qua PR review của cả các track liên quan.

## GATE TỔNG DUY NHẤT — A-FINAL (chạy 1 lần khi mọi track xong)

Chạy toàn bộ chuỗi kiểm định trên build hợp nhất của tất cả track:

1. **Functional N-N**: kịch bản MVNO 2 IMSI-block → 2 HSS-pool đúng rule;
   ULR/AIR/PUR relay; server-initiated IDR đổ về đúng MME ban đầu;
   hot-reload config giữa lưu lượng không rớt transaction; config sai bị
   rollback.
2. **Resilience**: kill peer giữa tx retryable → answer thành công từ peer khác
   > 99.9%; kill node DRA → node kia nhận lease, không split-brain binding.
3. **Overload**: ép 3× tải → throttle DRMP-thấp trước, 0 crash.
4. **Topology hiding**: lab 2 PLMN qua DRA-edge; HSS đối tác chỉ thấy
   pseudo-host; pcap chứng minh không leak host nội bộ; checklist IR.88.
5. **Capacity**: ≥10k TPS/node sustained 60s, p99 ≤ 5 ms agent-added, 0 OOM,
   tx_active về 0 sạch sau load; baseline/stretch ghi vào capacity doc.
6. **Security**: allowlist/spoof tests xanh; secret scan; admin auth OK.
7. **Prove the artifact**: dist/dra package → deploy host lab → restart →
   dashboard READY + 1 ULR live đi qua + jar mtime/PID classpath khớp bản mới.

A-FINAL đỏ ở hạng nào ⇒ fix và chạy lại toàn bộ gate (không gate riêng lẻ).

## Phụ thuộc & rủi ro

| Rủi ro | Track | Giảm thiểu |
|--------|-------|------------|
| Codec reflection corsac chặn throughput | T7 | Đo baseline sớm ngay tuần 1; phương án: cache MethodHandle, pooling ByteBuf, passthrough replay buffer gốc khi không sửa AVP |
| Unknown-command decode | T1 | raw-passthrough fallback đã thiết kế; test command lạ |
| TLS thiếu ở corsac | T6 | decision record; sidecar/IPsec thay thế tạm |
| AGPL corsac fork | T6 | công khai phần sửa; không nhúng vô sản phẩm closed |
| Contract drift giữa track song song | tất cả | freeze contract tuần 1 (bảng trên); PR review chéo |
| Merge conflict cuối kỳ | tất cả | module tách bạch (core/app), rebase daily |

## Ước lượng effort (agent-days, tính theo track — chạy song song)

| Track | Effort | Ghi |
|-------|--------|-----|
| T1 | 4 d | RA extension nặng nhất về kỹ thuật RA |
| T2 | 3 d | TxTable + benchmark |
| T3 | 3–4 d | Engine + admin |
| T4 | 4 d | Binding + cluster chaos |
| T5 | 3–4 d | Overload + screening |
| T6 | 2–3 d | TH + security review |
| T7 | 2–3 d | Ladder + docs + prove |

Tổng ~21–25 agent-days phân bổ song song ⇒ thời gian lịch ~5–7 ngày nếu đủ
nhân sự/agent chạy đồng thời; buffer cho tích hợp + A-FINAL: 2–3 ngày.
