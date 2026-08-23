# Elisa Nextgen DRA — Diameter Routing Agent trên micro-jainslee

**Mục tiêu:** DRA (Diameter Routing Agent) high-performance, viết như một ứng dụng
**micro-jainslee + ra-diameter**, hỗ trợ **N-N routing** (N client ↔ N server) với
bộ rule engine đầy đủ: realm/host routing, IMSI-prefix routing (MVNO), load
balancing, sticky binding, failover, overload control, topology hiding.

Trạng thái hiện tại: **THIẾT KẾ + PLAN** (chưa có code). Bắt đầu implement sau
khi design được duyệt.

## Tài liệu

| File | Nội dung |
|------|----------|
| [`docs/design/01-research.md`](docs/design/01-research.md) | Nghiên cứu domain DRA: spec 3GPP/GSMA/IETF, chức năng cốt lõi, landscape vendor, khoảng trống trong nhà |
| [`docs/design/02-architecture.md`](docs/design/02-architecture.md) | Kiến trúc Nextgen DRA: layers, module layout, luồng message, RA extension, threading, HA, config, packaging |
| [`docs/design/03-routing-rules.md`](docs/design/03-routing-rules.md) | Spec bộ rule engine N-N: matchers, actions, LB strategies, sticky binding, topology hiding, ví dụ cấu hình |
| [`docs/design/04-plan.md`](docs/design/04-plan.md) | Plan implement P0–P6: tasks, acceptance gates, test strategy, rủi ro |
| [`docs/specs/`](docs/specs/README.md) | Bản copy offline các spec 3GPP (Rel-19) + IETF RFC liên quan Diameter/DRA (.md) |

## Vị trí trong hệ sinh thái Elisa

- **elisa/** (IMS core) + **epc/** (Full MVNO core: HSS/AuC + SGW/PGW trong
  `sip-freeswitch` tree): đã tuyên bố "DRA/DRE/STP out of this tree — external
  DRA project". **Elisa Nextgen DRA chính là project đó**, chung họ với Elisa
  core MVNO. Xem `epc/docs/nni/host-mno-interface-contract.md`.
- **elisa/**: IMS core (Cx qua ra-diameter) — sẽ đứng sau DRA.
- **silent-authentication**: MAP/Diameter verifier — pattern tham chiếu.
- **micro-jainslee-2 / ra-diameter / corsac-diameter**: nền tảng chạy.

## Quy tắc bất di bất dịch (theo AGENTS.md toàn cục)

- **Java 25 only** (mise → zulu-25). Không hạ release xuống thấp hơn.
- Commit authorship **nhanth87 / Tran Nhan**, cấm mọi Co-authored-by/AI-trailer
  (attribution-guard hooks của workspace chặn).
- **Prove the artifact**: green test chưa đóng bài; phải package dist → rsync
  runtime bits → restart → chứng minh live bằng UI/API/log + jar mtime.
- Peer truth = **CER/CEA live (`isPeerReady()`)**, LISTEN ≠ ready. Không bao giờ
  route vào peer chưa ready; không trả silent 2xxx khi không deliver được.

## Bản quyền

Corsac Diameter là **AGPL v3** (local fork). Toàn bộ Nextgen DRA kế thừa điều
kiện này — không link tĩnh vào sản phẩm đóng. Ghi nhận trong mọi decision liên
quan tới việc fork thêm corsac (xem 04-plan § Rủi ro).
