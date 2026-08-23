# test_plan_security_scan.md — Security Lab: iFinder → Nextgen DRA → sas-diameter-testapp

> Trạng thái: **DRAFT ĐỢI DUYỆT** — chưa chạy gì cho tới khi được approve.
> Ngày: 2026-08-23. Nền tảng research: `docs/security-lab/R1_IFINDER_NOTES.md`,
> `R2_PAPER_NOTES.md`, `R3_LAB_NOTES.md` (đọc kỹ trước khi review plan này).

## 0. Chiến lược (đã hiệu chỉnh theo chỉ đạo)

**KHÔNG fork iFinder riêng.** Viết thêm hỗ trợ Diameter vào đúng cấu trúc upstream
của iFinder theo convention sẵn có, định hướng **donate ngược lên repo
LinZiyuu/iFinder** để cộng đồng quét được cả hệ Diameter — đồng thời mình dùng
chính phần đóng góp đó để quét DRA:

```
schema/diameter/      ← KB viết mới từ RFC 6733 + TS 29.272/29.212 (re-authored, không copy)
procedure/diameter/   ← ~10 thủ tục JSON (ULR/AIR/PUR/NOR/IDR/CLR/CER/DWR/Gx-CCR-I)
scope/diameter/       ← scope_dra.json trỏ vào source Nextgen-DRA (Java — iFinder language-agnostic)
testbed/docker-dra/   ← compose: dra + sas-diameter-testapp (+ telemetry sidecar)
scripts/reproduce_*_dra.sh  ← wrapper DA→VA→EA theo mẫu sẵn có
src/                  ← thay đổi TỐI THIỂU (registry protocol + PoC prompt) — PR lên upstream
```

## 1. Mục tiêu & câu hỏi trả lời

1. DRA của mình có dính các **class lỗi implicit-trust** mà iFinder tìm ra trong
   84 vuln / **81 CVE** (GTP-C/PFCP: Open5GS 40, OAI 18, free5GC 14, SD-Core 7,
   eUPF 5) hay không — đối chiếu qua 6 pattern, KHÔNG phải so từng CVE đơn lẻ
   (84/84 vuln gốc là endpoint PFCP/GTP-C, không có Diameter nào — blind spot
   ghi rõ R2 §6).
2. Phần đóng góp `diameter/` đủ chuẩn để PR lên upstream (convention, schema
   nhất quán 3 chiều schema↔procedure↔trigger_message).
3. Bằng chứng trung thực về khả năng chống đỡ DRA: candidates → FEASIBLE →
   CONFIRMED, kèm token cost và thời gian (paper baseline: P=75%, R=68.2% trên
   GTP-C/PFCP; kỳ vọng thấp hơn cho Diameter vì nhiều surface relay-specific
   không có trong seed patterns).

## 2. Kiến trúc lab

```
┌─────────────────────────────────────────────────────────────┐
│ iFinder (claude-agent-sdk, DA→VA→EA+LogJudge)               │
│   DA: static scan source DRA × pattern                      │
│   VA: cross-check procedure/diameter prerequisites          │
│   EA: viết PoC Go Diameter-over-TCP → bắn vào :3868         │
└──────────────┬──────────────────────────────────────────────┘
               ▼
   ┌───────────────────────────┐        ┌─────────────────────────┐
   │ Nextgen DRA (:3868)       │ relay  │ sas-diameter-testapp    │
   │ RelayCore guard-chain     │──────► │ (HSS/PCRF sim :3868+)   │
   │ screen→loop→admit→rule→   │        │ peer hss-a.epc.mnc01…   │
   │ TH→tx→forward             │        └─────────────────────────┘
   │ + bench SeederClient role │        ▲
   │ "MME-attacker" (raw fuzz) │────────┘ không qua DRA? KHÔNG — mọi
   └───────────────────────────┘        attack đều QUA DRA
   Telemetry JSONL 1s/tick từ cả 2 node = feed duy nhất cho LogJudge
```

