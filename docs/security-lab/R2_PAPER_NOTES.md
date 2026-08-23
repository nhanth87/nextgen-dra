# R2 RESEARCH NOTES — iFinder (arXiv:2607.10315) → Diameter mapping cho Elisa Nextgen DRA

Nguồn: arXiv abs + HTML v1 (fetch 2026-08-23) · website `linziyuu.github.io/iFinder-Website/js/data.js` (toàn bộ 84 entries, đã parse + đếm) · local repo `/tmp/opencode/iFinder` (`pattern/*.json`, `dataset/{open5gs,free5gc}` 22 ground-truth JSON, `src/ifinder`).

Paper: "Understanding Implicit Trust Errors in Core Carrier Networks through Multi-Agent Flaw Discovery and Analysis" — Ziyu Lin et al., NTU, USENIX Security 2026.

---

## 1. PHÂN LOẠI 84 VULN / 81 CVE

### 1.1 Theo pattern (bảng chính — khớp Table 5 của paper, xác nhận bằng parse `data.js`)

| Pattern | Tên | Class | GTP-C | PFCP | **Total** |
|---------|-----|-------|-------|------|-----------|
| PA1 | Malformed Field | syntactic | 11 | 3 | **14** |
| PA2 | Absent Field | syntactic | 6 | 25 | **31** |
| PB1 | Invalid Value | semantic | 4 | 8 | **12** |
| PB2 | Invalid State | semantic | 7 | 1 | **8** |
| PB3 | Invalid Reference | semantic | 2 | 3 | **5** |
| PC1 | Resource Exhaustion | resource | 7 | 7 | **14** |
| **Total** | | | **37** | **47** | **84** |

Impact tổng: **82 DoS + 2 Session Hijacking** (cả 2 đều là PB3, cả 2 đều PFCP/UPF).

### 1.2 Theo implementation (Table 4 paper)

| Target | Ngôn ngữ | Discovered | Confirmed | CVE |
|--------|----------|-----------|-----------|-----|
| Open5GS 5G | C | 10 | 9 | 9 |
| free5GC | Go | 14 | 14 | 12 |
| OAI 5G | C++ | 11 | 11 | 11 |
| SD-Core | Go | 7 | 7 | 7 |
| eUPF | Go | 5 | 5 | 5 |
| Open5GS LTE | C | 30 | 30 | 30 |
| OAI LTE | C++ | 7 | 7 | 7 |
| **Total** | | **84** | **83** | **81** |

Aggregate theo vendor (data.js): open5gs=40, oai=18, free5gc=14, SD-Core=7, eUPF=5.
Vendor × protocol: open5gs {gtp-c:30, pfcp:10}, oai {gtp-c:7, pfcp:11}, free5gc {pfcp:14}, SD-Core {pfcp:7}, eUPF {pfcp:5}.
3 entry KHÔNG có CVE: free5gc Issue #818, #819 (session-pool flood/OOM), open5gs Issue #4204 (association pool) — cả 3 đều PC1.

### 1.3 Theo component CN (Table 6)

MME 2 · SGW 33 (32 GTP-C + 1 PFCP) · PGW 3 · SMF 9 · UPF 37. Skew: SGW chiếm 32/37 GTP-C; UPF chiếm 37/47 PFCP — vì SGW/UPF giữ long-lived session state + quản resource + mediate nhiều peers.

### 1.4 CVE mẫu theo mỗi pattern (từ data.js)

