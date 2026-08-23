# Nextgen DRA — Runbook vận hành

> Elisa Nextgen DRA — Diameter Routing Agent (micro-jainslee + corsac transport).
> Java 25 only (mise zulu-25). Dist layout: `dist/dra/`.

## 1. Ports & thành phần

| Port | Mục đích |
|------|----------|
| 3868 | Diameter (TCP/SCTP) — peers nối vào đây hoặc DRA dial ra |
| 8080 | Admin REST + HTMX dashboard (`/api/*`, `/`) |
| 9090 | Prometheus metrics (`ra-prometheus-exporter`, prefix `dra_`) |
| 5432 | PostgreSQL (bindings durable, route_config SoT, audit_log) |

## 2. Start / Stop

```bash
# package
dist-tools/package-dist.sh          # → dist/dra/

# deploy (rsync runtime bits, KHÔNG clobber configs/ của operator)
rsync -a --exclude 'configs/' --exclude 'logs/' dist/dra/ dra-host:/opt/dra/
ssh dra-host 'sudo systemctl restart dra'   # hoặc: /opt/dra/run.sh

# verify live (prove-artifact)
curl -s http://dra-host:8080/api/peers        # peer READY truth
ls -la dra-host:/opt/dra/quarkus-run.jar      # jar mtime khớp bản mới
pgrep -af quarkus-run                          # PID + classpath
```

Stop an toàn: `kill -TERM` (drain in-flight tx), KHÔNG `kill -9` trừ treo.

## 3. Health & peer truth

- `GET /api/peers` — trạng thái từng peer: `channelUp`, `ceaOk`, `watchdogValid`,
  `outstanding`, advertised apps. **READY = cả ba true** (LISTEN ≠ ready).
- Peer không READY → xem §6 troubleshooting.
- `GET /api/telemetry` — snapshot counters: `dra_tx_total{peer,app,cmd}`,
  answer classes 2xxx/3xxx/4xxx/5xxx, `dra_failover_total`, `dra_throttled_total`,
  `dra_tx_active` (phải về 0 sau load), `dra_binding_size`.

## 4. Config hot-reload

SoT routes/rules/peers = PG (`route_config` versioned); file JSON chỉ seed.

```bash
# xem rules hiện tại
curl -s http://dra-host:8080/api/rules | jq .version

# apply version mới (validate-before-apply; sai → HTTP 400 + giữ last-good)
curl -s -X PUT http://dra-host:8080/api/rules \
  -H 'Content-Type: application/json' \
  -d @configs/dra-rules.json.new

# enable/disable peer (drain)
curl -s -X POST http://dra-host:8080/api/peers/hss-a/disable
curl -s -X POST http://dra-host:8080/api/peers/hss-a/enable
```

In-flight tx tiếp tục dùng rule cũ đến khi hoàn tất (atomic swap).

## 5. Bench (kiểm chứng hiệu năng)

```bash
# fake HSS nhận tải
java -cp bench/target/classes:dra-core/target/classes \
  et.elisa.dra.bench.FakeHssServer 3868   # (xem main() args)

# seeder bắn ULR
java -cp bench/target/classes:dra-core/target/classes:$(find ~/.m2 -name 'jackson-databind-2.17.2.jar') \
  et.elisa.dra.bench.BenchScenario --host 127.0.0.1 --port 3868 \
  --tps 10000 --duration-s 60 --connections 4 --imsi-prefix 4520402
```

Gate NODE_10K: 10k TPS sustained 60s, p99 agent-added ≤ 5 ms, 0 OOM,
`dra_tx_active` về 0 sạch. Chạy trên host lab thật trước khi claim.

## 6. Troubleshooting

| Triệu chứng | Nguyên nhân khả dĩ | Xử lý |
|-------------|--------------------|-------|
| Peer không bao giờ READY | CEA không 2001 / watchdog DWR-DWA fail / channel down | kiểm tra connectivity 3868, advertised apps hai bên, watchdog interval |
| Storm 3002 UNABLE_TO_DELIVER | rule nomatch (`dra_route_nomatch_total` tăng) / group rỗng | xem `/api/rules`, thêm default rule hoặc sửa group refs |
| Storm 3004 TOO_BUSY | overload token bucket đầy | tăng rate config, kiểm tra OC-OLR từ server (reduction%) |
| `dra_tx_active` không về 0 | leak hbh entry / answer không correlate | kiểm tra hbh rewrite hai chiều, bật log WARN unknown-tx |
| Server-initiated IDR bị 3002 | không có binding & không Dest-Host (fail-closed đúng thiết kế) | xác nhận ULR đầu tiên đã capture IMSI binding |
| Binding mất sau restart | write-behind chưa flush / PG down | kiểm tra `audit_log` + batch writer, giảm writebehind.interval |

## 7. Security biên (DEA mode)

- Allowlist app-ID/cmd/IP per peering trong screening config; anti-spoof Origin-Realm.
- Topology hiding theo group (`dra.th.groups`); pseudo host deterministic theo IMSI.
- TLS: SslHandler Netty cho TCP NNI (fork corsac); SCTP dùng IPsec TS 33.210.
  Chi tiết decision record: `TRACK_T6_NOTES.md`.

## 8. Prove-artifact checklist (bắt buộc trước khi báo "deployed")

1. `dist-tools/package-dist.sh` xanh, bytecode major 69.
2. rsync runtime bits (không clobber `configs/`).
3. Restart + readiness: `/api/peers` trả READY cho peer lab.
4. 1 ULR test đi qua end-to-end (bench seeder 1 msg hoặc client thật).
5. Bằng chứng sống: dashboard READY + jar mtime/PID classpath khớp bản mới.
