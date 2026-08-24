# sas-diameter-testapp-lab — HSS / 3GPP AAA simulator for the Nextgen DRA security lab

Operator-side **HSS + 3GPP AAA + PCRF (Gx)** Diameter simulator (corsac-diameter),
adapted from the Silent-Auth SAS testapp into the Elisa Nextgen DRA tree. Lab
topology — every attack goes **through the DRA**, never straight at this app:

```
iFinder agents / bench SeederClient ──► Nextgen DRA (:3868, TCP) ──► sas-diameter-testapp-lab (:3869)
                                        dra1.epc.mnc01.…            hss-a.epc.mnc01.mcc452.3gppnetwork.org
```

R&D lab tooling only — never production.

## What changed vs the SAS-tree original

| Area | Original | This lab build |
|------|----------|----------------|
| Package / artifact | `et.restlink.testapp`, `et.restlink:sas-diameter-testapp` | `et.elisa.dra.lab.testapp`, `et.elisa:sas-diameter-testapp-lab` (parent `dra-parent`, corsac via `${corsac.version}`) |
| Origin identity (CER/CEA host) | `hss.restlink.et` / `restlink.et` | default `hss-a.epc.mnc01.mcc452.3gppnetwork.org` / `epc.mnc01.mcc452.3gppnetwork.org` (`--origin-host`/`--origin-realm` to override) |
| Expected peer | hardcoded `sas.restlink.et` | `--peer-host` (default `dra1.epc.mnc01.mcc452.3gppnetwork.org`) + `--peer-realm` (default `epc.mnc01.mcc452.3gppnetwork.org`) |
| Listen port | `--diameter-port` (default 3868) | `--listen-port` (default **3869**); `--diameter-port` still accepted as alias |
| Metrics | none | `GET /api/metrics` (heap/thread/deadlock/counters), LongAdder totals in `MessageLog` |
| Exit oracle | none | shutdown hook writes `--status-file` JSON `{exitReason,timestamp}`; README shows OOM JVM flags |
| Subscriber API | POST only updated pre-existing IMSIs | create-or-update via `hss.upsert` (+ `created` field in response) |
| Seeding | demo subscriber only | built-in 5 lab profiles + `--subscribers-json FILE` (JSONL) |
| Gx bindings | seeded `10.20.30.40 → +251911111111/655010000000001` | unchanged (same demo binding re-seeded on `/api/reset`) |

## What it serves

| App | Command | Code | Answer | Spec |
|-----|---------|------|--------|------|
| S6a  | Update-Location-Request    | 316 | ULA: success + Subscription-Data (`Subscriber-Status`), or error result-code | TS 29.272 §5.2.2.2 |
| S6a  | Authentication-Information | 318 | AIA: fabricated E-UTRAN vectors, or empty on zero-vector state | TS 29.272 §5.3.2 |
| S6a  | Insert-Subscriber-Data     | 319 | IDA ack | TS 29.272 §5.2.2.4 |
| SWx  | Multimedia-Auth            | —   | MAA: EAP-AKA items honouring vector-count state; stamps `lastEapAuthSuccess` | TS 29.273 §6.2.2 |
| SWx  | Server-Assignment          | —   | SAA ack + Non-3GPP-User-Data | TS 29.273 §6.3.2 |
| SWx  | Push-Profile               | —   | PPA ack | TS 29.273 §6.6.2 |
| Gx   | Credit-Control-Request (I) | 272 | CCA `2001` + Subscription-Id for the Framed-IP binding, else `5030`; Subscription-Id rides as unknown AVP 443 (M-bit clear) | TS 29.212 |

Result-code policy (errors ride the base Result-Code — corsac forbids
Experimental-Result on these answers): known+attached+not-barred `2001`;
unknown user `5001`; detached UE `5421`; barred `2001` with
`Subscriber-Status = OPERATOR_DETERMINED_BARRING`; zero vectors `2001` with an
empty set; Gx IP without binding `5030`; handler exception `3002`
DIAMETER_UNABLE_TO_DELIVER (fail-safe, never crashes).

## Build

```bash
export JAVA_HOME=$(mise where java@zulu-25)
mvn -pl lab/sas-diameter-testapp -am package -DskipTests   # fat jar via shade
# -> lab/sas-diameter-testapp/target/sas-diameter-testapp-lab.jar
mvn -pl lab/sas-diameter-testapp test                      # unit tests
```

## Run behind the DRA

```bash
java -XX:+ExitOnOutOfMemoryError \
     -XX:OnOutOfMemoryError="touch /tmp/opencode/testapp-OOM" \
     -XX:+HeapDumpOnOutOfMemoryError \
     -jar lab/sas-diameter-testapp/target/sas-diameter-testapp-lab.jar \
     --listen-port 3869 --web-port 8086 --bind 127.0.0.1 --tcp \
     --origin-host hss-a.epc.mnc01.mcc452.3gppnetwork.org \
     --origin-realm epc.mnc01.mcc452.3gppnetwork.org \
     --peer-host dra1.epc.mnc01.mcc452.3gppnetwork.org \
     --peer-realm epc.mnc01.mcc452.3gppnetwork.org \
     --subscribers-json lab/sas-diameter-testapp/subscribers.jsonl \
     --status-file /tmp/opencode/testapp-exit.json
```

Flags:

| Flag | Default | Purpose |
|------|---------|---------|
| `--listen-port N` | `3869` | Diameter listen port (`--diameter-port` kept as backward-compat alias) |
| `--web-port N` | `8086` | Control web UI port |
| `--bind ADDR` | `127.0.0.1` | Listen address for both planes |
| `--tcp` | off (SCTP) | Use TCP instead of SCTP — the DRA peer config is TCP, so run lab mode with `--tcp` |
| `--origin-host H` | `hss-a.epc.mnc01.mcc452.3gppnetwork.org` | Local Diameter origin-host advertised in CEA |
| `--origin-realm R` | `epc.mnc01.mcc452.3gppnetwork.org` | Local realm |
| `--peer-host H` | `dra1.epc.mnc01.mcc452.3gppnetwork.org` | Remote identity accepted on the link (the DRA's origin-host) |
| `--peer-realm R` | `epc.mnc01.mcc452.3gppnetwork.org` | Remote realm accepted on the link |
| `--subscribers-json FILE` | built-in lab defaults | JSONL seed file applied at startup |
| `--status-file FILE` | unset | Exit-reason JSON written by the shutdown hook |

Behind-DRA notes:

- The DRA side dials out as a CLIENT into this listener (peer entry `hss-a`,
  role=CLIENT, TCP). One inbound association per listen port — do not point the
  old SAS backend and the DRA at the same port at the same time.
- Port layout in the lab: iFinder agents/MME-sim → DRA :3868; DRA → testapp
  :3869 (or any custom port, e.g. `13869`).
- The exit oracle treats a missing status file plus a dead PID as a crash (P1);
  `-XX:OnOutOfMemoryError` drops the marker file so OOM kills are distinguishable.
- `/api/reset` clears the message ring but **keeps** the cumulative counters —
  the LogJudge oracle needs totals that survive ring rollover and resets.

## Control API

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/messages`   | GET  | ring buffer (last 500): time/direction/command/session/result/details |
| `/api/subscriber` | GET  | all subscriber states |
| `/api/subscriber` | POST | create-or-update: `{"identity":"452041110000009","msisdn":"+251700000009","attached":false}` — new numeric IMSIs are created (MSISDN synthesized from IMSI digits when omitted); response carries `created:true/false` |
| `/api/binding`    | GET/POST | Gx IP→{msisdn,imsi} registry; remove with `{"ip":"…","clear":true}` |
| `/api/binding/{ip}` | DELETE | remove one binding |
| `/api/reset`      | POST | clear ring + restore subscriber/binding defaults |
| `/api/health`     | GET  | `{status,diameterListening,lastMessageAgeMillis}` (`lastMessageAgeMillis=-1` before the first message) |
| `/api/metrics`    | GET  | `{heapUsed,heapMax,threadCount,deadlockCount,requestsTotal,answersTotal,errorsTotal,lastMessageAgeMillis}` |

`requestsTotal`/`answersTotal`/`errorsTotal` are `LongAdder` totals fed by the
same logging path the handlers use: every logged request increments
`requestsTotal`, every success answer (`2001`) `answersTotal`, every other
answer `errorsTotal`. Oracle rule of thumb (R3 §5.3): everything the DRA
forwards must show up exactly once as req + once as ans here.

## Seed profiles

Without `--subscribers-json` the app seeds these five profiles at startup:

| IMSI | State | Expected through the DRA |
|------|-------|--------------------------|
| `4520402000000001` | attached, not barred, 1 vector | ULA/AIA success, binding captured |
| `4520402000000002` | barred (OPERATOR_DETERMINED_BARRING) | ULA 2001 + barring through TH |
| `4520402000000003` | detached | ULA/AIA `5421` |
| `4520402000000004` | authVectorsAvailable=0 | AIA/MAA success, empty vector set (fail-closed) |
| `4520409990000001` | off-prefix IMSI | dropped by the DRA's default rule → `3002` from the DRA, request never seen here |

The demo subscriber `655010000000001` / `+251911111111` and the Gx binding
`10.20.30.40 → +251911111111 / 655010000000001` are always present (original
behaviour preserved). Custom seed files use JSONL — one flat JSON object per
line, `#` comments allowed:

```json
{"imsi":"452041110000001","msisdn":"+251700000001","attached":true,"authVectorsAvailable":2}
{"imsi":"452041110000002","barred":true}
```

## Layout

```
src/main/java/et/elisa/dra/lab/testapp/
├── Main.java                 # flags + wiring + seeding + exit-reason hook
├── Config.java               # CLI parsing (record)
├── ExitReason.java           # atomic status-file writer {exitReason,timestamp}
├── Metrics.java              # heap/thread/deadlock snapshot + MessageLog counters
├── MessageLog.java           # last-500 ring + LongAdder totals
├── SubscriberState.java      # per-subscriber mutable lab state
├── SubscriberSeeds.java      # JSONL seed parse/apply + built-in lab profiles
├── HssSimulator.java         # registry keyed by IMSI/MSISDN (upsert = create-or-update)
├── BindingRegistry.java      # Gx IP → {msisdn,imsi} bindings (seeded demo)
├── diameter/
│   ├── HssDiameterServer.java# corsac stack, parameterized origin/peer identity
│   ├── Answers.java          # result codes, random material, logging helpers
│   ├── S6aHandler.java       # ULR/AIR/IDR server listener (TS 29.272)
│   ├── SwxHandler.java       # MAR/SAR/PPR server listener (TS 29.273)
│   └── GxHandler.java        # CCR binding lookups → CCA + Subscription-Id
└── web/
    ├── ControlWebServer.java # JDK HttpServer endpoints incl. /api/metrics
    ├── Pages.java            # single-page UI (vanilla JS, ET-flag accents)
    └── Json.java             # minimal JSON escape/parse (no dependency)
```