- **PA1 (14)**: CVE-2025-15418 (Open5GS SGW-C Bearer-QoS IE encoding sai → assert crash), CVE-2026-36896 (OAI APN IE stack overflow qua VLA), CVE-2026-36895 (OAI unbounded loop grouped-IE parser), CVE-2026-2521/2522 (oversized PAA trong CreateSessionResponse → heap overflow SGW-C/MME), CVE-2025-66783/66784 (Open5GS UPF BitRate / Dropped-DL-Traffic-Threshold IE malformed).
- **PA2 (31)**: CVE-2025-15156 (SD-Core SER thiếu NodeID → nil deref), CVE-2025-65565 (thiếu CPF-SEID), CVE-2026-1682 (free5GC AssociationReleaseRequest thiếu NodeID), CVE-2026-1973/1974/1976 (free5GC SMF answer thiếu Cause/NodeID), CVE-2025-14953 (Open5GS CreatePDR thiếu FAR-ID), CVE-2025-15417 (malformed/absent F-TEID CreateSessionRequest), CVE-2025-65560 (OAI PDR thiếu F-TEID).
- **PB1 (12)**: CVE-2025-65561/65562 (free5GC SEID khổng lồ khi Modify/Delete), CVE-2026-36882 (SEID 0xFFFFFFFFFFFFFFFF reachable assert), CVE-2025-15530 (EBI invalid), CVE-2026-1587/1736/1737 (PDN type invalid dẫn tới assert ở message sau), CVE-2025-66785 (URR-ID out-of-range ISTМ=1), CVE-2025-65559 (CH F-TEID address family mismatch).
- **PB2 (8)**: CVE-2025-15529 (delayed S5-C CreateSessionResponse mất S11 transaction), CVE-2026-1521/1522 (stale S11 transaction), CVE-2026-36888/36889 (Update/DeleteBearerResponse sau khi bearer đã xóa), CVE-2026-36893 (DeleteSessionResponse stale transaction), CVE-2026-36885 (OAI remove-all-uplink-PDRs OOB read).
- **PB3 (5)** — gồm cả 2 hijack: **CVE-2025-66776** (eUPF: FAR-ID cross-session, GlobalId=0 fallback → authorization bypass → redirect traffic), **CVE-2026-36884** (OAI: duplicate PDR + precedence thấp hơn → match-first → traffic interception); CVE-2025-66778 (UAF PDR tham chiếu FAR không tồn tại), CVE-2025-15528 (orphan CreateBearerResponse → sgwc_ue assert), CVE-2026-36894 (UpdateBearerResponse stale bearer context).
- **PC1 (14)**: CVE-2025-15532 (UE pool exhaustion CreateSessionRequest flood), CVE-2025-15531 (bearer pool), CVE-2026-1738 (PDR pool sgwc_tunnel_add), CVE-2026-36892 (PFCP transaction pool), CVE-2026-36887/36891 (event/remote-transaction pool), CVE-2026-36890 (timer pool), CVE-2025-14954 (QER pool), CVE-2026-36883 (OAI SEID map unbounded), CVE-2025-66779 (DL PDR > 32 OOB write), CVE-2025-66775 (eUPF invalid SDF filter exhaustion), Issues #818/#819/#4204.

Ground-truth 22 issues (local dataset, đếm trực tiếp JSON): PA1=11, PA2=1, PB1=6, PB2=1, PB3=1, PC1=2 — khớp Section 3 (syntactic 12 = PA1+PA2, semantic 8 = PB1+PB2+PB3, resource 2 = PC1).

Commercial: 2 DoS trên cùng một vendor core (1 CVE: **CVE-2026-8232**) + session-hijack trên 2 commercial cores (**CVE-2026-8233**, 1 vendor đã fix, vendor kia đang remediate) — cơ chế giống CVE-2026-36884 (duplicate PDR precedence).

---

## 2. ĐỊNH NGHĨA 6 PATTERN (nguồn: `pattern/*.json` — artifact chính thức; format triple <element, dangerous operation, missing validation>)

### PA1 — Malformed Field (syntactic)
- **Element**: trường length/size/count mang trong message hoặc structure nhúng (IE/TLV/filter).
- **Dangerous op**: memory/string operation điều khiển bởi length không tin cậy (copy buffer, index, đọc network buffer như C-string).
- **Missing validation**: đối chiếu length với actual payload size + buffer capacity TRƯỚC khi thao tác.
- **Trigger/hậu quả**: message có length field gian lận → buffer overflow / OOB read-write, hoặc fatal error path (assert/panic) thay vì graceful reject → **DoS (crash)**. Biến thể: loop parser không bound (unbounded loop grouped IE), string op giả định null-termination.

### PA2 — Absent Field (syntactic)
- **Element**: mandatory IE theo spec.
- **Dangerous op**: dereference/truy cập trực tiếp giá trị hoặc index vào nó.
- **Missing validation**: kiểm tra presence + số lần xuất hiện bắt buộc + IE header hợp lệ nhưng thực sự mang giá trị field bắt buộc hay không.
- **Hậu quả**: null pointer deref / OOB index trên list rỗng → **DoS**. Đây là pattern phổ biến nhất (31/84), đặc biệt PFCP (25/31) vì handler ngầm tin mandatory IE luôn có.

