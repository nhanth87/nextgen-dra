# TRACK_T1_NOTES — Multi-peer Diameter RA (`dra-ra`)

Trạng thái: `mvn -q -pl dra-ra -am test` XANH (dra-ra 40 tests, gồm NNConnectionIntegrationTest socket thật).
JDK: mise `zulu-25`. Không git commit (theo law).

## 0. N-N connection proof (2026-08-23) — REAL-SOCKET INTEGRATION

`NNConnectionIntegrationTest`: 2 MME-ingress (chung 1 listen port) × 2 HSS-egress,
CER/CEA + ULR/ULA relay full round-trip qua TCP thật (raw peer helper
`RawDiameterEndpoint`). Fix để N-N chạy được:

1. **PeerConfig tách listen-port khỏi remote-port**: field `listenPort` (nullable)
   — SERVER peer: listen trên `effectiveListenPort()`, association khớp
   remote host:port = `port`. Cho phép N client vào chung 1 cổng ingress
   (corsac tự reuse acceptor cùng host/port/channelType).
2. **PeerConfig thêm remote identity**: `remoteIdentityHost/Realm` (JSON:
   `remoteHost`/`remoteRealm`) — corsac kiểm CER Origin-Host/Realm khớp
   link destination* (`MessageProcessingTask` "invalid remote hostname/realm"),
   sai ⇒ 3010. Fallback: id / primaryRealm.
3. **destinationHost=null làm corsac NPE** (`hostsMap.get(null)`) — luôn truyền
   non-null.
4. **Parser per-link**: mỗi `DiameterLinkImpl` có `DiameterParser` RIÊNG — đăng ký
   command packages vào global parser KHÔNG có tác dụng ingress decode. Fabric
   reflect vào field `parser` của từng link và register
   `DiameterRaConfig.commandPackages` (mặc định common+s6a+cxdx+gx+…22 gói,
   cấu hình được qua JSON). CẦN FORK CORSAC: thêm accessor `getLinkParser()`.
5. **rejectMandatoryAvps=false**: relay phải passthrough AVP ngoài dictionary;
   overload `addLink(..., FALSE, FALSE)` — nếu không ULA mang AVP lạ bị ném
   DIAMETER_AVP_UNSUPPORTED.
6. **registerApplication per-link**: cần cho capability matching lúc CER (không thì
   CEA 5010 NO_COMMON_APPLICATION). Dùng stub package `linkreg.LinkRegMarker` làm
   provider/package để tránh re-scan trùng lệnh (parser không idempotent — scan
   lại package đã register ⇒ throw "already registered").
7. **NetworkListener đăng ký 1 LẦN toàn cục** (`"dra-ra-ingress"`):
   `genericListeners` là map CHUNG mọi link — addNetworkListener per-peer ⇒ mỗi
   message fire N lần (số entry trong map).

Gap corsac fork còn mở (ghi nhận): `sessionID null` khi answer thiếu Session-Id
⇒ NPE `WorkerPool.addTaskLast`; nên fallback `sessionID=linkId`.

## 1. Kiến trúc adapter

```
SBB/T2 ──DraRaPort──► CorsacPeerFabric ──PeerRegistry gate──► corsac DiameterLink.sendEncodedMessage()
                          │                                        (raw bytes, any-command)
                          ├─ PeerRegistry (ConcurrentHashMap<peerId, PeerConnection>)
                          │    per-peer FSM IDLE→CER_SENT→OPEN→DOWN
                          │    readiness truth = channelUp && ceaOk && watchdogValid
                          │    capability map = advertisedApps bắt từ CEA
                          └─ link watcher (0.5s poll): isConnected/isUp/getPeerState==OPEN
                               → onChannelUp/onCeaAccepted/onChannelDown

corsac ingress: NetworkListener.onMessage(msg, linkId, cb)
   → CorsacMessageBridge.toIngressEvent → sealed IngressEvent
      IngressRequest(DiaMsg, ingressPeerId, receivedNanos)
      IngressAnswer (DiaMsg, egressPeerId,  receivedNanos)

Egress: DiaMsg → DiameterWireCodec.encode → ByteBuf → link.sendEncodedMessage
        (đi vòng qua parser annotation ⇒ relay được MỌI command, kể cả không decode được;
         hbh/e2e giữ nguyên bit trong header — passthrough nguyên vẹn)
```

