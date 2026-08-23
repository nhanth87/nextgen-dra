# TRACK_T3_NOTES — Rule engine + config plane + LB + admin REST

Trạng thái: `mvn -q -pl dra-core test` XANH (74/74). `mvn -q -pl dra-app -am test`:
toàn bộ test T3 xanh; còn đỏ đúng 2 vấn đề pre-existing của T4 (mục ⚠ bên dưới),
đã có từ baseline TRƯỚC khi T3 thay đổi bất kỳ file nào. Quarkus `package`
(-DskipTests) XANH với CDI beans mới.

## 1. Files T3 sở hữu (tất cả trong sandbox /tmp/opencode/dra-t3)

### dra-core/engine (impl mới, không đụng frozen contracts)
- `KeyExtractorImpl.java` — extract keys chuẩn
- `Matcher.java` — sealed And/Or/Not/HasApp/HasCmd/RealmMatch/HostMatch/AvpMatch/
  PlmnMatch/DrmpAtLeast/IngressPeerIn/FlagIs/Always + PathNames chuẩn hoá path
- `Action.java`, `Rule.java`, `StickyLookup.java`, `EligibilityFn.java`
- `RuleEngineImpl.java` — contextFor/resolve/installRuleSet/updateCandidates,
  redirect cache TTL, counters LongAdder

### dra-core/lb (impl mới)
- `RrLoadBalancer`, `WeightedRrLoadBalancer` (smooth WRR nginx-style),
  `LeastOutstandingLoadBalancer` (tie→RR), `LoadAwareLoadBalancer`
  (weight ∝ 100−loadValue RFC8583; null → WRR theo static weight),
  `LoadBalancers` factory, `GroupRuntime` (candidates snapshot per group)

### dra-core/cfg (package mới)
- `RuleSetFile.java` (+Self/GroupCfg/Failover/RuleCfg nested),
  `MatcherCfg.java`, `ActionCfg.java`, `RuleSet.java` (compiled runtime),
  `JsonRuleSetLoader.java` (Jackson 2.17.2, custom polymorphic deser+ser),
  `DraConfigValidator.java`, `RuleSetHolder.java` (AtomicReference swap,
  validate-before-apply, last-good rollback)

### dra-app/app.admin (chỉ package này trong dra-app)
- `AdminPort.java`, `TelemetryPort.java` (interface + NOOP),
  `AdminWire.java` (@Produces @ApplicationScoped no-op defaults — RA thật của
  T1/T2 override bằng bean @Alternative/@Priority cao hơn là đủ),
  `PeersResource`, `RulesResource`, `BindingsResource`, `TelemetryResource`
- `dra-app/html/index.html` — HTMX dashboard (T7 copy vào dist/dra/html/)

### pom thay đổi: đúng 1 dep
- `dra-core/pom.xml`: thêm `com.fasterxml.jackson.core:jackson-databind:2.17.2`

### Tests mới (44)
core: RuleEngineResolveTest(5), RuleEngineStickyTest(4),
RuleEngineLoopHairpinEligibilityTest(4), KeyExtractorImplTest(9),
LoadBalancerStrategyTest(8), JsonRuleSetLoaderTest(5), RuleSetHolderTest(9).
app: AdminResourcesTest(3). Fixtures test: engine/Fixtures.java.

## 2. JSON config AUTHORITATIVE (T7 dùng làm seed template; SoT versioned ở PG)

```jsonc
{
  "version": <int, phải tăng đơn điệu so với current>,
  "self": { "originHost": "dra1.elisa.lab",
            "realms": ["epc.mnc01.mcc452.3gppnetwork.org"] },
  "peerGroups": {
    "<group-id>": {
      "lb": "RR|WEIGHTED_RR|LEAST_OUTSTANDING|LOAD_AWARE",
      "peers": [ { "id": "hss-a", "weight": 70 } ],
      "failover": { "enabled": true, "maxRetries": 1 }
    }
  },
  "rules": [ {
    "name": "<unique>", "priority": <int, sort asc, first-match-wins>,
    "when": <MatcherCfg>, "then": <ActionCfg>
  } ]
}
```

