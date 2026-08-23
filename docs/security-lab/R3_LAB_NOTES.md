# R3_LAB_NOTES — Research lab: iFinder agents → Nextgen DRA (:3868) → sas-diameter-testapp (hss-a)

Ngày: 2026-08-23 · Agent: RESEARCH R3 · Read-only (không sửa code)
Nguồn: `silent-authentication/main/sas-diameter-testapp/**`, `Nextgen-DRA/{dra-core,dra-ra,dra-app,bench,configs}`, TRACK_{INTEGRATOR,T1,T2}_NOTES.md

---

## 1. TESTAPP HIỆN TRẠNG

### 1.1 Vai trò & transport
| Mục | Kết quả |
|---|---|
| Listen port | `--diameter-port` default **3868**, SCTP mặc định (`--tcp` để chuyển TCP); control web `--web-port` default **8086**; `--bind` default **127.0.0.1** (`Main.java:29-44`) |
| Role | **SERVER thuần** — `HssDiameterServer.start()` → `nm.addLink(LINK_ID, bind, 0, bind, diameterPort, true /*isServer*/, sctp, …)` (`HssDiameterServer.java:70-73`). **Không bao giờ dial-out** — client (SAS) phải dial CER vào nó |
| Config | Args CLI duy nhất (`--diameter-port/--web-port/--bind/--tcp`), không env, không file config |
| Peer identity kỳ vọng | **Hardcode**: local `hss.restlink.et`/`restlink.et`, remote kỳ vọng `CLIENT_HOST="sas.restlink.et"`/`CLIENT_REALM="restlink.et"` (`HssDiameterServer.java:40-43`). Ai dial CER với Origin-Host khác `sas.restlink.et` sẽ bị corsac từ chối ở mức link |
| Apps đăng ký | S6a 16777251, SWx 16777265, Gx 16777238 (`HssDiameterServer.java:89-94`). Commands anchor theo package corsac (ULR/AIR/IDR, MAR/SAR/PPR, CCR) |
| Association | **1 inbound association / listen port** (README caveat dòng 101-104) |

### 1.2 CER/CEA/DWR/DWA
Corsac stack xử lý base-protocol (CER/CEA/DWR/DWA) nội bộ ở tầng link — không bao giờ tới handler ứng dụng (cùng hành vi `CorsacMessageBridge.isBaseProtocol()` filter bên DRA). Testapp không tự trả gì cho base protocol.