### PB1 — Invalid Value (semantic)
- **Element**: element well-form về syntax nhưng value sai ngữ nghĩa: enum/spare không hỗ trợ, ID ngoài range, tổ hợp flag mâu thuẫn.
- **Dangerous op**: dùng thẳng value làm index / lookup key / control-flow selector.
- **Missing validation**: range/enum/conflict check trước khi dùng.
- **Hậu quả**: assert failure, panic, NPE, OOB access → **DoS**.

### PB2 — Invalid State (semantic)
- **Element**: incoming message (version/type/stage) nhận tại trạng thái protocol hiện tại.
- **Dangerous op**: dispatch vào state-dependent handler vốn giả định precondition ("session phải tồn tại", "transaction phải initialized").
- **Missing validation**: kiểm tra message được phép ở trạng thái hiện tại trước khi dispatch.
- **Hậu quả**: crash hoặc hành vi bất định khi precondition bị vi phạm → **DoS**. Chiếm đa số ở GTP-C (7/8) vì state machine phức tạp, multi-message procedures.

### PB3 — Invalid Reference (semantic)
- **Element**: object reference suy ra từ message: object ID làm key tra bảng nội bộ, HOẶC stored pointer giữ bởi async callback.
- **Dangerous op**: deref/operate trên object được tham chiếu (table lookup, pointer use).
- **Missing validation**: object tồn tại + thuộc scope hiện tại + còn sống (alive) trước khi dùng.
- **Hậu quả**: use-after-free, NPE, hoặc thao tác trên entry SAI (cross-scope) → **DoS hoặc SESSION HIJACKING**. Hai hijack CVE đều dạng "operation on wrong entry": FAR-ID cross-session (GlobalId=0 fallback) và duplicate PDR thắng precedence — attacker chèn rule mới trỏ forward về mình, UPF match-first → traffic uplink của nạn nhân đi tới attacker.

### PC1 — Resource Exhaustion (resource)
- **Element**: bounded pool tài nguyên protocol-related giới hạn cứng (session, bearer, PDR, transaction, timer, event, association pool).
- **Dangerous op**: cấp phát khi nhận resource-creating request + code path khi đạt limit (fatal error khi count ≥ MAX, hoặc deref kết quả cấp phát thất bại).
- **Missing validation**: xử lý exhaustion như error case bình thường (reject kèm cause thích hợp) thay vì crash.
- **Hậu quả**: flood request tạo-resource → drain pool → crash (segfault khi deref failed alloc) → **persistent DoS** (process chết, cần restart).

---

## 3. CONFIRMATION ORACLE

Paper không dùng từ "confirmed" theo nghĩa chỉ-crash. Hai tầng oracle (artifact `src/ifinder`):

**Layer 1 — Crash regex (testbed.py)**: marker regex trên runtime logs: `SIGSEGV`, `segmentation fault`, `SIGABRT`, `panic:`, `runtime error:`, `fatal error:`, `assertion`, `ogs_assert`, `core dumped`, `Aborted`; cộng exit-code container từ fatal signal {132,134,135,136,139} (cố tình loại 143 SIGTERM sạch và 137 SIGKILL/OOM ambiguous). Có **teardown-crash attribution**: NF được restart (reset) trước MỖI attempt; crash chỉ lộ lúc teardown (vd ogs_assert trong cleanup do desync attempt trước) được quy về đúng attempt đã trồng PoC.

**Layer 2 — LogJudge (agents/oracle.py)**: oracle LLM single-turn, KHÔNG tool, chỉ đọc tail 200 dòng log (PoC stdout/stderr + NF container), nhận thêm candidate mô tả (vulnerable site, trigger message/IE, missing validation, data flow). Chỉ chạy khi regex đã loại trừ crash. Verdict JSON `{confirmed, signal_type ∈ audit|behavior|state|none, evidence, reasoning}`:
- `audit`: log thể hiện NF CHẤP NHẬN request lẽ ra phải reject;
- `state`: mutation trạng thái nội bộ có trong log (vd "PDR <id> created" xuất hiện 2 lần cho flaw duplicate-id);
- `behavior`: hành vi đổi (session reused cross-tenant, response mang IE giá trị lạ).
- Ràng buộc chống hallucination: PHẢI quote nguyên văn 1 dòng log làm evidence — code có safety net hạ `confirmed=True` về false nếu thiếu evidence; hướng dẫn prefer false-negative khi ambiguous ("PoC gửi bytes và NF xử lý êm ru" KHÔNG phải confirmation — đó là behavior mặc định của benign message).

