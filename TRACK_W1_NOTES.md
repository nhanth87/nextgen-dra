# TRACK_W1_NOTES — lab/sas-diameter-testapp (adapted sas-diameter-testapp)

Ngày: 2026-08-23 · Agent: W1 · Phạm vi sở hữu: `lab/sas-diameter-testapp/**` (+ 1 dòng `<module>` trong pom.xml parent)

## Đã làm

1. Copy `sas-diameter-testapp` từ silent-auth tree → `lab/sas-diameter-testapp/`, refactor package
   `et.restlink.testapp` -> `et.elisa.dra.lab.testapp`; pom con: parent dra-parent,
   artifactId `sas-diameter-testapp-lab`, corsac qua `${corsac.version}`, junit-jupiter test, giữ shade fat-jar
   (`target/sas-diameter-testapp-lab.jar`, Main-Class `et.elisa.dra.lab.testapp.Main`).
2. Peer config lab: `--listen-port` (default 3869, alias `--diameter-port`),
   `--peer-host` (default `dra1.epc.mnc01.mcc452.3gppnetwork.org`), `--peer-realm`
   (default `epc.mnc01.mcc452.3gppnetwork.org`), `--tcp`;
   origin-host mặc định `hss-a.epc.mnc01.mcc452.3gppnetwork.org`
   (`--origin-host`/`--origin-realm` override được). Không flag = backward-compat CLI cũ.
3. Oracle instrumentation: `GET /api/metrics` {heapUsed, heapMax, threadCount, deadlockCount,
   requestsTotal, answersTotal, errorsTotal (LongAdder), lastMessageAgeMillis};
   MessageLog giữ cumulative counters qua ring-rollover và `/api/reset`;
   `/api/health` thêm lastMessageAgeMillis (-1 trước message đầu);
   shutdown hook ghi `--status-file` JSON {exitReason:"shutdown", timestamp}.
4. Subscriber: POST /api/subscriber create-or-update qua hss.upsert (MSISDN tự sinh từ IMSI khi thiếu,
   response có field created); seed 5 profile lab lúc start (452040200000000{1..4} + 4520409990000001)
   hoặc qua `--subscribers-json` (JSONL, mẫu kèm `subscribers.jsonl`). Demo subscriber +
   Gx binding 10.20.30.40 giữ nguyên như bản gốc.

## Verify đã chạy (JDK 25 mise zulu-25)

- `mvn -q -pl lab/sas-diameter-testapp -am package -DskipTests` — XANH
- `mvn -q -pl lab/sas-diameter-testapp test` — XANH, 29 tests / 0 fail
- Smoke thật: jar boot TCP :13869, origin/peer đúng identity, seeds=5,
  metrics/health/subscriber create-or-update/binding OK, SIGTERM ->
  status-file `{exitReason:"shutdown", timestamp}` rồi thoát sạch.

## Ghi chú cho integrator / track khác

- DRA side phải dial-out CLIENT vào testapp với Origin-Host
  `dra1.epc.mnc01.mcc452.3gppnetwork.org` (configs/dra-peers.json hiện ghi
  `dra1.elisa.lab` + role SERVER cho hss-a — cần đổi role=CLIENT host/port testapp khi wiring lab;
  không sửa trong phạm vi W1).
- Testapp chỉ 1 association/listen port (corsac) — đừng trỏ thêm client nào cùng port.
- Counters oracle: mọi request DRA forward phải thấy req+ans đúng 1 lần ở testapp;
  errorsTotal đếm mọi answer != 2001.