## 3. Workstreams & phase

### W1 — Adapt sas-diameter-testapp (copy vào tree này)
| # | Task | File |
|---|------|------|
| 1.1 | Copy nguyên trạng + đổi package/groupId thành et.elisa.dra.lab | toàn module |
| 1.2 | Flag hoá `--peer-host/--peer-realm/--tcp`, origin-host=hss-a.epc.mnc01.mcc452.3gppnetwork.org | HssDiameterServer, Main |
| 1.3 | `/api/metrics`: heap/thread/deadlock/FD/counters; hang-detect lastMessageAgeMillis; exit-reason file (OOM hook) | ControlWebServer, Main |
| 1.4 | `/api/subscriber` create-or-update + seed 5 profile: match-prefix/barred/detached/zero-vector/off-prefix | HssSimulator |
| 1.5 | MessageLog thêm LongAdder counters ngoài ring buffer 500 | MessageLog |

### W2 — Bootstrap DRA standalone (bắt buộc để lab chạy thật)
Wiring theo R3 §3: CorsacPeerFabric(config JSON) → RuleEngineImpl+RuleSetHolder ← configs/dra-rules.json · OverloadGateImpl · ScreeningServiceImpl · TopologyHiderImpl · DefaultTxTable+HbhAllocator · InMemoryBindingStore(+write-behind) · RelayCore(12 args) · classifier onRequest/serverInitiated · scheduler sweep() · CDI producers thay NOOP.
Gap dra-ra cần xử lý trước: CEA-apps accessor private (seed capability từ config), command-lạ bị corsac parse-fail TRƯỚC listener (fuzz raw đi đường bench), 1 SERVER peer/port.

### W3 — Đóng góp diameter/ vào cấu trúc iFinder
| # | Task | Ghi chú |
|---|------|---------|
| 3.1 | `schema/diameter/generated/{message_schemas,ie_catalog}.normalized.json` | re-author từ docs/specs/rfc/rfc6733.md + TS 29.272/29.212 local; S6a ~10 msg + Gx CCR/CCA; AVP catalog ~40 AVP dùng routing |
| 3.2 | `procedure/diameter/*.json` (~10 file) | ULR/AIR/PUR/NOR/IDR/CLR/CER/DWR/Gx-CCR-I; seq/from/to/mandatory_ies khớp tên schema verbatim |
| 3.3 | `scope/diameter/scope_dra.json` | trỏ source Java Nextgen-DRA; include dra-core+dra-ra+dra-app/sbbs |
| 3.4 | `testbed/docker-dra/docker-compose.yml` + Dockerfile | dra (jdk25 zulu dist) + testapp (jdk25 jar) + healthchecks; KHÔNG nhúng secret |
| 3.5 | `scripts/reproduce_one_candidate_diameter_dra.sh` + `reproduce_full_diameter_dra.sh` | copy-mô-phỏng wrapper sẵn có, port map 3868, `_nf_labels` dra/testapp |
| 3.6 | src/ minimal diff: registry protocol `diameter` + EA PoC prompt (Go raw-TCP Diameter encoder thay go-pfcp/go-gtp) | candidate PR upstream |

### W4 — Oracle instrumentation (thiết kế lại cho Java fail-closed)
Crash-regex của iFinder vô dụng với Java (R2 §6.2). Thay thế:
- Telemetry JSONL 1s/tick từ DRA + testapp (counters MetricsNames + heap/thread/deadlock).
- LogJudge signals ↔ pattern map (R3 §5): unknown-tx WARN >5/s→PB2; txActive tăng đơn điệu 30s→PC1;
  bindings Δ>50k/min→PB3/PC1; throttle-ratio≈1.0>5s→PC1; activeReports>0 khi testapp không emit OLR→PB1;
  th_restore_miss>0→PB3; deadlock≠null→PA1/PA2; heap>0.9 sustained→PC1; exit/OOM-marker→PA1/PB1;
  testapp req/ans lệch→PA2/PB2. Mỗi signal bắt buộc quote log nguyên văn (giữ luật evidence của LogJudge).