**Định nghĩa confirmed của paper**: (i) với 84 finding mới — PoC trigger expected behavior trong testbed + developer xác nhận (83/84); (ii) FP residue: EA chỉ check runtime effect (luôn là crash) chứ không verify crash-site khớp candidate — paper tự soi thủ công: observed crash site ≠ expected site ⇒ tính FP. Hijack (non-crash) được confirm bằng quan sát hành vi forwarding (traffic nạn nhân đổ về attacker) + reproduced trên lab vendor thương mại.

---

## 4. EVALUATION METRICS (Tables 1–3)

Setup: ground truth = 22 issue đã biết; targets Open5GS v2.7.5/2.7.2/2.4.14 + free5GC v3.3.0/2.0.2; Claude Opus 4.5, Claude Agent SDK; max 5 vòng refine PoC/vuln; trung bình 5 lần chạy.

| Config | TP | FP | FN | New | Precision | Recall | F1 |
|---|---|---|---|---|---|---|---|
| Prompt-only (có patterns) | 8 | 56 | 14 | 14 | 28.205% | 36.364% | 31.769% |
| **iFinder (DA+VA+EA)** | 15 | 12 | 7 | 21 | **75.000%** | **68.182%** | **71.429%** |

Pipeline candidates → feasible → confirmed (Table 2 ablation):
- **DA alone**: 15 TP / 62 FP → P = 36.735%
- **DA+VA** (code-spec cross-check): FP giảm 62→19 → P = 65.455% (VA loại FP do check nằm ở message/state TRƯỚC trong cùng procedure)
- **DA+VA+EA**: PoC fail-to-trigger discard thêm FP 19→12 → P = 75%. 12 FP còn lại: PoC trigger được flaw THẬT nhưng khác flaw dự kiến (trước đó trên execution path) — phát hiện bằng so sánh thủ công crash-site.

EA PoC success (cho sẵn 22 GT): EA 19/22 vs prompt-only 8/22.

Cost per target implementation (Table 3):

| Stage | Time (s) | Tokens |
|---|---|---|
| Discovery | 280 (39.4% runtime) | 2.1M |
| Vetting | 230 (32.4%) | 3.3M |
| Exploitation | 200 (đổi theo số vòng, ≤5) | 4.4M |
| **Total** | **~710 s (~12 phút)** | **~9.8M** |

7 FN phân tích thủ công: 4 do pattern coverage gap (corner-case logic ngoài pattern), 3 do DA không dựng được caller chain dài xuyên module.

---

## 5. MAP SANG DIAMETER S6a/Gx TRÊN NEXTGEN DRA

Bối cảnh cấu phần thật (worktree Nextgen-DRA): `RelayCore` (app.sbbs.relay), `TxTable/DefaultTxTable` + `TxState(hbhOut)` + sweep `forEachExpired` + `HbhAllocator`, `BindingStore` (InMemory/Clustered) với keys `IMSI/MSISDN/FRAMED_IP_APN` (BindingKeys), `ServerInitiatedResolver`, `PeerRouteTarget`, LB (`Rr/WeightedRr/LeastOutstanding`), `OverloadGate` (+`OlrCache/LoadCache/AdmissionController/DoicAvps`), `Screener` (allowlist/CIDR), `TopologyHider` + `PseudoHostMapper`, `DiaMsg/DiaAvp` wire parse, `AvpOps`, policy fail-closed 3002 (UNABLE_TO_DELIVER) / 3004 (TOO_BUSY?). Peer truth law: LISTEN ≠ ready.

Lưu ý nền tảng: DRA là Java — memory-safety lớp PA1/PB3 kiểu OOB-write/UAF không gây segfault; hậu quả dịch sang: exception giết event-handling, corrupt state/logic, CPU spin, OOM kill (= persistent DoS vì restart mất in-memory state), hoặc tệ hơn: relay/chuyển hướng SAI mà vẫn trả thành công (hijack kiểu Diameter).

