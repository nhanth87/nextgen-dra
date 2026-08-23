# docs/specs — Spec 3GPP + IETF cho Nextgen DRA

Bản copy offline phục vụ design/implementation. Mỗi file có header ghi nguồn +
ngày tải (2026-08-23). Bản gốc `.zip` nằm trong `_orig/` (để re-convert).
Tất cả 3GPP là **Release 19, phiên bản mới nhất** tại ngày tải.

## 3GPP Technical Specifications

| File | Vai trò với Nextgen DRA |
|------|------------------------|
| `TS-29.213_Rel19_v3.0.0.md` | ★ **Spec DRA chuẩn**: định nghĩa DRA, cơ chế binding Gx/Gxx/Rx/S9 về cùng PCRF của một IP-CAN session |
| `TS-23.002_Rel19_v0.0.0.md` | Kiến trúc mạng — DRA là network entity nào, đặt ở đâu |
| `TS-23.003_Rel19_v7.0.0.md` | Định dạng realm bắt buộc khi routing (`epc.mnc.mcc.3gppnetwork.org`, §19.2) — input cho rule engine |
| `TS-29.272_Rel19_v5.0.0.md` | S6a/S6d/S13/SLg MME↔HSS — application được relay nhiều nhất; AVP User-Name=IMSI, Visited-PLMN-Id dùng làm routing key |
| `TS-29.212_Rel19_v1.0.0.md` | Gx/Gxx PCEF↔PCRF — Framed-IP là key cho PCC binding |
| `TS-29.214_Rel19_v3.0.0.md` | Rx AF↔PCRF — chiều thứ hai của binding PCC |
| `TS-29.229_Rel19_v1.0.0.md` | Cx/Dx IMS (I/S-CSCF↔HSS) — elisa sẽ đứng sau DRA trên interface này |
| `TS-29.328_Rel19_v2.0.0.md` | Sh IMS AS↔HSS |
| `TS-32.299_Rel19_v0.0.0.md` | Rf/Ro charging (OCS/OFCS) — pool OCS qua DRA |
| `TS-33.210_Rel19_v3.0.0.md` | NDS/IP: IPsec/TLS ở biên interconnect (yêu cầu security DEA-mode) |

## IETF RFC

| File | Vai trò |
|------|---------|
| `rfc/rfc6733.md` | ★ Diameter Base Protocol: agent types (Relay/Proxy/Redirect), realm routing table, Route-Record, Proxy-Info, failover — "bible" của DRA |
| `rfc/rfc7075.md` | Realm-Based Redirect (3005 + Redirect-Host caching) |
| `rfc/rfc7683.md` | DOIC overload control (OC-Supported-Features / OC-OLR) — reacting node của DRA |
| `rfc/rfc7944.md` | DRMP priority AVP — throttle theo priority |
| `rfc/rfc8583.md` | Diameter Load Information — load-aware load balancing |

## Ghi chú

- Re-download/re-convert: chạy lại script logic trong
  `/tmp/opencode/fetch_specs.py` (FTP `ftp.3gpp.org/Specs/archive/<X>_series/`,
  chọn zip mới nhất, pandoc DOCX→GFM). Pandoc binary:
  `/tmp/opencode/pandoc/pandoc-3.6.4/bin/pandoc`.
- File MD giữ nguyên cấu trúc heading của spec gốc → grep theo số mục trực tiếp
  được (ví dụ tìm binding: `grep -n -i "diameter routing agent" TS-29.213*.md`).
