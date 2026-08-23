# TRACK_T7_NOTES — Bench + packaging + runbook

> Implement trực tiếp trong main tree bởi integrator (subagent upstream lỗi mạng 2 lần liên tiếp).

## Đã làm

### bench module (`et.elisa.dra.bench`) — 4/4 test xanh
- `DiaWire`: encoder/decoder Diameter thô trên byte[] — header 20B (version|len24|flags|cmd24|app|hbh|e2e) + AVP (code4+flags1+len3 [+vendor4], data pad 4, **AVP Length field KHÔNG tính padding** per RFC 6733 §4). Helpers utf8/u32/resultCodeOf.
  - **Bug đã sửa**: nhầm AVP header 12/16 bytes → đúng là 8/12; cả encoder lẫn decoder.
- `DiaStream`: framing TCP readFrame (đọc header → length → body). Public để tool ngoài dùng.
- `FakeHssServer`: ServerSocket + virtual threads; CER→CEA 2001 advertise app 16777251, DWR→DWA, ULR→ULA 2001 (delay/failure-rate injectable); LongAdders requests/answers.
  - **Bug đã sửa**: tạo BufferedInputStream mới mỗi lần readFrame làm mất byte đã buffer → giữ 1 stream bền per connection.
- `SeederClient`: N connections, handshake CER/CEA trước, paced send theo TPS (nanoTime schedule), correlate ULA theo hbh, histogram log-scale 40 buckets (0.1ms..10s) → p50/p90/p99/max; timeout sweeper; max latency CAS.
  - **Bug đã sửa**: `SEQ_STEP` identity function khiến MỌI request mang hbh=2 → correlate rác. Đổi `incrementAndGet()`.
- `BenchScenario main(--host --port --tps --duration-s --connections --imsi-prefix --timeout-ms)`: warmup không riêng (chạy thẳng), in bảng kết quả + ghi `bench-report.json` (Jackson).

### Smoke sandbox (TRUNG THỰC, máy dev)
- FakeHss port 0 ↔ Seeder 1 connection: 200 ULR @500 TPS → **0 loss, 0 timeout, p99 < 250ms** (~0.6s chạy).
- KHÔNG claim NODE_10K — gate đó cần host lab thật (GATE A-FINAL mục 5). Harness sẵn sàng cho lần đo đó.

### dist-tools/package-dist.sh
- Guard JDK zulu-25 / major 25; mvn package; assemble `dist/dra/` (fast-jar quarkus-app hoặc legacy jar fallback); `run.sh` sinh sẵn (ZGC, ExitOnOutOfMemoryError, gclog); html/; configs copy CHỈ KHI đích chưa có (không clobber operator); verify bytecode major 69. `bash -n` pass.

### configs/ seed templates
- `dra-peers.json` — shape contract T1 (peers[] id/host/port/role/transport/advertisedApps/group/weight/maxOutstanding + originHost/realms/watchdog/tw).
- `dra-rules.json` — shape AUTHORITATIVE T3 (version/self/peerGroups/rules với matcher `and/app/avp PREFIX/plmnFrom notIn`, action forward sticky/th, reject 3002) — MVNO 4520402 → mvno-hss-pool WRR 70/30.
- `application.properties.sample` — gộp keys của T4 (bindings/tx) + đề xuất dra.overload.* / dra.th.*.

### docs/runbook.md
Ports, start/stop rsync runtime-only, health peer-truth, hot-reload curl ví dụ, enable/disable peer, bench usage, troubleshooting table (READY-fail / 3002 storm / 3004 / tx leak / server-initiated fail-closed), prove-artifact checklist.

## Gap so GATE A-FINAL (cần host lab)
1. NODE_10K sustained 60s + p99 ≤ 5ms agent-added: cần chạy bench trên lab host với DRA thật (bootstrap xong).
2. Chaos kill-peer/kill-node, TH pcap checklist IR.88, secret scan: phần A-FINAL.
3. Prometheus scrape end-to-end qua ra-prometheus-exporter: wire lúc bootstrap.

## Việc integrator phải wire
- `BenchScenario` trỏ vào DRA thật sau bootstrap; metrics endpoint scrape kiểm chứng tên counter khớp `MetricsNames`.