### PA1 Malformed Field → Diameter
1. **AVP Length gian lận**: Length < 8 (header) hoặc vượt số byte còn lại của message → parser `DiaMsg/DiaAvp` đọc tràn frame SCTP/TCP stream hoặc TLV-walk không tăng offset (Length 0–3) → **infinite loop / CPU spin trên event thread** = DoS mềm. Gate: mọi AVP length phải validate (≥8, ≤ remaining, multiple-of… nếu spec yêu cầu) ngay khi parse; vi phạm → drop + answer 5001/5015-style fail-closed (theo house policy trả 3002 kèm Error-Message), tuyệt đối không parse tiếp "chừng nào còn bytes".
2. **Grouped AVP lồng vô hạn** (≈ CVE-2026-36895 unbounded grouped-IE loop): Nested-Grouped depth không giới hạn → StackOverflowError giết thread SLEE event. Gate: depth cap tường minh trong recursive parse của `AvpOps`.
3. **Trường giá trị có format cứng nhưng độ dài rác**: TBCD IMSI/MSISDN trong Visited-PLMN/User-Name/Subscription-Id-data rác → decode ra digits sai/nhị phân lạ → bơm thẳng vào `BindingKeys` → key mồ côi/pollution trong `BindingStore`. Gate: validate TBCD nibble + độ dài chuẩn E.164/IMSI trước khi làm key; rác ⇒ không bind, trả lỗi.

### PA2 Absent Field → Diameter
1. **Request thiếu mandatory AVP**: ULR thiếu RAT-Type/ULR-Flags, CER thiếu Origin-Host/Origin-Realm/Inband-Security-Id — code path relay giả định getter trả non-null → NPE giữa `RelayCore`/rule engine. Gate: schema-check per-command (mandatory AVP presence) trước dispatch; thiếu ⇒ answer 5004 (MISSING_AVP) — vẫn fail-closed.
2. **Answer thiếu Result-Code** từ upstream HSS/PCRF: `RelayCore` hoàn tất `TxState` giả định có Result-Code/Experimental-Result → NPE hoặc TX treo đến sweep. Gate: answer không parse được Result-Code ⇒ tổng hợp 3002 về phía downstream, hủy tx sạch.
3. **CEA thiếu AVP bắt buộc handshake** (Result-Code, Origin-State-Id, Host-IP-Address): peer layer đánh READY dù thiếu → vi phạm peer-truth law. Gate: CEA validator strict; thiếu ⇒ không bao giờ chuyển peer sang ready.

### PB1 Invalid Value → Diameter
1. **Auth-Application-Id/Vendor-ID không hỗ trợ nhưng well-formed**: route engine chọn selector theo application-id không nằm trong supported set → rơi vào default path không kiểm soát. Gate: allowlist application-id ở `Screener`/rule engine; ngoài danh sách ⇒ 3007/3002.
2. **OC-OLR/DOIC value ngoài range** (Reduction-Percentage > 100, Report-Type spare): `OverloadGate`/`OlrCache` nhân thẳng vào token-bucket refill → tự bóp cầu chì hoặc mở van hoàn toàn (≈ CVE-2025-66785 URR-ID out-of-range). Gate: clamp + sanity-range trước khi apply; OLR từ peer không config ⇒ bỏ qua + counter.
3. **Session-Id/Origin-State-Id well-formed nhưng语义 sai** (Session-Id không theo namespace <host>;high;low): dùng làm correlation/binding key → collide/tách nhầm session. Gate: structural validation Session-Id trước khi key.

