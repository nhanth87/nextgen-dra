# Elisa Nextgen DRA — Diameter Routing Agent Steroid on micro-jainslee

[🇻🇳 Tiếng Việt](README.vi.md)

> 🏆 **First open-source Diameter Routing Agent to pass an iFinder security
> audit** — DA discovery + VA adversarial vetting (pattern PA1), full coverage
> 10/10 messages · 56/56 IEs, 1 latent defect found &amp; documented.
> Procedure: [lab/TESTPLAN_IFINDER_DRA_EN.md](lab/TESTPLAN_IFINDER_DRA_EN.md) ·
> Report: [`iFinderResult/`](iFinderResult/SUMMARY.txt)

A high-performance **Diameter Routing Agent** built the way a real network
function should be: as a **micro-jainslee** application with a dedicated
multi-peer Diameter RA on the corsac transport, doing true **N-N relay**
(N clients ↔ N server pools) with a full routing rule engine — realm/host
routing, IMSI-prefix MVNO routing, weighted load balancing, sticky bindings,
failover, DOIC overload control and topology hiding.

In the network topology the DRA is the **Diameter front door of the MVNO
core**: MME/AAA clients dial into a single agent edge, and the agent fans each
request out to the right HSS/PCRF pool behind it — clients never learn real
server identities, servers never see raw client hosts. The whole journey fits
in two pictures:

- [`assets/callflows/dra-ulr-ula-relay.svg`](assets/callflows/dra-ulr-ula-relay.svg)
  — one S6a ULR end-to-end: screen → overload gate → rule match → weighted
  sticky peer pick → hbh rewrite → relayed ULA back on the ingress link.
- [`assets/callflows/dra-internals.svg`](assets/callflows/dra-internals.svg)
  — the same message seen from inside: corsac decode → RA ingress event →
  micro-jainslee SBB dispatch → RelayCore guard chain → tx table → egress.

Status: **IMPLEMENTED — lab-ready** (278 tests green; N-N relay proven over
real TCP **and SCTP** sockets; strict micro-jainslee wiring through
`MicroSleeContainer`). Production capacity numbers are not claimed yet.

---

## Build &amp; quick start

```bash
export JAVA_HOME=$(mise where java@zulu-25)   # JDK 25 only
mvn clean test                                # full suite (278 tests)
dist-tools/package-dist.sh                    # -> dist/dra (run.sh + configs + html)
```

Database: embedded H2 file demo by default (`./data/dra`, zero setup); for
production export `DRA_DB_KIND=postgresql` plus `DRA_DB_URL/DRA_DB_USER/DRA_DB_PASSWORD`.

### Minimal live lab (SCTP, ~2 minutes)

```bash
# 1) HSS/S6a simulator on :3869 (SCTP is the default transport)
java -jar lab/sas-diameter-testapp/target/sas-diameter-testapp-lab.jar \
     --listen-port 3869 --web-port 8086 &

# 2) DRA from the packaged dist (peers config: configs/dra-peers.json)
cd dist/lab-run && ./run.sh &                 # Diameter :3868, admin :8080
sleep 15 && cd ../..

# 3) Load routing rules (SoT is the REST API; JSON only seeds it)
curl -s -X PUT http://127.0.0.1:8080/api/rules -H 'Content-Type: application/json' \
     -d @dist/lab-run/configs/dra-rules-lab.json

# 4) Push S6a ULR traffic through the agent
java -cp bench/target/classes:elisa-dra/target/classes \
     et.elisa.dra.bench.SctpSeederClient --host 127.0.0.1 --port 3868 \
     --src-port 38680 --count 4 --imsi-prefix 45204020 \
     --dest-host hss-a.epc.mnc01.mcc452.3gppnetwork.org
# expect: 4/4 answered, result codes 2001 / 2001(barred) / 5421(detached) / 2001

curl -s http://127.0.0.1:8080/api/peers   # peer truth: channelUp+ceaOk+watchdogValid
curl -s http://127.0.0.1:8086/api/messages # simulator-side ground truth
```

---

## Modules


| Module                     | Content                                                                                                                                                 |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `elisa-dra`                | The single Quarkus + micro-jainslee app: routing rule engine, config model, tx table, sticky binding store, LB strategies (`et.elisa.dra.core.*`), multi-peer Diameter RA on the corsac stack with readiness truth (channel up + CEA + watchdog) and any-command decode (`app.ra`), relay SBBs + micro-jainslee wiring (`app.sbbs`, `app.bootstrap`), admin REST + HTMX dashboard (:8080) (`app.admin`), durable binding persistence H2/PostgreSQL via Flyway (`app.persist`) |
| `bench`                    | Wire codecs, fake HSS/MME, TCP seeder + SCTP seeder clients for load &amp; smoke                                                                        |
| `lab/sas-diameter-testapp` | Standalone HSS/S6a(+SWx/Gx) simulator used as the relay target                                                                                          |