### MatcherCfg shapes (đủ catalog doc 03 §3)
| Matcher | JSON |
|---|---|
| And | `{"and":[<matcher>,...]}` |
| Or | `{"or":[...]}` |
| Not | `{"not":<matcher>}` |
| HasApp | `{"app":16777251}` |
| HasCmd | `{"cmd":[316,317]}` |
| RealmMatch | `{"realm":{"field":"DEST\|ORIG","op":"EQ\|SUFFIX\|REGEX","value":"..."}}` |
| HostMatch | `{"host":{"field":"DEST\|ORIG","op":"EQ\|SUFFIX\|REGEX","value":"..."}}` |
| AvpMatch | `{"avp":{"path":"...","op":"EQ\|PREFIX\|CONTAINS\|IN_LIST\|IP_IN_CIDR","value":"..."}}` |
| PlmnMatch | `{"plmnFrom":"IMSI\|MSISDN\|VISITED_PLMN","in":[..],"notIn":[..]}` |
| DrmpAtLeast | `{"drmpAtLeast":8}` |
| IngressPeerIn | `{"ingressPeerIn":["link-a"]}` |
| FlagIs | `{"flag":"R\|P\|E\|T"}` |
| Always | `{"always":true}` |

Path chuẩn hoá (alias đều nhận): User-Name/1→IMSI, MSISDN/701,
Visited-PLMN-Id/1407→VISITED_PLMN, Framed-IP-Address/8→FRAMED_IP,
Called-Station-Id/30→APN, Destination-Host/293→DEST_HOST,
Destination-Realm/283→DEST_REALM, Origin-Host/264→ORIG_HOST,
Origin-Realm/296→ORIG_REALM, Session-Id/263→SESSION_ID.

### ActionCfg shapes
- Forward: `{"forward":{"group":"g","sticky":{"key":"IMSI","ttlSecs":86400},
  "th":"OFF|PSEUDO_HOST_DETERMINISTIC|FULL_EDGE","allowHairpin":false,"ops":[...]}}`
  (sticky/th/allowHairpin/ops optional; ops: `{"appendRouteRecord":"host"}` |
  `{"set":{"code","vendorId","typeIndex","value"}}` | `{"drop":{"code","vendorId"}}`)
- Redirect: `{"redirect":{"host":"h.example","realm":"r.example","cacheSecs":60}}`
- Reject: `{"reject":{"resultCode":3002,"reason":"no-route"}}`

Round-trip: GET /api/rules trả lại đúng shape trên (custom serializer đầy đủ).

## 3. Decisions & hợp đồng cho track khác

1. **Loop guard = 3005 LOOP_DETECTED** (RFC 6733 đúng chuẩn); design doc ghi
   nhầm 3002. **Redirect = 3006 REDIRECT_INDICATION** (RouteDecision.Redirect
   do relay map ra answer 3006 + Redirect-Host/Usage/Max-Cache-Time).
   Nomatch/no-candidate fail-closed = 3002 UNABLE_TO_DELIVER.
2. **`RouteDecision.Forward.preferredPeerId` = peer egress ĐƯỢC CHỌN CUỐI**
   (sticky-hit nếu cùng group, nếu không thì LB chọn). T2 relay dùng trường này
   để `sendToPeer(preferredPeerId, ...)`. Đây là điểm bắt buộc để decision có
   thể route được.
3. **Sticky store key format**: `<KEY>:<value>` (vd `IMSI:452040123456789`).
   Decision.Forward.sticky.key trả về store-key đã resolve (không phải tên key
   cấu hình) khi ctx có giá trị; nếu message thiếu key ⇒ sticky=null (không có
   gì để bind). T4 BindingSbb capture theo đúng chuỗi này.
   Sticky hit khác group ⇒ rebinding: counter `dra_sticky_rebind_total` +
   audit hook consumer (wire sau vào audit_log của T4).
4. **Engine seam injection**: T2/T1 wiring tạo
   `new RuleEngineImpl(extractor, stickyLookup::get, eligibilityFn, clock, audit)`
   rồi đẩy candidates bằng `engine.updateCandidates(groupId, List<PeerHandle>)`
   (snapshot PeerHandle từ PeerRegistry.peersReady()). EligibilityFn phải cover
   ready && advertises(app) && !overloaded(reduction==0); engine tự thêm
   anti-hairpin (`p.peerId != ingressPeerId`) trừ khi rule bật allowHairpin.
