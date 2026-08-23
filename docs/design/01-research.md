# 01 — Nghiên cứu DRA & spec liên quan

> Trạng thái: RESEARCH — nền cho `02-architecture.md`. Ngày viết: 2026-08-23.

## 1. DRA là gì

**Diameter Routing Agent (DRA)** là một **Diameter Agent** đứng ở trung tâm lưới
signaling LTE/EPC + IMS, thay thế mô hình full-mesh point-to-point giữa các NE
(MME, HSS, PCRF, OCS, P-GW, CSCF…). Định nghĩa chuẩn:

| Nguồn | Định nghĩa |
|-------|-----------|
| **3GPP TS 29.213** | DRA là *proxy hoặc redirect agent* đảm bảo mọi Diameter session của **cùng một IP-CAN session** (Gx/S9/Gxx/Rx) được route tới **cùng một PCRF** khi mạng có nhiều PCRF đánh địa chỉ riêng biệt. Đây là chỗ duy nhất 3GPP định nghĩa hành vi DRA normative. |
| **3GPP TS 23.002** | DRA xuất hiện như một network entity trong kiến trúc PCC (chọn PCRF). |
| **GSMA IR.88** | Định nghĩa **DEA (Diameter Edge Agent)** — điểm tiếp xúc duy nhất của PLMN với mạng ngoài ở tầng Diameter: relay/proxy/translation + **topology hiding** + filtering. IR.88 ghi rõ DRA (TS 29.213) và DEA bổ trợ nhau; thực tế sản phẩm gộp cả hai. |
| Thông dụng ngành | DRA ≡ DSC (Diameter Signalling Controller): traffic management + **load balancing** + **session binding** (cụm từ của Nokia/Oracle/Diametriq). |

Vì sao cần DRA: 10 MME × 3 HSS × 2 PCRF × 2 OCS = full mesh hàng trăm link,
khó bảo trì, không scale, không có điểm kiểm soát lỗi/thoái trào. Với DRA: mỗi
NE chỉ nối vào DRA (hub-and-spoke); DRA chọn server, giữ sticky binding, che
topology, chặn storm.

## 2. Spec landscape

### 2.1 IETF (nền tảng giao thức)

| Spec | Nội dung dùng cho Nextgen DRA |
|------|------------------------------|
| **RFC 6733** (Diameter Base Protocol, thay RFC 3588) | Định nghĩa 4 loại agent: **Relay / Proxy / Redirect / Translation**. Bảng **Realm Routing Table** (`<Destination-Realm, Application-ID> → <Realm, Server(s)>`); xử lý `Destination-Host/Realm`, **Route-Record** append mỗi hop, **Proxy-Info/Proxy-State**, duplicate-request detection, failover (chuyển peer khi transport hỏng), timer `Tw`, advertised application-ID trong CER/CEA, relay App-ID `0xFFFFFFFF`. |
| **RFC 7075** | Realm-Based Redirect: trả `DIAMETER_REDIRECT_INDICATION` (3005) + `Redirect-Host`, agent cache theo `Redirect-Max-Cache-Time`. |
| **RFC 7068** | Requirements overload control Diameter (REQ 23/24 dẫn tới Load). |
| **RFC 7683 (DOIC)** | Overload control: Reporting/Reacting node, `OC-Supported-Features` (AVP 621), `OC-OLR` (AVP 623) — agent là **reacting node** điển hình: đọc OLR, giảm/throttle lưu lượng gửi về reporting node. |
| **RFC 7944 (DRMP)** | `DRMP` AVP 301: priority 0–15 cho routing/throttle decision; default PRIORITY_10. |
| **RFC 8583 (Load)** | `Load` AVP 681 (HOST-type end-to-end, PEER-type hop-by-hop), giá trị 0–65535 kiểu SRV-weight để LB node dùng chọn server ít tải. |

### 2.2 3GPP

| Spec | Liên quan |
|------|-----------|
| **TS 29.213** | Định nghĩa DRA + cơ chế **binding** cho PCC: key = (UE IP, PDN-ID/APN, MSISDN/IMSI…); DRA phải route Gx/Gxx/Rx/S9 của cùng IP-CAN session về đúng PCRF đã chọn. |
| **TS 23.003 §19.2** | Định dạng realm chuẩn bắt buộc khi routing quốc tế: `epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org`, `ims.…`, `hss.…` — router phải parse MCC/MNC từ realm và từ IMSI để so khớp. |
| **TS 29.272 (S6a/S6d/S13/SLg…)**, **TS 29.229/29.228 (Cx/Dx)**, **TS 29.328/32.299 (Sh/Rf)**, **TS 29.210/29.212 (Gx/Gxx)**, **TS 32.299 (Ro/Rf)** | Các application sẽ được relay: chỉ cần hiểu đủ AVP để làm điều kiện route (User-Name=IMSI, Visited-PLMN-Id, Framed-IP-Address, Subscription-Id…) chứ không kết thúc transaction. |
| **TS 33.210 (NDS/IP)** | Interconnect bắt buộc IPsec/TLS ở biên (Za/Zb) — ảnh hưởng yêu cầu security của DEA-mode. |