### 1.3 Tolerate AVP lạ / decode fail
- Unknown **optional** AVP: corsac đưa vào optional-channel dạng octets — bằng chứng: `GxHandler.attachSubscriptionId()` chủ động gửi Subscription-Id như **unknown AVP 443, M-bit clear** để client đọc lại (`GxHandler.java:108-120`). Route-Record/Proxy-State không được model trong handlers → đi qua như optional octets.
- Decode fail / command lạ: corsac `DiameterParser.decode` ném DIAMETER_APPLICATION/COMMAND_UNSUPPORTED **trước** NetworkListener (giống T1 gap #2) → corsac tự trả error; **không có raw-passthrough** trong testapp. Frame độc TLV không chạm được code handler.
- AVP lạ có **M-bit set** mà dictionary corsac không biết: khả năng cao bị corsac reject protocol-error ở parser (chưa verify runtime — mục lab cần confirm).

### 1.4 Fail-safe handler
✅ Đã đạt: cả 3 handler đều wrap try/catch → `sendInitialAnswer(3002 UNABLE_TO_DELIVER)`; double-fault → `callback.onError(DiameterException(3002))`:
- `S6aHandler.onInitialRequest` (dòng 74-92) + `unableToDeliver()` factory từng command
- `SwxHandler.onInitialRequest` (dòng 72-90)
- `GxHandler.onInitialRequest` (dòng 65-84)

### 1.5 State & API sẵn có
- `MessageLog`: ring buffer 500 entry, `/api/messages` trả JSON đầy đủ (time/direction/command/session/result/details).
- `SubscriberState`: attached/barred/authVectorsAvailable/subscribedRat/lastEapAuthSuccess.
- `/api/subscriber` POST **chỉ update subscriber đã tồn tại** (`find().orElseThrow(BadRequest)`) — `HssSimulator.upsert()` có sẵn nhưng **chưa expose endpoint tạo mới**.
- `/api/binding` GET/POST/clear, DELETE `/{ip}`; seeded `10.20.30.40 → +251911111111/655010000000001`.
- `/api/reset`, `/api/health` (`{status, diameterListening}`).
- **Chưa có**: process metrics (heap/thread/FD), hang-detection, crash hook, message-rate counters ngoài ring 500.

---

## 2. ADAPT TESTAPP CHO LAB (file-level tasks)

### (a) Kết nối QUA DRA thay vì trực tiếp
| # | File | Task |
|---|---|---|
| A1 | `diameter/HssDiameterServer.java` | Flag hoá remote identity: thêm `--peer-host`/`--peer-realm` (thay hằng `CLIENT_HOST/CLIENT_REALM`), đặt = origin-host/realm của DRA: `dra1.elisa.lab` / `epc.mnc01.mcc452.3gppnetwork.org` (theo `configs/dra-peers.json`). Nếu corsac chỉ validate soft thì vẫn nên đổi cho CEA advertise khớp |
| A2 | `Main.java` | Parse 2 flag mới, pass xuống constructor `HssDiameterServer(hss, bind, port, sctp, peerHost, peerRealm)` |
| A3 | `README.md` (testapp) | Thêm section "Behind DRA": chạy `--tcp` (khớp transport TCP của PeerConfig DRA) + lưu ý tắt SAS backend vì 1 association/port; port layout lab: agents→DRA:3868, DRA→testapp:13868 |
| A4 | (DRA side) `configs/dra-peers.json` | Peer `hss-a` trỏ về testapp, **role=CLIENT** (DRA dial-out vào testapp listen — ngược với sample đang là SERVER), `advertisedApps:[16777251,16777238]` (thêm 16777265 nếu dùng SWx), host=127.0.0.1 port=13868 |

### (b) Instrument cho VA phát hiện "bị ảnh hưởng"
| # | File | Task |
|---|---|---|
| B1 | `web/ControlWebServer.java` | Thêm `GET /api/metrics`: `{heapUsedBytes, heapMaxBytes, threadCount, peakThreadCount, deadlockedThreadIds[], openFdCount, uptimeMillis}` — JDK `Runtime` + `ThreadMXBean.findDeadlockedThreads()` + `OperatingSystemMXBean` (hoặc đọc `/proc/self/status`), zero-dependency giữ phong cách hiện tại |
| B2 | `MessageLog.java` | Thêm `LongAdder` per-direction/command counters (req/ans totals, không mất khi ring quay) + expose trong `/api/messages?meta=true` — oracle cần total kể cả khi >500 msgs |
| B3 | `ControlWebServer.java` | Hang-detect: `AtomicLong lastIngressNanos` update trong mỗi handler `onInitialRequest`; `/api/health` thêm `lastMessageAgeMillis` + `heartbeat` (monotonic counter tăng mỗi request) — harness cảnh báo khi traffic đang bơm mà age > 10s |
| B4 | `Main.java` | JVM flags khi chạy lab: `-XX:+HeapDumpOnOutOfMemoryError -XX:OnOutOfMemoryError="touch /tmp/opencode/testapp-OOM"` (marker file cho VA) + shutdown hook ghi exit reason vào `/tmp/opencode/testapp-exit.json` |
| B5 | (mới) `web/Pages.java` | Panel hiển thị metrics (optional, thấp ưu tiên — API đủ cho harness) |

### (c) MME-role sim client (gửi ULR/AIR/PUR vào DRA)
Testapp **thuần server-side, không có code client nào**. Tái dùng bench module của DRA:
| # | File (Nextgen-DRA/bench) | Task |
|---|---|---|
| C1 | `SeederClient.java` | Dùng trực tiếp làm attack-agent baseline: TCP connect → CER chờ CEA 2001 → flood ULR (virtual threads), đo p50/p90/p99/max + timeout sweep. Đã đúng vai trò iFinder DA |
| C2 | `SeederClient.java` | Mở rộng: mode chọn command (ULR=316/AIR=318/PUR=321/NOR=323/ECR=324 — codes đã chuẩn hoá ở TRACK_INTEGRATOR §5), IMSI pool (prefix hợp lệ/lệch), flag gửi kèm AVP tuỳ chọn (Route-Record, Proxy-State, DRMP, OC-OLR giả, Destination-Host spoof) |
| C3 | `DiaWire.java` | Đã đủ primitives (`utf8/u32/encode/decodeHeader/resultCodeOf`) — chỉ cần helper cho AVP octets/grouped khi fuzz (mẫu `leaf()/groupedValue()` copy được từ `GxHandler.java:113-154`) |
| C4 | `FakeHssServer.java` | Giữ làm HSS giả phụ (configurable delay + % failure 3002) cho scenario failover/retry; testapp thật dùng cho correctness scenarios |
| C5 | `BenchScenario.java` | Thêm profile args: `--profile clean\|fuzz\|olr-forged\|loop-poison` để VA chạy kịch bản lặp được |

### (d) Seed subscriber state cho attack vectors
| # | File | Task |
|---|---|---|
| D1 | `web/ControlWebServer.java` | `/api/subscriber` POST đổi sang create-or-update qua `hss.upsert(imsi, msisdn)` (sinh MSISDN mặc định từ IMSI khi chưa có) — hiện tại không tạo IMSI mới được |
| D2 | `HssSimulator.java` | Seed bộ lab ngay trong constructor (hoặc `--seed-profile lab`): |

Bộ seed đề xuất (map trực tiếp sang rule `PREFIX 4520402` trong `configs/dra-rules.json`):
| IMSI | State | Kỳ vọng qua DRA |
|---|---|---|
| `452040200000001` | attached, không barred, 1 vector | route mvno-hss-pool → hss-a, capture binding IMSI |
| `452040200000002` | barred=true | ULA 2001 + OPERATOR_DETERMINED_BARRING xuyên TH |
| `452040200000003` | attached=false | ULA/AIA 5421 |
| `452040200000004` | authVectorsAvailable=0 | AIA success rỗng |
| `655010000000001` | demo gốc | off-prefix → rule default-drop-unknown → **3002 từ DRA** (không tới testapp) |
| binding `10.20.30.40` | mặc định | Gx/CCR path nếu test PCRF-pool |

---

## 3. DRA SIDE — BOOTSTRAP WIRING (package `app.bootstrap` đang RỖNG)

Class-level checklist (đúng tên/signature đã tra):

```
1. CONFIG LOAD
   DiameterRaConfig raCfg   = DiameterRaConfigJson.parse(Files.readString("configs/dra-peers.json"));
   RuleSetFile ruleFile     = new JsonRuleSetLoader().parse(Files.readString("configs/dra-rules.json"));
   RuleSetHolder holder     = new RuleSetHolder();           // ctor với Consumer<RuleSet> sink nếu muốn hot-push
   List<String> errs        = holder.applyCandidate(rawJson); // validate + last-good rollback
   DraConfigValidator       (chạy trước applyCandidate nếu wiring tách bước)

2. RA FABRIC (socket thật)
   CorsacPeerFabric ra      = new CorsacPeerFabric(raCfg);
   ra.setIngressListener(this::onIngress);
   ra.start();                                               // WorkerPool(4) + DiameterStackImpl + link watcher 0.5s

3. ENGINE
   KeyExtractor extractor   = new KeyExtractorImpl();
   StickyLookup sticky      = bindings::get;                 // lambda khớp @FunctionalInterface StickyLookup.get(String)
   RuleEngine engine        = new RuleEngineImpl(extractor, sticky, EligibilityFn.all());
   engine.installRuleSet(holder.runtime());
   engine.updateCandidates(groupId, List<PeerHandle>);       // seed từng group từ raCfg.peers(): PeerHandle(id, weight, …)

4. OVERLOAD (T5)
   OverloadGate overload    = new OverloadGateImpl(new OlrCache(), new LoadCache(),
                                new AdmissionController(globalRatePerSec, globalBurst,
                                                        peerRatePerSec, peerBurst));
   // rates từ application.properties: dra.overload.global-rate-per-sec=20000 / peer 8000; burst chọn ~rate/10

5. SCREENING (T5)
   Screener screener        = new ScreeningServiceImpl(ScreeningConfig.of(Map.of())); // ALLOW_ALL lab
   // hoặc peering rules: agents {cmdCodes: [316,318,321]}, hss-a {appIds: [16777251,16777238]}

6. TOPOLOGY HIDING (T6)
   TopologyHider th         = new TopologyHiderImpl(
                                new PseudoHostMapper(new ThConfig("epc.mnc01.mcc452.3gppnetwork.org",
                                    "dra-edge", 4, false /*fullEdge*/, Set.of() /*groups*/)),
                                selfOriginHost);

7. TX + BIND
   TxTable txTable          = new DefaultTxTable();
   HbhAllocator hbh         = new HbhAllocator();
   RelaySupport support     = new RelaySupport(twMillis=5000, maxRetries=1); // dra.tx.timeout-millis
   BindingStore bindings    = new InMemoryBindingStore(500_000);  // + WriteBehindPersistence(PersistenceHook) nếu cần persist
   ServerInitiatedResolver resolver = new ServerInitiatedResolverImpl(bindings);

8. RELAY CORE
   CandidateSource cand     = (groupId, exclude) -> engine.group(groupId)…peers trừ exclude; // adapter nhỏ quanh GroupRuntime
   RelayCore core           = new RelayCore(engine, txTable, hbh, ra, bindings, resolver,
                                 overload, screener, th, cand, "dra1.elisa.lab", 5000L, 1);

9. INGRESS BRIDGE (viết classifier — CHƯA CÓ SẴN)
   void onIngress(IngressEvent evt):
     IngressRequest r → isClientInitiated(r.msg()) ? core.onRequest(r.ingressPeerId(), r.msg())
                                                   : core.serverInitiated(r.ingressPeerId(), r.msg());
     IngressAnswer  a → core.onAnswer(a.msg(), a.egressPeerId());
   // isClientInitiated: destinationHost==selfOriginHost HOẶC command ∈ {CLR 317, IDR 319, DSR 320, RTR 338…}
   // (DraRelaySbb gọi onRequest; DraBindingSbb gọi serverInitiated — SBB shells tách sẵn, bootstrap chọn đường)

10. SCHEDULER
    quarkus-scheduler @Scheduled(every="0.2s") → core.sweep(System.currentTimeMillis());
    BindingSweepJob (T4) đã có cho expire bindings — bật qua properties dra.bindings.sweep-every-seconds=60

11. CDI PORTS (admin REST :8080 đang inject NOOP)
    @Produces @Singleton TelemetryPort → merge SbbMetrics.snapshot() + engine.counters() + txTable.activeCount()
    @Produces @Singleton AdminPort     → ra.peersHealth() + bindings.size() + registry enable/disable mapping
    // Resources: TelemetryResource/PeersResource/BindingsResource/RulesResource inject port qua constructor

12. PROPERTIES LAB
    copy configs/application.properties.sample → dra-app/src/main/resources/application.properties
    %dev: db-kind h2 mem (bỏ PG dependency lúc lab); dra.th.groups=mvno-hss-pool nếu muốn TH bật theo group
```

### dra-ra gaps chặn chạy thật (T1 §3 — ảnh hưởng lab)
| Gap | Ảnh hưởng lab | Work-around / cần fork |
|---|---|---|
| (1) CEA capability accessor private (`remoteAuthApplicationIds`) | Capability map = seed từ `advertisedApps` config — nếu testapp advertise khác config → `AppNotAdvertisedException` oan | Set advertisedApps đúng list apps testapp đăng ký (S6a+SWx+Gx); dài hạn fork corsac `getRemoteAuthApplicationIds()` |
| (2) Command lạ ingress bị corsac parse-fail TRƯỚC listener | **Frame độc/không đăng ký không bao giờ tới `DiameterWireCodec.decode` của mình** — corsac tự trả error. Attack surface codec-thuần chỉ còn chiều encode-egress + bench raw path | Fuzz codec phải qua module bench (raw TCP như SeederClient) hoặc fork `MessageProcessingTask` fallback-deliver raw frame |
| (3) Không push connect/disconnect event | Ready-truth trễ ≤0.5s (poll) | Chấp nhận cho lab |
| (4) 2 SERVER peer cùng port fail bind thứ hai | Chỉ **1** listen-plane peer trên :3868 | Một peer entry duy nhất cho toàn bộ attack agents; nếu corsac giới hạn 1 association/listen-link (như testapp) → nhiều port cho nhiều agent song song |
| (5) Outstanding giảm xấp xỉ theo answer-on-link | Counter leak khi answer lạc link → peer kẹt `maxOutstanding` → `PeerNotReadyException` | Monitor `outstanding` per peer trong lab; đây cũng là 1 attack surface (§4) |

---

## 4. ATTACK SURFACE MAP (góc nhìn iFinder DA/VA/EA, theo ưu tiên)

> Bối cảnh quan trọng: corsac parser đứng TRƯỚC mọi ingress vào :3868 (gap §3.2) ⇒ payload wire-level thô bị corsac nuốt/trả error trước khi tới code DRA. Surface mạnh nhất là **semantic abuse bằng frame hợp lệ**.

| # | Surface | Vector cụ thể | Guard hiện tại / lỗ hổng |
|---|---|---|---|
| 1 | **OverloadGate OLR giả** (egress-side) | Agent đóng vai egress (hoặc testapp bị chiếm) nhét `OC-SUPPORTED-FEATURES` + `OC-OLR{seq,report,reduction%,validity}` vào answer → `OverloadGateImpl.handleOlr` chấp nhận → `maxActiveReduction` scale token-bucket → throttle toàn cục | Chỉ guard seq-monotonic (`staleIgnored`); KHÔNG xác thực nguồn OLR; validity kéo dài hiệu lực. Ground truth oracle: testapp **không bao giờ** gửi OLR |
| 2 | **TxTable hbh lifecycle** | (a) Flood request không-answer → `pending`+`txTable` phình đến deadline 5s (memory pressure, P3); (b) egress độc inject answer đoán trúng `hbhOut` đang sống → ăn cắp/đốt tx người khác (correlate sai, P4); (c) hbh wheel `AtomicInteger++` dự đoán được | `onAnswer` chỉ tra `byHbhOut(hbh)` — không kiểm egressPeerId khớp `tx.egressPeerId`; DROP_UNKNOWN_TX là dấu hiệu |
| 3 | **BindingStore key pollution** | Flood ULR với IMSI phân biệt (`digitsOf(Username)` tự do) → LRU 500k entry đầy, evict binding thật (sticky hit-miss → LB lại, mất affinity); key `"IMSI:<digits>"` normalize làm collision giữa biểu diễn khác nhau | Cap 500k + TTL; không có quota per-ingress-peer; `binding_captured_total` vs `bindings count` lệch là dấu hiệu |
| 4 | **Loop-check poisoning** | Nhét `Route-Record` chứa chính xác `selfOriginHost` vào request của nạn nhân → `guardRejected` trả 3005 LOOP_DETECTED cho request hợp lệ (rejection-by-proxy) | Check chỉ soi ROUTE_RECORD == self; attacker cần biết origin-host (leak qua pcap/TH-miss) — kết hợp surface 7 |
| 5 | **DRMP forgery** | Set `DRMP` priority cao nhất trên mọi request → vượt `DrmpPolicy.CRITICAL_COMMANDS` weighting trong AdmissionController → đói các flow thường | Token-bucket global+peer có; DRMP chỉ scale trọng số — priority giả vẫn thắng trong bucket |
| 6 | **Screening bypass/abuse** | `ScreeningConfig.of(Map.of())` = ALLOW_ALL mặc định lab; realm-suffix matching; `trustedNoProxy` chỉ đếm `foreignProxyState` không reject | Nếu bootstrap quên seed rules → mọi agent được mọi app/cmd; EA kiểm chứng bằng request app-ID sai phải ăn 3007 |
| 7 | **TopologyHider map-miss/leak** | `restoreInbound` miss (`learnedRealByPseudo` chưa học) → pseudo-host lộ nguyên văn ra ingress (P4/P5); Session-Id không có ';' bỏ qua rewrite; pseudo-name SHA-256 deterministic trên key biết trước (IMSI) → brute-force map ngược real-host | `th_restore_miss_total` > 0; FULL_EDGE scan đệ quy chỉ bật khi `fullEdge=true` |
| 8 | **Admin REST :8080 exposure** | `POST /api/peers/{id}/disable` không auth → cắt peer giữa run; `PUT` rules hot-reload JSON bệnh → CPU spike; telemetry lộ topology | AGENTS.md: bcrypt auth BẮT buộc trước khi expose — lab phải bind 127.0.0.1 |
| 9 | **LB/outstanding self-DoS** | Pin traffic vào 1 peer → outstanding ≥ maxOutstanding → `PeerNotReadyException` → failover → lặp (retry storm ULR/AIR retryable); answer-lạc-link làm counter leak vĩnh viễn (T1 gap 5) | `FAILOVER_TOTAL`, `SEND_FAILED_TOTAL`, outstanding gauge |
| 10 | **Wire codec TLV** (chiều encode + bench raw path) | `decode`: `length-4 > readableBytes` guard OK nhưng padding-skip có điều kiện `offset+avpLength+padding <= length` → frame biên gây offset-drift đọc AVP sai; `estimateSize` overflow với AVP khổng lồ; grouped-nesting KHÔNG recurse (giữ opaque bytes) — rủi ro nằm ở corsac parser phía trước, không phải codec mình | Codec mình: reject frame cụt ✅; corsac front là lớp chịu TLV walk thật |

---

## 5. ORACLE INSTRUMENTATION (LogJudge-style)

Quy ước 6 patterns: **P1** crash · **P2** hang · **P3** resource-exhaustion · **P4** data-corruption · **P5** security-violation · **P6** performance-degradation.

### 5.1 Thêm log/metrics vào DRA bootstrap (task integrator, không đụng frozen contracts)
```java
// RelayCore.onAnswer nhánh tx==null: hiện chỉ inc metric — THÊM:
LOG.warn("[ORA][P4] unknown-tx hbh={} egress={} cmd={} app={}", ans.hopByHopId(), egressPeerId, …);

// Telemetry line mỗi 1s ra stdout (JSONL — feed trực tiếp LogJudge):
{"ts":"…","ora":true,"txActive":N,"bindings":N,"throttledRate":0.87,
 "counters":{"dra_drop_unknown_tx_total":N,"dra_loop_detected_total":N,…},
 "heap":{"used":N,"max":N},"threads":{"count":N,"deadlocked":N}}
```

### 5.2 Signal → pattern map (ngưỡng đề xuất)
| Signal | Nguồn | Ngưỡng cảnh báo | Pattern |
|---|---|---|---|
| `unknown-tx` warn rate | log WARN mới + `dra_drop_unknown_tx_total` delta | > 5/s trong 5s | **P4** (answer-injection/hbh corrupt) |
| `txActive` gauge | `DefaultTxTable.activeCount()` qua telemetry JSONL | tăng đơn điệu 30s liên tục, hoặc > 10_000 | **P3** (pending leak / flood-no-answer) |
| `bindings` gauge | InMemoryBindingStore size | Δ > 50_000/phút hoặc chạm cap 500k | **P3/P5** (key pollution) |
| `throttle ratio = throttled/(admitted+throttled)` | AdmissionController counters | ≈ 1.0 kéo dài > 5s khi load bình thường | **P6** (OLR giả hiệu lực — cross-check signal dưới) |
| `olrCache.activeReports() > 0` | OlrCache qua telemetry | > 0 trong lúc testapp **không bao giờ** emit OC-OLR | **P5** (DOIC forgery accepted) |
| `dra_loop_detected_total` delta | SbbMetrics | bất kỳ khi nào agent KHÔNG chủ động poison | **P5** (loop-poison hoặc loop-check false-positive) |
| `dra_screen_rejected_total` delta | SbbMetrics | request hợp lệ bị 3007/3002 từ screen | **P5/P4** (screener mis-config) |
| `th_restore_miss_total` | ThMetrics | > 0 khi TH bật | **P4** (pseudo-host leak ra ingress) |
| `p99 latency` | `SeederClient.Stats.p99Nanos` | > 10× baseline sạch (vd > 50ms) | **P6** |
| loss% / timeouts | `SeederClient.Stats` | > 0.1% khi không chủ động kill peer | **P6/P2** |
| `deadlocked != null` | ThreadMXBean `/api/metrics` | bất kỳ | **P2** (deadlock) |
| `lastMessageAgeMillis` | testapp `/api/health`+B3 | > 10s trong khi agent đang bơm traffic | **P2** (DRA ngầm chuyển tiếp — hang ngược) |
| `heap.used/max > 0.9` 60s | `/api/metrics` cả 2 process | sustained | **P3** (near-OOM) |
| process exit / OOM marker file | harness giám sát PID + `/tmp/opencode/testapp-OOM` | exit≠0 hoặc marker xuất hiện | **P1** (crash) |
| `dra_send_failed_total` + `dra_unable_to_deliver_total` | SbbMetrics | tăng sau khi KHÔNG ai kill peer | **P4** (outstanding-leak self-DoS) |
| `outstanding` per-peer | PeerRegistry healthMap | kẹt gần maxOutstanding, không hồi | **P4/P3** |
| Admin REST calls | access-log :8080 | request từ IP ≠ 127.0.0.1 | **P5** |

### 5.3 Testapp-side oracle (ground truth)
- `/api/messages` (ring 500) + B2 counters: mọi ULR/AIR/PUR mà DRA forward phải xuất hiện **đúng 1 lần req + 1 lần ans**; lệch ⇒ DRA duplicate/drop (P4).
- Result-code distribution: agent nhận 3002/3004/3005 từ DRA phải giải thích được bằng state seed (barred→ULA-2001+barring xuyên suốt; off-prefix→3002 từ DRA **không thấy request ở testapp** — phân biệt "reject-at-DRA" vs "error-from-HSS").

---

## 6. TÓM TẮT EXECUTION PLAN LAB (thứ tự đề nghị)
1. A1-A4 + D1-D2 (testapp peer-flag + seed API) — nhỏ, Unblock ngay.
2. B1-B4 instrument testapp + 5.1 telemetry JSONL ở DRA bootstrap.
3. Bootstrap wiring §3 (1-12) với %dev H2, bind admin 127.0.0.1.
4. C1-C2 mở rộng SeederClient thành attack agent (mode/profile).
5. Chạy baseline sạch → ghi oracle thresholds → bật từng attack vector theo §4 thứ tự ưu tiên.