5. **DrmpAtLeast(p)** đánh giá `ctx.drmpPriority() >= p` (theo nghĩa đen
   "≥ ngưỡng" trong doc 03 §3; LƯU Ý RFC 7944 số NHỎ = ưu tiên CAO — nếu muốn
   semantics theo RFC đổi thành <= ở 1 chỗ duy nhất: Matcher.DrmpAtLeast).
6. **Validator realm-loop check** (xấp xỉ, chỉ dựa được trên rules file):
   Forward TH=OFF + matcher chứa RealmMatch(DEST)/AvpMatch(DEST_REALM) mà value
   overlap với self.realms (EQ/SUFFIX/REGEX) ⇒ error "configuration loop".
   Group-level realm thuộc peers.json (T1) — nếu T1 muốn check chéo, chạy
   DraConfigValidator thêm pass thứ hai khi merge peers.
7. **Priority trùng** KHÔNG bị reject (doc ghi "khuyến nghị"); sort stable
   (priority asc, giữ thứ tự file) nên vẫn deterministic.
8. **Redirect cache**: nội bộ `Map<(realm,app),expiry>` TTL=cacheSecs;
   query `redirectCacheActive(realm, app)`; counter `dra_route_redirect_total`.
   Engine luôn trả Redirect (3006 do relay dựng) — cache hiện tại phục vụ
   quan sát/chính sách tiếp theo, không tự trả answer thay relay.
9. **Admin REST**: GET /api/peers, POST /api/peers/{id}/enable|disable,
   GET /api/rules, PUT /api/rules (400 kèm errors, last-good giữ nguyên),
   GET /api/bindings/count, GET /api/telemetry, dashboard tĩnh html/index.html.
   Default beans NOOP trong AdminWire — T1/T2 provide bean thật:
   `@Alternative @Priority(1) @ApplicationScoped class RaAdminPort implements AdminPort`.

## 4. ⚠ Cross-track cần integrator/T4 xử lý (ngoài quyền T3)

1. **MigrationSqlSanityTest đỏ sẵn ở baseline** (app/persist — T4-owned):
   - test đếm 6 statements nhưng `V1__dra_baseline.sql` chỉ có 5 (thiếu
     "trailing statement");
   - test assert `"KEY TEXT PRIMARY KEY"` single-space nhưng SQL align
     multi-space (`key             TEXT PRIMARY KEY`) ⇒ contains() fail;
     tương tự `ID BIGSERIAL`/`VERSION INT`/`PAYLOAD JSONB`/audit cột.
   Cách fix 1 dòng một phía (T2 chọn): bỏ alignment trong SQL hoặc normalize
   whitespace trong test. T3 KHÔNG đụng vì cấm app/persist/**.
2. **WriteBehindPersistence flaky** (dra-core bind — T4-owned):
   `drainLoop()` giữ `stagingLock` suốt `queue.poll(5ms)` (dòng ~99-109) trong
   khi `enqueue()`→`buffered()` cũng khoá `stagingLock` mỗi submit ⇒ submit có
   thể stall tới 5ms/lần; test budget 200ms/500 submit fail lúc máy load
   (load avg ~5-6 do các agent build song song). Gợi ý fix T4: poll NGOÀI lock
   (poll trước, vào lock chỉ để staging.put) hoặc dùng offer-with-deadline.
   Test standalone 3/3 pass; full-suite thỉnh thoảng đỏ.
3. **T7 seed**: dùng JSON shape §2 ở trên làm `configs/dra-rules.json` seed;
   nhớ bump version mỗi lần apply qua PUT /api/rules.

## 5. Verify đã chạy (JDK zulu-25 via mise)

- `mvn -q -pl dra-core test` → 74/74 xanh (nhiều lần; 1 lần đỏ do flake #4.2)
- `mvn -q -pl dra-app -am test` → dra-core 74/74 xanh trong reactor +
  AdminResourcesTest 3/3 xanh; failure duy nhất còn lại = MigrationSqlSanityTest
  (baseline T4, mục 4.1)
- `mvn -q -pl dra-app -am package -DskipTests` → quarkus-app build XANH với
  CDI beans admin mới