Lớp chính (package `et.elisa.dra.ra`):
| File | Vai trò |
|---|---|
| `PeerConfig` | record id/host/port/role/transport/advertisedApps/group/weight/maxOutstanding |
| `DiameterRaConfig` | record peers/originHost/realms/watchdogIntervalMillis/twTimeoutMillis; factory `singlePeer(...)` backward-compat field cũ |
| `PeerRegistry` / `PeerConnection` / `PeerState` | registry + FSM + capability map; LongAdder outstanding; admission guard maxOutstanding |
| `CorsacPeerFabric implements DraRaPort` | wiring corsac thật: DiameterStackImpl + NetworkManager.addLink(peerId…) + registerApplication(toàn bộ ApplicationIDs + Relay 0xFFFFFFFF) + NetworkListener per peer |
| `SimulatedPeerFabric implements DraRaPort` | in-memory queue per peer-link, auto-answer callback, health settable — dùng cho test T1 và các track sau merge |
| `wire/DiameterWireCodec` | RFC6733 codec thuần: DiaMsg ↔ bytes (+peekHeader); raw passthrough, decode trả AVP dạng octets |
| `CorsacMessageBridge` | corsac `DiameterMessage` → `DiaMsg`: annotation trước, fallback peek header từ raw buffer; optional-AVP → octets; KHÔNG trả null cho command lạ (any-command) |
| Exceptions | `UnknownPeerException`, `PeerNotReadyException`, `AppNotAdvertisedException extends PeerNotReadyException` |

Fail-closed send path (cả hai fabric dùng chung `PeerRegistry.requireDeliverable`):
unknown → UnknownPeerException; !ready → PeerNotReadyException; appId ∉ CEA-advertised → AppNotAdvertisedException;
outstanding ≥ maxOutstanding → PeerNotReadyException. Caller (T2) quyết định 3002.
`sendAnswerOnLink` chỉ gate readiness (không lọc app) — answer phải quay đúng ingress link ngay cả khi
app-ID ngoài allowlist; hbh/e2e do caller set từ TxTable, RA không đụng vào.

## 2. Mapping DiaMsg ↔ corsac

| DiaMsg | corsac `DiameterMessage` |
|---|---|
| flags R/P/T bits | instanceof DiameterRequest + getIsProxyable/getIsRetransmit |
| commandCode/applicationId | `@DiameterCommandDefinition`; thiếu annotation → peek từ raw buffer (`getBuffer()`) |
| hopByHopId/endToEndId | getter object; fallback header |
| sessionId/origin*/destination* | safe getters (catch AvpNotSupportedException → "") |
| resultCode | DiameterAnswer.getResultCode() |
| avps | SessionId/UserName/Origin*/Destination* (typed) + mọi optional-AVP dạng octet raw (code/vendor/M-bit/rawBytes) |

Chiều ra KHÔNG build object corsac (tránh reflection annotation ~50 app): encode thô bằng
`DiameterWireCodec` rồi `sendEncodedMessage` — đúng semantics relay và bỏ qua toàn bộ
whitelist `canSendMessage()` của corsac.

## 3. Gap cần integrator hoàn thiện (socket-thật)

1. **Capability từ CEA phía remote**: corsac lưu app-ID peer advertise trong
   `DiameterLinkImpl.remoteAuthApplicationIds/remoteAcctApplicationIds` (private, không có getter trên
   interface `DiameterLink`). Hiện tại khi link OPEN lần đầu, fabric seed capability map =
   `PeerConfig.advertisedApps` (giả định peering đối xứng). **Cần fork corsac thêm accessor**
   (vd `getRemoteAuthApplicationIds()`) rồi gọi `registry().onCeaAccepted(peerId, apps)` với giá trị thật.