### W5 — Chạy quét
| Phase | Nội dung | Cost ước tính |
|-------|----------|---------------|
| S0 sanity | reproduce_one_candidate_open5gs_5g trên clone đầy đủ (tạm re-add target/open5gs subset nếu cần) | ~10 min, ~1-2M tokens |
| S1 static | DA×6 patterns + VA (--no-exploit, không cần docker) trên scope_dra | vài giờ, token chính nằm ở đây |
| S2 live | EA: PoC bắn DRA thật trong docker-dra, ≤5 vòng refine/candidate, LogJudge xác nhận | mỗi candidate ~10-15 min |
| S3 report | bảng pattern×candidates×FEASIBLE×CONFIRMED + evidence quotes | - |

## 4. Model ("gắn model mạnh vào")

iFinder chạy claude-agent-sdk, **Anthropic-only** (default claude-opus-4-5, config.py:13):
- Phương án A (khuyến nghị): `ANTHROPIC_API_KEY` thật + model mạnh nhất khả dụng (opus-class) cho DA/VA; EA có thể hạ sonnet-class tiết kiệm.
- Phương án B: gateway Anthropic-compatible trước claude CLI (Bedrock/vertex hoặc proxy OpenAI-format→Anthropic-format tự dựng) nếu muốn dùng model khác/local vLLM.
- ⚠️ CẦN USER CẤP: API key + chọn phương án. Full-eval một target ~9.8M tokens theo paper — đặt budget cap.

## 5. License & legal

- iFinder artifacts: PolyForm Noncommercial 1.0.0 → lab nội bộ R&D OK; đóng góp PR lên repo họ cũng nằm dưới license họ quản lý.
- Code mình donate (schema/procedure/scope/testbed/scripts): re-author từ spec công khai (RFC/3GPP), KHÔNG copy artifact của họ ngoài convention/shape.
- testbed chứa Nextgen-DRA + corsac (AGPL): publish testbed = công khai source phần liên kết — phù hợp cam kết AGPL hiện có của dự án (README đã ghi nhận); cần confirm một lần nữa trước khi PR/donate thật.
- Nếu quét ra lỗ hổng mới của chính DRA: sửa nội bộ trước; không disclose bên ngoài khi chưa fix.

## 6. Deliverables sau approve

1. `lab/sas-diameter-testapp/` (adapted) — build xanh.
2. DRA bootstrap chạy standalone: dashboard READY + 1 ULR qua relay tới testapp.
3. `ifinder-contribution/` (schema/procedure/scope/testbed/scripts cho diameter) — convention-check script pass.
4. Telemetry JSONL + oracle signal table implement.
5. Kết quả quét S1/S2 + `security_scan_report.md` (trung thực: số liệu thật, không claim nếu LogJudge UNCONFIRMED).

## 7. Rủi ro & biện pháp

| Rủi ro | Giảm thiểu |
|--------|-----------|
| Token cost bất ngờ (paper ~9.8M/target) | budget cap + bắt đầu S1 --no-exploit, review candidates trước khi bật EA |
| Convention lệch upstream (schema↔procedure↔trigger_message tên không khớp) | consistency checker tự viết trước khi chạy DA |
| Oracle false-positive trên Java behavioral | mọi CONFIRMED phải có quote log + repro thủ công bằng bench seeder |
| corsac parse-fail chặn fuzz raw | attack path đi bench DiaWire raw socket (đã có sẵn) song song đường corsac |
| AGPL/publish lo ngại | bước donate PR tách riêng, chỉ làm sau khi review lại license |

## 8. Không làm gì cho tới khi được duyệt

Không copy testapp, không đụng iFinder src/, không gọi API model tốn tiền,
không publish/donate gì — chỉ chuẩn bị plan này. Approve thì bắt đầu W1+W2 song song.