## Call flow

### ULR/ULA through the agent (the happy path)

<p align="center">

  <img src="assets/callflows/dra-ulr-ula-relay.svg" alt="S6a ULR/ULA relay call flow through the DRA" width="860"/>

</p>

Step by step:

1. Ingress `ULR` (app 16777251, IMSI `4520402…`) lands on the `mme-acc` server link (:3868, SCTP or TCP).
2. Guard chain: **screening** (allowlist app/cmd/realm-spoof/IP-CIDR) → **overload gate** (global+peer token buckets, DRMP-aware, OC-OLR reduction) → **loop check** (self in Route-Record ⇒ 3005).
3. **Rule engine** matches `s6a-mvno-hss` → forward group `mvno-hss-pool` with `WEIGHTED_RR 70/30`, sticky IMSI binding (TTL 24 h), failover ≤ 1 retry on retryable commands (ULR/AIR/PUR/NOR).
4. `TxTable` rewrites `hbhIn→hbhOut` (e2e preserved); the request leaves on a CLIENT link to `hss-a` (:3869).
5. The simulator answers `ULA` — `2001` ok/barred, `5421` detached, `5001` unknown user.
6. `RelayCore.onAnswer` correlates `hbhOut→hbhIn`, restores topology-hiding, captures the IMSI→peer binding, and replies **on the original ingress link**.
7. Deadline sweep (5 s) drives retry/failover; exhausted ⇒ fail-closed `DIAMETER_UNABLE_TO_DELIVER (3002)` — never a silent drop.

### Internal request path

<p align="center">

  <img src="assets/callflows/dra-internals.svg" alt="DRA internal request path across RA plane, micro-jainslee core and state modules" width="860"/>

</p>

`corsac decode (any-command)` → `IngressRequest(peerId, DiaMsg)` → `DraRelaySbb` (micro-jainslee container routing) → `RelayCore` guard chain → rule decision (`Forward | Redirect | Reject`) → sticky-aware LB pick → topology-hiding rewrite → tx row + hbh re-alloc → egress. Server-initiated requests (IDR/CLR…) reverse-resolve IMSI→peer from the binding store; no binding and no Dest-Host ⇒ fail-closed 3002. Unknown-hbh answers are counted drops (injection guard).

### Peer readiness truth

`LISTEN ≠ ready`. A peer is routable only when `channelUp ∧ ceaOk(2001) ∧ watchdogValid` — polled from the corsac stack every 100 ms with an on-demand re-check before any send fails (`refreshRegistryBeforeFail`).

---

## Admin &amp; observability


| Endpoint                 | Purpose                                                            |
| ------------------------ | ------------------------------------------------------------------ |
| `GET /api/peers`         | per-peer readiness truth + advertised apps                         |
| `GET/PUT /api/rules`     | validated hot-reload of the routing rule set (last-good rollback)  |
| `GET /api/telemetry`     | counters: tx totals, answer classes, throttle/failover/drop gauges |
| `GET :8086/api/messages` | simulator ring-buffer log (req/ans ground truth)                   |


Config seeds live in `configs/`; operator-owned copies under `dist/dra/configs`
are never clobbered by packaging.

## Position in the Elisa ecosystem

- **elisa/** (IMS core, Cx over ra-diameter) sits behind this DRA.
- **epc/** (full-MVNO core in the sip-freeswitch tree): NNI interface contract
documented in `epc/docs/nni/host-mno-interface-contract.md`.
- Built on **micro-jainslee** (GPLv3 container family) and the
corsac-diameter transport (AGPLv3 fork).

## Licensing

Elisa Nextgen DRA is dual-licensed — pick the model that fits your use:

1. **Open source**: application modules (`elisa-dra`,
 `bench`, lab testapp) are **GPLv3** (see `LICENSE`); the micro-jainslee
 container family is GPLv3 and the bundled corsac-diameter transport fork is
 **AGPLv3**. Running the agent unmodified inside your own network triggers no
 copyleft obligations; distributing appliances or closed derivatives does.
2. **Commercial license** from Tran Nhan for operators and
 vendors who need redistribution without copyleft obligations, SLA-backed
 support, or closed-source derivatives — contact `nhanth87@gmail.com`.

Copyright © 2026 Tran Nhan (nhanth87). All rights reserved where applicable.