2. **Command lạ ở ingress**: `DiameterParser.decode` ném exception (DIAMETER_APPLICATION/COMMAND_UNSUPPORTED)
   TRƯỚC khi tới NetworkListener ⇒ command không đăng ký sẽ được corsac tự trả error, không bao giờ
   thành IngressRequest. Bridge + codec raw đã sẵn sàng (`fromRawFrame`); cần patch fork corsac
   `MessageProcessingTask` để fallback-deliver frame thô cho listener thay vì chỉ sendError.
3. **Connect/disconnect event**: corsac không push callback TCP up/down; fabric poll 0.5s
   (`LINK_POLL_MILLIS`). Nếu muốn realtime → fork thêm state-listener.
4. **Listen port nhiều SERVER peer**: mỗi link server bind riêng localPort=peer.port ⇒ 2 server peer
   cùng port sẽ fail bind thứ hai (peer đó bị markDown, fabric không chết). Cần config listen-plane
   hoặc fork chia acceptor nếu NNI yêu cầu.
5. **Outstanding**: giảm khi answer ingress trên cùng link (xấp xỉ); correlation hbh chính xác thuộc
   TxTable (T2) — RA counter là guard thô cho admission.
6. `DiameterStackImpl` khởi tạo parser scan jar (~vài giây lần đầu) — chỉ xảy ra trong `start()`,
   không nằm trong unit test hermetic.

## 4. Config contract JSON (CONTRACT giữa T1/T3/T7 — giữ nguyên tên field)

```json
{"peers":[{"id":"hss-a","host":"10.0.0.11","port":3868,"role":"SERVER","transport":"TCP",
"advertisedApps":[16777251],"group":"mvno-hss-pool","weight":70,"maxOutstanding":2000}],
"originHost":"dra1.elisa.lab",
"realms":["epc.mnc01.mcc452.3gppnetwork.org"],
"watchdogIntervalMillis":30000,"twTimeoutMillis":5000}
```

Loader: `et.elisa.dra.ra.cfg.DiameterRaConfigJson.parse/write`. Defaults khi thiếu field:
role=SERVER, transport=TCP, group="default", weight=1, maxOutstanding=2000,
watchdogIntervalMillis=30000, twTimeoutMillis=5000. `originHost` bắt buộc; peers có thể rỗng.
Backward-compat: `DiameterRaConfig.singlePeer(host,port,realm,originHost,productName,vendorId,
tcp,sctp,peerHost,peerPort,destHost,destRealm,role,watchdogMs)` → peer id `diameter-ra`
(LEGACY_LINK_ID). Lưu ý: destination* legacy không còn static — relay mang Destination-* theo từng
request (destinationRealm cũ được fold vào realms).

## 5. Test coverage (39)

- PeerRegistryTest (10): readiness gating đủ luật (LISTEN ≠ ready), watchdog-expiry phá ready,
  DOWN→reconnect, unknown/not-ready/unadvertised exceptions, answer-gate bỏ filter app.
- SimulatedPeerFabricTest (9): fail-closed khi DOWN, unknown peer, capability filter,
  roundtrip request→auto-answer (hbh/e2e passthrough), answer-on-link giữ hbh/e2e,
  admission guard + hồi phục sau answer, health truth.
- CorsacPeerFabricTest (8): registry seed từ config, unknown/down fail-closed không đụng socket,
  thứ tự gate (capability trước transport), answer gating, non-request bị chặn, lifecycle start/stop
  an toàn khi địa chỉ hỏng (teardown sạch workerPool/stack).
- DiameterWireCodecTest (6): header roundtrip, AVP padding/V-M flags/grouped, byte-stability
  encode-decode-encode, Result-Code chỉ ở answer, reject frame cắt cụt.
- DiameterRaConfigJsonTest (6): parse đúng CONTRACT_JSON shape trên, round-trip write→parse,
  defaults, originHost bắt buộc, role sai bị chặn, singlePeer factory mapping.

## 6. File tạo/sửa

- Sửa: `pom.xml` (thêm `<module>dra-ra</module>` sau dra-core — duy nhất 1 dòng).
- Tạo module: `dra-ra/pom.xml` + 17 file main + 5 file test (liệt kê ở §1).