### PB2 Invalid State → Diameter
1. **Answer cho hbh không tồn tại / đã sweep** (≈ CVE-2025-15529 delayed response mất transaction): `DefaultTxTable.byHbhOut(hbh)` trả null → NPE hoặc tệ: coi như request mới. Gate: miss ⇒ log + drop answer, KHÔNG tạo tx mới từ answer, không silent-drop mà trả 3002 về phía... (lưu ý: answer-orphan thường chỉ drop + metric; request-path thì phải 3002).
2. **hbh reuse sau sweep** (hbh slot tái sử dụng khi tx cũ chưa chết hẳn): answer muộn của session A ghép vào tx của session B = **cross-session answer splice** — phiên bản Diameter của hijack. Gate: hbh space rộng + random hóa (`HbhAllocator`), sweep giữ tombstone window, verify (Origin-Host, Session-Id, End-to-End) ngoài hbh trước khi hoàn tất tx.
3. **Message sai thứ tự FSM**: STR giữa chừng DWx, CER sau DPR, answer trước request — dispatch vào handler giả định preconditions (peer associated, binding tồn tại). Gate: peer-FSM + command-validity check ở `PeerHealth`/relay gate trước khi vào `RelayCore`.

### PB3 Invalid Reference → Diameter
1. **Dest-Host lạ / không thuộc peer table**: route theo Dest-Host như key vào `PeerHandle` map — host không tồn tại/không ready ⇒ phải fail-closed 3002 (đúng peer-truth law), không retry vô hạn, không route "best effort" vào peer chưa ready.
2. **Binding mồ côi**: `ServerInitiatedResolver` tra `IMSI/MSISDN/FRAMED_IP_APN` ra `BindingEntry` trỏ `PeerRouteTarget` của peer ĐÃ chết (channel down giữa chừng) → forward vào peer dead. Gate: resolve xong phải re-check `PeerHealth` ready; dead ⇒ 3002 + đánh dấu entry để `BindingSweepJob` dọn.
3. **Pseudo-host orphan trong `TopologyHider`**: answer mang Dest-Host = pseudo-host đã bị eviction/map-miss trong `PseudoHostMapper` → ánh xạ ngược thất bại → leak host thật hoặc NPE. Gate: map-miss ⇒ thay bằng default pseudo + metric, không bao giờ lộ topology thật, không crash.
4. (Hijack analogue) **AVP rewrite surface của relay**: `TopologyHider`/`AvpOps` chèn/xóa AVP — nếu attacker (downstream node đã bị chiếm) khiến relay splice AVP định tuyến (MIP6-Agent-Info, Redirect-Host) vào answer của nạn nhân ⇒ redirect subscriber. Gate: rewrite whitelist theo command, không pass-through AVP định tuyến từ nguồn không tin cậy. Đây là bản Diameter của CVE-2025-66776/36884.