### 2.3 GSMA

| PRD | Nội dung |
|-----|----------|
| **IR.88** (LTE roaming) | DEA: khuyến nghị **advertise Relay App-ID 0xFFFFFFFF ra ngoài**, Proxy bên trong; **topology hiding** bắt buộc với IPX; routing quốc tế theo Destination-Realm chuẩn TS 23.003; thứ tự tìm next-hop: (1) static table (từ **IR.21**) → (2) NAPTR `AAA+D2S` qua DNS IR.67 → (3) SRV `_diameter._sctp`; allowlist + app-id filter tại biên; watchdog + restart/recovery bắt buộc; "DRA unreachable ⇒ peer not-ready, không silent 2xxx". |
| IR.21 | Dữ liệu roaming (realms, hosts đối tác) — nguồn seed bảng route tĩnh. |
| IR.67 / IR.34 | DNS GRX/IPX; backbone IPX. |

### 2.4 Chức năng cốt lõi của một DRA sản phẩm (tổng hợp vendor: Oracle DSR,
Nokia DSC, Diametriq DSS, Huawei)

1. **Peer management**: CER/CEA, DWR/DWA watchdog, DPR/DPA, reconnect/backoff,
   capability registry (app-ID mà peer advertise), per-peer health truth.
2. **Routing**: realm-based mặc định; host-based khi `Destination-Host` có mặt;
   rule engine theo AVP (IMSI/MCC-MNC prefix → MVNO/HSS pool; Visited-PLMN →
   roaming route; Framed-IP → PCRF binding).
3. **N-N**: nhiều client ↔ nhiều server, hai chiều (server-initiated CLR/IDR/RAR
   phải quay lại đúng client link ban đầu — nhờ **binding store**).
4. **Load balancing**: round-robin, weighted, least-outstanding, load-aware
   (RFC 8583), partitioning (chia tĩnh).
5. **Session/sticky binding**: IMSI→HSS, IP-CAN→PCRF, MSISDN→HSS (Zh/Zn);
   TTL; persist qua restart; cluster-consistent.
6. **Failover/retry**: peer down/timer-expired → thử peer khác trong group
   (chỉ command idempotent), trả `DIAMETER_UNABLE_TO_DELIVER` (3002) nếu bét.
7. **Overload/storm protection**: DOIC reacting, load-aware LB, DRMP-aware
   throttle (drop thấp-priority trước), admission control theo bucket, rate-limit
   per-peer.
8. **Topology hiding**: pseudo-host mapping (MME/HSS thật ↔ giả), rewrite
   Origin/Destination-Host, Session-Id, Route-Record — deterministic theo IMSI
   để HSS không tưởng ULR mới (tránh CLR storm) — tham khảo Oracle S6a/S6d TH.
9. **Screening/manipulation**: drop/insert/sửa AVP theo rule; allowlist
   app-ID/command-code per peering; chống spoof Origin-Realm.
10. **Redirect mode**: trả 3005 + Redirect-Host, cache redirect (RFC 6733 §6.13,
    RFC 7075).
11. **Observability**: counters per (peer, app, cmd, result-class), latency
    histogram, active transactions/bindings gauges, Prometheus export, audit log.

## 3. Khoảng trống trong nhà (gap analysis)

### 3.1 corsac-diameter (local fork, AGPL v3) — lớp transport/codec
Nguồn: `worktrees/diameter/corsac-diameter`.

Có sẵn:
- Peer state machine RFC-compliant: CER/CEA/DWR/DWA/DPR/DPA
  (`impl/.../MessageProcessingTask.java`, state IDLE→CER_SENT→OPEN→DPR_SENT).
- Capability check: `DiameterLinkImpl.canSendMessage()` xác minh app-ID mà peer
  đã advertise.
- Watchdog/reconnect timer; TCP + SCTP (multi-stream) trên Netty; duplicate-answer
  replay; pluggable session storage; dictionary ~49 app-ID bằng annotation;
  metrics map per command/app/link.

Thiếu (phải xây ở tầng trên):
- **Chỉ có static link table** (`NetworkManagerImpl.hostsMap/realmsMap`) +
  `chooseRandomLink()` round-robin — không realm-routing engine, không rule,
  không failover-on-error, không redirect/proxy logic, không binding.
- **Không TLS/DTLS** (chỉ TCP/SCTP).
- Codec reflection (`Method.invoke` per AVP) — ceiling throughput, không pooling.

