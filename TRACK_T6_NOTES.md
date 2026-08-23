# TRACK_T6_NOTES — Topology hiding + security biên

## Đã làm (merge thẳng vào main tree bởi integrator — subagent upstream lỗi mạng 2 lần)

- `dra-core/src/main/java/et/elisa/dra/core/th/`:
  - `ThConfig` — record config (internalDomainSuffix, pseudoPrefix, pseudoCount, fullEdge, thGroups), immutable, validate ctor.
  - `PseudoHostMapper` — SHA-256 first-8-bytes stable hash → `pseudoPrefix-<n>.<suffix>`; `realFor()` parse ngược; `isInternalHost()`.
  - `TopologyHiderImpl` — implements seam `TopologyHider`:
    - `hideOutbound`: Origin/Dest-Host nội bộ → pseudo; Session-Id rewrite host-part giữ `;rest`; strip Route-Record(282) outbound; học reverse-map `pseudo→real`.
    - `restoreInbound`: pseudo→real từ reverse-map (miss → nguyên si + counter); append Route-Record selfOriginHost.
    - FULL_EDGE: quét đệ quy mọi AVP UTF8 mà host-part (trước `;`) là FQDN nội bộ → replace pseudo + counter leakBlocked. Không giới hạn theo code AVP — bắt cả leak kiểu MSISDN-host.
    - Mode OFF/group không bật: caller (`RelayCore`) không gọi hide/restore — passthrough tự nhiên.
  - `ThMetrics` — LongAdders hideTotal/restoreTotal/restoreMiss/leakBlocked.
- Tests: `PseudoHostMapperTest` (4) + `TopologyHiderImplTest` (5): deterministic 10k, phân bố ≥2 pseudo, round-trip identity+session-id+Route-Record, CLR-storm guard (cùng IMSI khác session → cùng pseudo), FULL_EDGE chặn leak grouped nested, passthrough external host nguyên vẹn record-equals.
- Kết quả: dra-core 39/39 xanh.

## TLS DECISION RECORD (plan T6 mục 2)

| Phương án | Quyết định | Lý do |
|-----------|-----------|-------|
| (a) SslHandler Netty trong fork corsac (TCP-only NNI) | **CHỌN** | TCP NNI biên IR.88 là đa số; Netty SslHandler mature; fork corsac đã là local AGPL fork — thêm handler không đổi kiến trúc. |
| (c) IPsec TS 33.210 tầng mạng cho SCTP | **CHỌN** | SCTP multi-stream của corsac không có DTLS mainstream trong Netty; IPsec ở mạng là pattern NDS/IP chuẩn vận hành. |
| SCTP-DTLS | HOÃN | Thiếu support thư viện; ghi rõ đây là gap nếu operator yêu cầu DTLS-on-SCTP. |
| (b) Sidecar TLS-terminating (stunnel/envoy) | PLAN B | Khi không được đụng fork corsac (vd build pipeline bên ngoài). |

AGPL: mọi sửa vào corsac fork (kể cả SslHandler) phải công khai source.

## Security checklist

- Secret/password: KHÔNG log trong RelayCore/admin (`SbbMetrics` chỉ counter; REST admin trả health/config, không echo credential); `application.properties` password datasource nằm configs/operator sở hữu, không hardcode.
- Admin auth: dùng bcrypt pattern elisa (tham chiếu silent-auth AdminHttpHandler); hiện `/api/*` mới có NOOP producer — **integrator phải bật auth trước khi expose ngoài loopback**.
- IR.88 checklist rút gọn: advertise Relay App-ID 0xFFFFFFFF ra ngoài (CorsacPeerFabric đã register toàn bộ + relay), allowlist app/cmd/IP per peering (T5 `ScreeningServiceImpl`), watchdog + restart/recovery (T1 PeerRegistry readiness law), TH pseudo deterministic chống CLR-storm (ở trên).

## Config keys đề xuất

```
dra.th.internal-domain-suffix=epc.mnc01.mcc452.3gppnetwork.org
dra.th.pseudo-prefix=dra-edge
dra.th.pseudo-count=4
dra.th.full-edge=false
dra.th.groups=ipx-edge
```

## Việc còn lại (integrator)

- Wire `TopologyHiderImpl` instance vào `RelayCore` (constructor param `TopologyHider`) với ThConfig từ `dra.th.*`.
- Reverse-map hiện in-memory per node — cluster mode cần replicate nếu edge node chết giữa dialog (chấp nhận: Diameter client retry).
