# Elisa Nextgen DRA — Diameter Routing Agent trên micro-jainslee

**Mục tiêu:** DRA (Diameter Routing Agent) high-performance, viết như một ứng dụng
**micro-jainslee + ra-diameter**, hỗ trợ **N-N routing** (N client ↔ N server) với
bộ rule engine đầy đủ: realm/host routing, IMSI-prefix routing (MVNO), load
balancing, sticky binding, failover, overload control, topology hiding.

Trạng thái hiện tại: **IMPLEMENTED — lab-ready** (278 tests xanh; N-N relay
được chứng minh bằng socket thật; strict micro-jainslee wiring qua
`MicroSleeContainer`). Chưa chứng minh production capacity — xem runbook.

## Build & chạy nhanh

```bash
export JAVA_HOME=$(mise where java@zulu-25)   # JDK 25 only
mvn clean test                                # full suite
dist-tools/package-dist.sh                    # -> dist/dra (run.sh + configs + html)
```

Database: mặc định H2 file demo (`./data/dra`) không cần cài gì; production
export `DRA_DB_KIND=postgresql` + `DRA_DB_URL/DRA_DB_USER/DRA_DB_PASSWORD`.

Hướng dẫn giới thiệu từng bước (kèm HSS simulator): xem
`GETTING_STARTED.md` trong bản dist nén.

## Tài liệu nội bộ

Design/specs docs được giữ local (không track trong repo): `docs/design/*.md`,
`docs/specs/` (bản copy 3GPP Rel-19 + RFC), runbook vận hành.

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

## Licensing

Elisa Nextgen DRA is **dual-licensed** — pick the model that fits your use:

### 1. Open Source (GPLv3 / AGPLv3)

| Component | License | Notes |
|-----------|---------|-------|
| micro-jainslee core (`jainslee-core`, `jainslee-api`, vendor RAs) | **GPLv3** | JAIN SLEE 1.1 container family |
| `dra-core`, `dra-ra`, `dra-app`, `bench` | **GPLv3** | application code on top of the SLEE |
| corsac-diameter (transport, local fork) | **AGPLv3** | network-copyleft: distributing a build that links it requires publishing the corresponding source of your modifications |

Running the DRA as-is (your own network, no distribution) has no copyleft
obligations. If you distribute appliances or embed it into a closed product
under the open-source route, the GPLv3/AGPLv3 terms above apply in full.

### 2. Commercial License

Available from **Digicom-ET / Tran Nhan (nhanth87)** for operators and vendors
who need to:

- embed or redistribute the DRA without GPLv3/AGPLv3 copyleft obligations,
- receive SLA-backed support, hardening and certification for production NNI,
- ship closed-source derivatives.

Contact: `nhanth87@gmail.com`.

Copyright © 2026 Tran Nhan (nhanth87). All rights reserved where applicable.