### 3.2 ra-diameter (micro-jainslee `vendor-ras/ra-diameter`) — lớp RA
Nguồn: `/home/meodien/Desktop/ethiopia-working-dir/jain-slee/jain-slee`
(branch `micro-jainslee-2`).

Có sẵn:
- Event model sạch: `DiameterRequestEvent` / `DiameterAnswerEvent` (sealed),
  command `SendDiameterRequest` / `SendDiameterAnswer.ok/error()`
  (`vendor-ras/ra-diameter/src/main/java/com/microjainslee/ra/diameter/`).
- Activity handle == Session-Id → per-session ordering trên virtual thread SLEE.
- Base protocol (257/280/282) tự trả nội bộ qua `DiameterPeerTracker`.
- Peer-truth law: `isPeerConnected()` / `isPeerReady()` (CEA 2001 + channel up +
  watchdog) — đúng "house style" của Elisa/epc.
- HA: sticky Session-Id + TCP endpoint lease (Infinispan), checkpoint P1.

Thiếu:
- **Single peer duy nhất**: `DiameterRaConfig.peerHost/Port/destination*`,
  `LINK_ID = "diameter-ra"` — chưa có N peers, chưa có peer registry.
- Corsac path chỉ register S6A/CX_DX/GX/CREDIT_CONTROL và **drop command không
  decode được** (`CorsacEventBridge.toEvent()` trả null khi thiếu
  `@DiameterCommandDefinition`) — DRA phải relay mọi command.
- Không có answer-correlation map theo hop-by-hop (chưa đủ cho relay 1:N).
- Không TLS; không overload; không binding store.

### 3.3 elisa / epc (sip-freeswitch tree) — người tiêu dùng tương lai
Nguồn: `worktrees/sip-freeswitch/main`.

- Elisa Cx (`elisa/.../ims/cx/CxClient.java`): **hardcode 1 Destination-Host/
  Realm, 1 peer**; docs thừa nhận "**No Dx/SLF multi-HSS**" và
  "ra-diameter peer route ❌ gap (P0)".
- `epc/docs/nni/host-mno-interface-contract.md`: "**DRA/DRE/STP = separate
  project**"; biên NNI yêu cầu: peer allowlist + app-id filter, watchdog,
  overload AVPs, restart/recovery, redundant peers, "DRA unreachable ⇒ peer
  not-ready ngay, không silent 2xxx". Sign-off checklist: "S6a routed to MVNO
  HSS via DRA/DRE".
→ Nextgen DRA chính là project bị "đặt chỗ" này; nó sẽ đứng trước epc-hss
(S6a/Cx) và elisa (Cx), phục vụ luôn kịch bản MVNO nhiều IMSI-block → nhiều
HSS pool.

### 3.4 silent-authentication — pattern học được
Nguồn: `worktrees/silent-authentication/main`.

- Module skeleton Quarkus + micro-jainslee + wrapper/delegate/endpoint RA triad
  với `@InjectRa` — copy pattern.
- Config service: validate-before-apply + last-good rollback + push-to-live
  listener (`DiameterAdminSupport`) — copy cho hot-reload route table.
- Timeout ladder 3 lớp (app budget → RA completeOnTimeout → SBB abort) — copy.
- **Bài học xấu phải tránh**: correlation đáp ứng single-flight
  (`CorsacS6aVerifierBackend.handleAnswer()` completes tất cả pending futures)
  — sai dưới concurrency; DRA phải correlate theo **hop-by-hop ID**.
- Blocking bridge `CompletableFuture.get()` trên dispatch thread (~90% SLEE
  conformance, tự nhận "must fix") — DRA phải **event-driven thuần**.
- Không metrics plane — DRA phải có telemetry ngay từ đầu.

## 4. Kết luận thiết kế

Những gì có sẵn (dùng lại): transport + peer state machine (corsac), event/
command surface + peer-truth + HA lease (ra-diameter), container SLEE
(Disruptor + virtual threads), packaging dist/, admin UI style (HTMX).

Những gì phải xây mới — đây là phạm vi Nextgen DRA:

1. **Multi-peer transport layer** mở rộng ra-diameter: N peer config, registry
   capability, per-peer readiness, send-by-peerId, answer-on-link với hbh tùy ý.
2. **Any-command relay**: nới whitelist app-ID + fallback decode raw cho command
   lạ (không drop im lặng).
3. **Transaction layer**: hbh-in ↔ hbh-out map, timeout wheel, retry/failover.
4. **Routing engine + rule DSL** (chi tiết `03-routing-rules.md`).
5. **Binding store** sticky + server-initiated routing, PG/Infinispan-backed.
6. **Overload plane** DOIC/Load/DRMP + admission control.
7. **Topology hiding** pseudo-host maps (edge/DEA mode).
8. **Telemetry + admin** Prometheus/HTMX + hot reload.
