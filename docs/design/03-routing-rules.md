# 03 — Routing Rule Engine (N-N)

> Trái tim của Nextgen DRA. Mọi quyết định route đi qua engine này; không có
> bảng đích tĩnh kiểu corsac. Engine nằm ở `dra-core/engine`, thuần Java,
> immutable, unit-test không cần socket.

## 1. Nguyên tắc N-N

- **N peers mỗi bên**: mọi link bình đẳng — một peer có thể vừa là "client"
  (MME/P-GW phát ULR/CCR) vừa là "server" (HSS phát CLR/IDR). Rule đánh giá
  theo **nội dung message**, không theo vai trò cấu hình.
- **Bidirectional**: một rule-set duy nhất áp cho cả hai chiều.
- **Deterministic**: cùng input (message + binding-state) ⇒ cùng decision;
  phần ngẫu nhiên chỉ nằm trong LB strategy và được log (peer chosen).
- **Fail-closed**: không match rule nào và không có default ⇒
  `DIAMETER_UNABLE_TO_DELIVER` (3002) + counter `dra_route_nomatch`.

## 2. Data model

```java
record Rule(String name, int priority, Matcher when, Action then) {}
sealed interface Matcher permits And, Or, Not, HasApp, HasCmd,
        RealmMatch, HostMatch, AvpMatch, PlmnMatch, DrmpAtLeast,
        IngressPeerIn, FlagIs {}   // FlagIs: R/E/P bit của header
sealed interface Action permits Forward, Redirect, Reject {}
record Forward(String group, StickyPolicy sticky, FailoverPolicy failover,
               ThMode th, List<AvpOp> ops) {}
record Redirect(String host, String realm, long cacheSecs) {}  // RFC 7075
record Reject(int resultCode, String reason) {}
```

Config serialize ra JSON (`configs/dra-rules.json`; SoT versioned ở PG):

```jsonc
{
  "version": 7,
  "self": {
    "originHost": "dra1.elisa.lab",
    "realms": ["epc.mnc01.mcc452.3gppnetwork.org",
               "ims.mnc01.mcc452.3gppnetwork.org"]
  },
  "peerGroups": {
    "mvno-hss-pool": {
      "lb": "WEIGHTED_RR",          // RR|WEIGHTED_RR|LEAST_OUTSTANDING|LOAD_AWARE
      "peers": [
        { "id": "hss-a", "weight": 70 },
        { "id": "hss-b", "weight": 30 }
      ],
      "failover": { "enabled": true, "maxRetries": 1 }
    }
  },
  "rules": [
    {
      "name": "s6a-mvno-hss",
      "priority": 100,
      "when": {
        "and": [
          { "app": 16777251 },                       // S6a
          { "avp": { "path": "User-Name",            // IMSI
                     "op": "PREFIX", "value": "4520402" } }
        ]
      },
      "then": {
        "forward": {
          "group": "mvno-hss-pool",
          "sticky": { "key": "IMSI", "ttlSecs": 86400 }
        }
      }
    },
    {
      "name": "roaming-out-vplmn-edge",
      "priority": 200,
      "when": {
        "and": [
          { "app": 16777251 },
          { "plmnFrom": "IMSI", "notIn": ["45201", "45204"] }
        ]
      },
      "then": {
        "forward": {
          "group": "ipx-edge",
          "th": "PSEUDO_HOST_DETERMINISTIC"           // topology hiding IR.88
        }
      }
    },
    {
      "name": "gx-pcrf-binding",
      "priority": 300,
      "when": { "and": [ { "app": 16777238 } ] },    // Gx
      "then": {
        "forward": {
          "group": "pcrf-pool",
          "sticky": { "key": "FRAMED_IP_APN", "ttlSecs": 43200 }
        }
      }
    },
    { "name": "default-drop-unknown", "priority": 65000,
      "when": { "always": true },
      "then": { "reject": { "resultCode": 3002,
                            "reason": "no-route" } } }
  ]
}
```

## 3. Matcher catalog

| Matcher | Ý nghĩa | Ghi chú |
|---------|---------|---------|
| `HasApp(id)` | Application-ID header | |
| `HasCmd([codes])` | Command-Code | ví dụ ULR=316, AIR=318, CLR=317, IDR=320… |
| `RealmMatch(field, op, v)` | Destination/Origin-Realm | `op`: EQ / SUFFIX / REGEX-precompiled; field ∈ DEST, ORIG |
| `HostMatch(field, op, v)` | Destination/Origin-Host | |
| `AvpMatch(path, op, value)` | điều kiện theo AVP | path đã chuẩn hoá (§4); `op`: EQ, PREFIX, CONTAINS, IN-LIST, IP-IN-CIDR |
| `PlmnMatch(fromKey, in/notIn)` | MCC-MNC từ IMSI/realm/Visited-PLMN | so khớp nhanh bằng prefix-tree |
| `DrmpAtLeast(p)` | DRMP AVP ≥ ngưỡng | RFC 7944 |
| `IngressPeerIn([ids])` | message đến từ peer nào | dùng cho policy per-peering |
| `FlagIs(bit)` | R/E/P-bit | hiếm dùng |

## 4. AVP key extraction (pre-compiled extractor)

Engine không parse AVP lúc route bằng string — mỗi `path` compile 1 lần thành
extractor có type:

| Path chuẩn | Nguồn AVP | Kiểu |
|------------|-----------|------|
| `IMSI` | User-Name (S6a/S6d), Subscription-Id-Data | UTF8 digits |
| `MSISDN` | MSISDN / Subscription-Id-Data(0) | E.164 |
| `VISITED_PLMN` | Visited-PLMN-Id (3GPP 1408) | MCC+MNC từ octets TBCD |
| `FRAMED_IP`, `APN` | Framed-IP-Address (8), Called-Station-Id (30) | IPv4/6, UTF8 |
| `DEST_HOST`, `DEST_REALM`, `ORIG_HOST`, `ORIG_REALM` | header-AVP 293/283/264/296 | DiameterIdentity |
| `SESSION_ID` | Session-Id (263) | UTF8 |

AVP không biết trước vẫn relay nguyên vẹn (raw passthrough) — extractor chỉ đọc
những AVP nằm trong rule config. Không bao giờ decode-sửa AVP M-bit bắt buộc
mà mình không hiểu (screening riêng xử lý).

## 5. Thuật toán resolve

```text
resolve(ctx):
  b = bindingStore.peek(ctx.stickyKeys)        // hit ⇒ skip rule? KHÔNG:
  for rule in rulesSortedByPriority:           // vẫn chạy matcher để chọn nhóm
    if rule.matches(ctx):
      d = actionOf(rule)
      if d is Forward and d.sticky and (hit = binding.get(d.sticky.key)):
          group = hit.group; preferredPeer = hit.peerId
      else:
          group = d.group; preferredPeer = none
      peer = lb(group).choose(ctx, preferredPeer, eligibility)
      return FORWARD(peer, transforms(rule, ctx))
  return defaultAction   // reject/unable-to-deliver
eligibility(p) = p.isPeerReady() && p.advertises(appId)
                 && !overloaded(p) && p.id != ctx.ingressPeerId  // anti-hairpin
```

- **Anti-hairpin**: mặc định không route ngược về ingress link trừ khi rule
  bật `allowHairpin` (loop prevention bổ sung cho Route-Record check).
- **Loop guard khác**: nếu Route-Record đã chứa `self.originHost` ⇒ trả 3002
  (RFC 6733 §6.5 spirit).
- **Sticky hit** vẫn phải qua matcher để đổi group khi operator đổi rule —
  nhưng ưu tiên `preferredPeer` trong cùng group mới; nếu group khác ⇒ rebinding
  + audit event.
- **LB strategies**:
  - `RR`: wheel AtomicInteger.
  - `WEIGHTED_RR`: smooth weighted RR (nginx-style).
  - `LEAST_OUTSTANDING`: min outstanding-counter per peer.
  - `LOAD_AWARE`: weight ∝ Load AVP RFC 8583 (host-type), fallback WRR.
  - Eligibility lọc trước; tất cả strategy O(candidates).

## 6. Action phụ trợ

- `Redirect(host[,realm], cacheSecs)`: trả `DIAMETER_REDIRECT_INDICATION`
  (3005) + Redirect-Host/Redirect-Host-Usage/Redirect-Max-Cache-Time; cache
  redirect nội bộ theo (realm, app) như RFC 6733 §6.13 / RFC 7075.
- `Reject(code, reason)`: dựng answer error với Result-Code + Failed-AVP nếu có.
- `AvpOp` kèm Forward: `APPEND_ROUTE_RECORD`, `SET(path,value)`,
  `DROP(path)`, `TH_REWRITE(mode)` — áp lên bản copy của message (message
  object immutable sau decode; transform tạo bản encode mới).

## 7. Topology hiding modes (`ThMode`)

| Mode | Hành vi |
|------|---------|
| `OFF` | giữ nguyên identity (peering nội bộ tin cậy) |
| `PSEUDO_HOST_DETERMINISTIC` | map Origin/Dest-Host ↔ pseudo theo hash(IMSI); Session-Id rewrite; Route-Record strip phía ngoài — chống CLR-storm (Oracle-style) |
| `FULL_EDGE` | thêm: chặn mọi internal host leak trong AVP dạng identity, allowlist app/cmd theo peering |

## 8. Validation & hot reload

Validator (`DraConfigValidator`) chạy trước apply:

- mọi `group` tham chiếu tồn tại và có ≥1 peer; peer-id tồn tại trong peers.json
- app-ID hợp lệ (bảng known-apps); command-code số học
- không rule nào có `forward.group` trỏ vòng về realm của self với TH=OFF
  (chống loop cấu hình)
- version tăng đơn điệu; diff ghi vào `audit_log`

Apply = swap volatile reference `AtomicReference<RuleSet>` — zero-downtime,
in-flight tx tiếp tục dùng rule cũ đến khi hoàn tất. Sai validation ⇒ giữ
last-good + trả lỗi cho admin UI (pattern silent-auth).

## 9. Test matrix cho engine (unit, không socket)

1. Priority ordering & first-match-wins.
2. Sticky hit/miss/rebinding giữa 2 group.
3. Anti-hairpin + Route-Record loop → 3002.
4. Eligibility loại peer chưa-ready/app-không-hỗ-trợ → chọn peer kế.
5. LB: phân bố WRR 70/30 trên 10k picks (±2%); LEAST_OUTSTANDING chọn đúng.
6. PlmnMatch từ IMSI vs từ Visited-PLMN-Id octets.
7. Redirect cache TTL expiry.
8. Validator: group mồ côi, version giảm, realm-loop → reject config.