### PC1 Resource Exhaustion → Diameter
1. **TxTable flood** (≈ CVE-2026-36892 transaction pool): ULR/CCR storm đầy `DefaultTxTable` giữa 2 chu kỳ sweep → heap growth → OOM kill = persistent DoS (mất toàn bộ binding in-memory). Gate: cap kích thước + khi đầy trả 3004 (TOO_BUSY) ngay — graceful rejection đúng tinh thần PC1, không bao giờ để OOM killer quyết.
2. **BindingStore/queue flood** (≈ CVE-2025-15532 UE pool): IMSI-keyed entries + hàng đợi `WriteBehindPersistence`/PG backup phình vô hạn. Gate: bound write-behind queue, backpressure, admission theo `OverloadGate` TRƯỚC khi vào store.
3. **Overload-gate weaponization**: kẻ xấu gửi OC-OLR reduction=100 giả (từ peer trong allowlist nhưng bị chiếm) → DRA tự shed traffic legit = DoS "xin lỗi bạn". Gate: OLR chỉ tin từ peer đã authenticated + bound giá trị + hysteresis; đây là blind spot riêng của relay (endpoint CN ít khi có cơ chế này).
4. **Peer/connection storm** (≈ Issue #4204 association pool): CER flood từ địa chỉ chưa cho phép → cap concurrent capabilities + `Screener` CIDR allowlist trước handshake.

---

## 6. BLIND SPOTS — những gì 81 CVE KHÔNG cover khi chuyển sang Diameter

1. **Zero dữ liệu Diameter**: 84/84 vuln là PFCP/GTP-C trên SGW/UPF/SMF/MME/PGW. Không một CVE nào trên S6a/Gx/Gy/Rx hay trên DRA/proxy. Mọi con số pattern đều là extrapolation, không phải evidence.
2. **Memory-safety classes không translate**: OOB write, VLA stack overflow, UAF (PA1/PB3 phần lớn) là C/C++/Go-specific. Trên Java hậu quả đổi bản chất: crash → exception/state-corruption/CPU-spin/OOM. Oracle crash-regex của iFinder gần như vô dụng trên Java; cần oracle kiểu LogJudge mở rộng (audit/state/behavior) — mà paper chỉ mới phác thảo.
3. **Hijack evidence rất mỏng**: chỉ 2/84 (0.4% trong 81 CVE) và cả hai là PFCP rule-table logic. Diameter hijack sẽ đi qua AVP rewrite/redirect tại relay (TopoHider là rewrite surface) — pattern tương đồng nhưng KHÔNG có ground-truth nào chứng minh khả thi trên Diameter stack.
4. **Failure-mode khác hẳn cho PC1**: Java không segfault khi pool đầy; chết kiểu latency-collapse/thrash/OOM-kill/restart-mất-state. "Persistent DoS" của paper (process crash) trở thành "OOM + cold start + mất binding cache" — nặng về mặt vận hành nhưng cần chiến lược test khác (đo queue depth, GC, latency p99) chứ không đo crash.
5. **Spec corpus khác**: DA/VA dựa procedure extraction từ TS 29.274 (PFCP) / TS 29.272-adjacent GTPv2. Với Diameter phải build lại từ RFC 6733 + TS 29.272 (S6a) + TS 29.212 (Gx) — Gx PCC-rule semantics phức tạp hơn xa các IE PFCP đã study; VA code-spec cross-checking sẽ đắt hơn đáng kể.
6. **Threat model lệch**: paper giả định internet-attacker chạm N4/GTP-C do cloud misconfig, hoặc UE smuggle qua GTP-U. Với DRA: peers là MME/HSS/PCRF bên trong trust zone + roaming GRX/IPX — kẻ tấn công thực tế là downstream node bị chiếm hoặc roaming partner, không phải raw internet. Ngược lại DRA có bề mặt riêng không có trong 81 CVE: DOIC (OC-OLR) relay, redirect-agent semantics, realm-routing, peer watchdog (DWR/DWA), End-to-End AVP, TLS/IPsec Diameter — chẳng hạn forge watchdog/OLR để gạt peer khỏi rotation là attack vector không xuất hiện trong dataset.
7. **Relay ≠ endpoint**: các CN component trong paper là endpoint sở hữu pool/session. DRA relay chủ yếu mirror state (tx + binding); nhiều PA2/PC1 instance kiểu "handler deref IE của procedure nội bộ" không có对应 trực tiếp — tỷ lệ pattern áp dụng được thực tế sẽ thấp hơn 84/84, nhiều khả năng tập trung PB2/PB3/PC1 (state/reference/exhaustion ở tx + binding + overload) và PA1 (wire parse).
8. **Residue chưa confirm**: 83/84 confirmed, 3 findings chỉ là GitHub issue chưa có CVE (#818/#819/#4204) — khi viện dẫn số liệu cần ghi rõ 81 CVE / 83 confirmed / 84 discovered.
9. **Giới hạn tự nhận của phương pháp**: DA bị chặn bởi seed-pattern set; LLM sai feasibility khi exploitability nằm ở subtle spec semantics/implicit invariants — với Diameter (protocol state machine RFC 6733 + capex exchange) các implicit invariant còn nhiều hơn, nên recall kỳ vọng sẽ thấp hơn 68% của paper trừ khi bổ sung pattern Diameter-specific (watchdog, e2e-id, AVP-in-AVP, DOIC) — những pattern này chưa tồn tại trong seed set.

---

## Phụ lục — Artifact paths
- Patterns: `/tmp/opencode/iFinder/pattern/{PA1,PA2,PB1,PB2,PB3,PC1}.json`
- Ground truth 22: `/tmp/opencode/iFinder/dataset/open5gs/*.json` (17), `/tmp/opencode/iFinder/dataset/free5gc/*.json` (5)
- LogJudge: `/tmp/opencode/iFinder/src/ifinder/agents/oracle.py`; EA loop: `agents/exploitation.py` (2-layer oracle, teardown attribution); crash markers + exit codes: `testbed.py`
- Web data (84 entries): `/tmp/opencode/ifinder-web/data.js`
- Zenodo artifacts: https://zenodo.org/records/20534406
