# R1 — iFinder Research Notes (port-feasibility → Diameter S6a/Gx)

Repo: `/tmp/opencode/iFinder` (artifact v4, Usenix Sec'26, NTU). All paths relative to repo root unless absolute.
Date: 2026-08-23. Pure research, no code changes.

---

## 1. Agent architecture ("teamup")

**Terminology**: the string "teamup" does NOT appear anywhere in the repo (grep across tree: 0 hits in
code; only false positive in vendored open5gs docs). The multi-agent design in code = 3 role agents +
1 LLM oracle + a deterministic Python orchestrator:

| Role | File | Purpose |
|------|------|---------|
| **DA = Discovery Agent** | `src/ifinder/agents/discovery.py` | Static analysis of ONE pattern × ONE target codebase; emits iTrue candidates + coverage proof |
| **VA = Vetting Agent** | `src/ifinder/agents/vetting.py` | Per-candidate code-vs-spec cross-check: is the "missing validation" really absent everywhere reachable? FEASIBLE/INFEASIBLE |
| **EA = Exploitation Agent** | `src/ifinder/agents/exploitation.py` | Writes a Go PoC, drives dockerized testbed, refines ≤5 rounds from runtime logs |
| **Oracle (LogJudge)** | `src/ifinder/agents/oracle.py` | Tool-less single-turn LLM judge over runtime logs for non-crash confirmations |
| Orchestrator | `src/ifinder/pipeline.py` | Sequential DA → VA → EA, persists pydantic JSON artifacts |

**Loop mechanics — tool-use, not a hand-rolled prompt loop, not raw REST:**
- All agents run on **claude-agent-sdk** (`claude_agent_sdk.ClaudeSDKClient`) — i.e. the Claude Code /
  Agent SDK headless harness which provides the local tool-use loop (the CLI executes Grep/Glob/Read/Bash
  itself). Dependency: `src/pyproject.toml:10-14` (`claude-agent-sdk>=0.1.0`, pydantic>=2, pyyaml).
- DA/VA = **single-turn** tool loop: `client.run_single_turn()` — `src/ifinder/client.py:92-99`.
- EA = **persistent multi-turn session** (`MultiTurnSession`, client.py:102-127) so the orchestrator can
  re-inject logs each refinement round (exploitation.py:108-145, budget `MAX_REFINE_ITERATIONS=5`,
  `config.py:19`; per-turn cap `DEFAULT_MAX_TURNS=200`, config.py:16).
- Least-privilege toolsets: `READONLY_TOOLS=[Grep,Glob,Read]`, `EXPLOIT_TOOLS=[Read,Write,Edit,Bash,Grep,Glob]`
  (`client.py:31-32`). DA additionally gets Bash (orientation) + 2 MCP report tools (`client.py:36-41`).
- **Structured output** via an in-process SDK MCP server `"ifinder_report"` whose tools `report_candidate` /
  `report_coverage` validate payloads against pydantic contracts and REJECT with a schema error the agent
  must fix (`discovery.py:59-90`; schemas at discovery.py:17-56). VA/EA parse free-text JSON best-effort
  (`util.extract_json`, util.py:12); VA parse-failure defaults to FEASIBLE (recall-biased,
  vetting.py:76-85).

**Model/API/config:**
- Provider = **Anthropic only**. `.env.example` contains exactly one key: `ANTHROPIC_API_KEY`.
- Default model: `claude-opus-4-5-20251101` (`config.py:13`); override with `ifinder run --model`
  (`cli.py:47`). Credentials resolution in scripts: env var, `.env`, or `~/.claude/.credentials.json`
  (`scripts/reproduce_one_candidate_open5gs_5g.sh:95-98`). CLI binary resolved from `IFINDER_CLAUDE_CLI`
  or PATH `claude` (`client.py:73-75`).
- **Local models**: NO built-in OpenAI-compatible / litellm / ollama / vllm support (grep for
  `openai|litellm|ollama|vllm|base_url` in `src/`: zero hits). Workaround (outside repo): the spawned
  `claude` CLI honors standard Anthropic env vars (`ANTHROPIC_BASE_URL`/`ANTHROPIC_AUTH_TOKEN`), so a
  gateway speaking the Anthropic Messages API (LiteLLM anthropic-format proxy, claude-code-router, or a
  vLLM deployment behind an Anthropic-format shim) could front local models — but nothing in iFinder
  guarantees behavior under non-Claude models, and tool-calling fidelity is load-bearing.

---

## 2. Knowledge base (`schema/`)

Two JSON files per protocol under `schema/<proto>/generated/`:

- `message_schemas.normalized.json` — **message dictionary**, no state machines, no byte layouts:
  top keys `generated_at, source_message_dir, source_ie_dir, message_count, messages`. Each message:
  `message_name, original_message_name, direction, mandatory_ies[], ies{name → {requirement:
  Mandatory|Optional|Conditional, multiplicity: Single|List, is_grouped, ie_meta{ie_type_id,...}}, source_file}`.
  PFCP: **23 messages**; GTP-C: **84 messages**.
- `ie_catalog.normalized.json` — flat IE registry: `ie_name, ie_type_id, is_grouped, sub_ies[{name,
  ie_type_id}]` (grouped IE nesting one level explicit; deeper via recursion through catalog),
  plus `source_file`. PFCP: **282 IEs**; GTP-C: **143 IEs**. `ie_type_id` = TLV type (for Diameter this
  slot naturally maps to **AVP code**).

**Provenance**: hand-maintained `raw/` layer — `schema/pfcp/raw/Message/*.json` (per-message IE table
mirroring 3GPP TS 29.244 figure tables; e.g. `raw/Message/AssociationSetupRequest.json`) and
`schema/pfcp/raw/IE/*.json` (minimal `{ie_name, ie_type_id, is_grouped[, sub_ies]}`; example
`raw/IE/ATSSSControlParameters.json`). Same layout for `schema/gtpc/raw/{Message,IE}`
(84 message files; e.g. `raw/Message/CreateSessionRequest.json`). The `normalized` files are produced
offline by a generator **not shipped in `src/`** (only `generated_at` + source-dir provenance fields).
So: authored-from-spec tables, then machine-normalized.

**Loaders** (`config.py`):
- `Paths.schema_dir` = `ROOT/schema/<protocol>/generated` (`config.py:51-53`); protocol switched via
  `Paths.for_protocol(protocol)` (`config.py:67-69`) — fully data-driven, no pfcp/gtpc hardcoding here.
- `load_message_schemas` / `load_ie_catalog` / `load_schema` (`config.py:124-139`) — plain dict loads,
  consumed by `pipeline.build_coverage_map` (`pipeline.py:49-61`, builds the message × IE audit space)
  and EA `_schema_excerpt` (`exploitation.py:211-224`, pulls trigger message + involved IE + sub-IEs).

**To add `schema/diameter/`**: create
`schema/diameter/generated/message_schemas.normalized.json` + `ie_catalog.normalized.json` in exactly
these shapes (JSON; loaders are generic). Message keys MUST equal the names used in
`procedure/diameter/*.json` message_flow steps and by DA `trigger_message` (VA matches
`step.message == candidate.trigger_message`, `vetting.py:100`). Requirement values seen: Mandatory/
Conditional/Optional (loader doesn't enum-check them — free-form strings OK).

---

## 3. Procedures (`procedure/`)

NOT per-message tests and NOT oracles. Each file = **one signaling procedure** = ordered message flow +
dependency closure. Pydantic contract `models.Procedure` / `MessageFlowStep` (`models.py:62-84`):

```json
{
  "procedure_id": "PFCP_Association_Setup",
  "direction": "SMF/PGW <-> UPF/SGW",
  "dependency_procedures": [],
  "message_flow": [
    {"seq": 1, "from": "SMF/PGW", "to": "UPF/SGW",
     "message": "PFCP_Association_Setup_Request", "mandatory_ies": ["NodeID","RecoveryTimeStamp"]}
    // ...
  ]
}
```

Consumers:
- **VA**: `VettingAgent._prerequisite_messages` (`vetting.py:87-121`) finds the procedure owning the
  trigger message, unions (a) all messages of `dependency_procedures` (authored as transitive closure —
  models.py:74-79) with (b) earlier in-procedure messages; VA then greps Request AND Response handlers
  of each prerequisite to see whether the "missing" check lives earlier (`SYSTEM_VETTING`,
  prompts.py:116-131). This is the core false-positive killer.
- **EA**: FEASIBLE decisions carry ordered `prerequisite_messages` the PoC must send first
  (`models.py:160-162`, injected at exploitation time).
- Coverage map comes from schema, not procedures (`pipeline.py:49-61`).

Inventory: PFCP 11 files (`association_setup`, `session_establishment`, `session_modification`,
`session_deletion`, `session_report`, `heartbeat`, `node_report`, `pfd_management`,
`association_update/release`, `session_set_deletion`); GTP-C 28 files (create_session, modify_bearer,
delete_bearer_command, bearer_resource_command, detach, forward_relocation, identification, ...).

**To add `procedure/diameter/`**: one JSON per procedure, same shape. Needed minimum for our lab:
S6a — `s6a_update_location` (ULR/ULA), `s6a_authentication_information` (AIR/AIA),
`s6a_purge_ue` (PUR/PUA), `s6a_insert_subscriber_data` (IDR/IDA), `s6a_cancel_location` (CLR/CLA),
`s6a_notify` (NOR/NOA), `diameter_device_watchdog` (DWR/DWA); Gx — `gx_credit_control_initial/
update/termination` (CCR/CCA type 1/2/3), `gx_re_auth` (RAR/RAA). Dependency closures e.g. attach =
AIR ← ULR ordering, Gx CCR-I after ULA. Names must match `schema/diameter` message keys verbatim.

---

## 4. The six implicit-trust patterns (`pattern/*.json`)

All six are plain JSON `{pattern_id, pattern_name, validation_class: syntactic|semantic|resource,
element, dangerous_operation, missing_validation, security_impact, pattern_description}` —
**protocol-agnostic prose**, reusable for Diameter with zero edits.

- **PA1 — Malformed Field** (syntactic, `pattern/PA1.json`): length/size/count fields inside a message
  element are blindly trusted for memory/string ops without checking against actual payload/buffer size
  → buffer overflow, OOB read/write, null-terminated-string assumption on network bytes, or fatal
  error-path instead of graceful reject. *GTP-C/PFCP exemplar*: OPEN5GS-PFCP-001, over-tokenized SDF
  Filter Flow Description fills `av[]` past its bound in `ogs_ipfw_compile_rule` (stack-buffer-overflow,
  `dataset/open5gs/OPEN5GS-PFCP-001.json`).
- **PA2 — Absent Field** (syntactic, `pattern/PA2.json`): a spec-mandatory IE is dereferenced/accessed
  without presence/multiplicity/value-presence check → NPE, OOB indexing on empty lists. *Exemplar*:
  FREE5GC-PFCP-004 `(*RecoveryTimeStamp).UnmarshalBinary`.
- **PB1 — Invalid Value** (semantic, `pattern/PB1.json`): syntactically valid element with out-of-range
  semantic value (unsupported/spare enum, ID outside range, conflicting flags) used directly as index/
  lookup/control-flow → assertion, panic, OOB. *Exemplar*: OPEN5GS-PFCP-017 `upf_sess_set_ue_ip:401`.
- **PB2 — Invalid State** (semantic, `pattern/PB2.json`): incoming message (version/type/stage) is
  dispatched into state-dependent handlers without validating the current FSM state allows it
  (assumes "session exists", "transaction initialized") → crash/desync. *Exemplar*: OPEN5GS-PFCP-014
  `ogs_pfcp_xact_find_by_xid` (unknown transaction id). **Most relevant to a DRA** (peer-state/Hop-by-Hop
  ID handling).
- **PB3 — Invalid Reference** (semantic, `pattern/PB3.json`): object identifier from a message used as a
  key into an internal table, or a stale pointer held across async callbacks, without existence/scope/
  liveness validation → UAF, wrong-entry operations, NPE. *Exemplar*: OPEN5GS-PFCP-015
  `smf_sess_remove` (CWE-416). Maps directly to DRA session-binding/routing-context tables.
- **PC1 — Resource Exhaustion** (resource, `pattern/PC1.json`): bounded pool (sessions etc.) exhausted by
  resource-creating requests; limit-hit path crashes (assert on count ≥ MAX, deref failed alloc) instead
  of rejecting with proper error cause. *Exemplar*: OPEN5GS-PFCP-021 `upf_sess_add`.

Ground truth dataset: 22 curated PFCP vulns mapped to patterns — `dataset/README.md:33-36`
(PA1×11, PA2×1, PB1×6, PB2×1, PB3×1, PC1×2).

---

## 5. Scope/target declaration & black-box question

Scope file shape (`scope/pfcp/scope_open5gs_275.json`, parsed into `models.ScopeTarget` models.py:87-96):

```json
{"scope_name": "open5gs-pfcp-275",
 "targets": [{"name": "open5gs_275", "version": "2.7.5", "protocol": "pfcp",
              "target_codebase": "target/open5gs_code/open5gs_275",
              "scan_dirs": ["lib/pfcp/","lib/core/","lib/ipfw/","src/smf/","src/upf/"]}]}
```

- `target_codebase` resolves to an on-disk source tree (`config.target_path`, config.py:71-74); DA's
  working directory IS that tree (`discovery.py:105`), and DA works "entirely through the read-only tools
  Grep, Glob, and Read" (`prompts.system_discovery`, prompts.py:52). VA likewise code-only
  (prompts.py:119). ⇒ **DA+VA require source code. Black-box-only scanning is impossible for those stages**
  (no network-analysis mode exists).
- Language-agnostic though: "Do NOT assume a fixed language or construct … ground the pattern's abstract
  dangerous operation into whatever the target language actually uses" (prompts.py:52-54) — **Java OK**.
- **EA is network-facing**: destination host/port come from parsing the testbed docker-compose YAML
  (`testbed.discover_nf_endpoints`, testbed.py:45-74 — reads per-service `ipv4_address`), port from
  `config.protocol_port` (config.py:24-30). So for OUR lab: keep our Nextgen-DRA source visible to DA/VA
  (it's ours — trivially satisfiable), and give EA a compose that runs `Nextgen DRA + sas-diameter-testapp`
  with static IPv4s.
- Hardcoded bits needing extension for Diameter: `NetworkFunction` enum (models.py:28-35: UPF/SMF/SGW-C/
  MME/PGW-C only), `PROTOCOL_NFS`/`PROTOCOL_LABELS`/`PROTOCOL_POC` (prompts.py:18-29), DA candidate-tool
  enum (discovery.py:39), `_nf_labels` compose-name matcher (testbed.py:23-42), `protocol_port`
  (config.py:28-30). All small, localized edits; pipeline/loader layers are already protocol-generic.

## 6. Live verification — who talks to the testbed, and how

Correction to the premise: **VA never touches the testbed** (pure static cross-check). Live verification
is EA + orchestrator:

- **Sender is not scapy and not a fixed tool** — it is a **Go PoC the EA writes itself**
  (`outputs/exploitation_results/<target>/<cand>/poc/main.go`, exploitation.py:50-52) using
  `github.com/wmnsk/go-pfcp` (PFCP/N4) or `github.com/wmnsk/go-gtp (gtpv2)` (GTP-C/S11) —
  `PROTOCOL_POC`, prompts.py:26-29. The orchestrator merely compiles/runs it: `go run <poc>` +
  collects target-NF container logs since start (`TestbedDriver.run_poc`, testbed.py:226-246);
  lifecycle via `docker compose up/down/stop/start/logs` subprocesses (`testbed.py:149-212`);
  NF restart between attempts + teardown-crash attribution via exit codes 132/134/135/136/139
  (testbed.py:93-98,181-212).
- Two-layer oracle: (1) crash-regex `detect_trigger` (markers SIGSEGV/panic/ogs_assert/…,
  testbed.py:77-91) and (2) LLM `LogJudge` for non-crash audit/state/behavior evidence requiring a
  verbatim quoted log line, else downgrade to unconfirmed (`oracle.py:50-69`, SYSTEM_ORACLE prompts.py:242-272).
- **Swapping in a Diameter-over-TCP client: YES, cheap.** The protocol lives entirely in (a) the
  generated PoC (agent-written — prompt tells it which library/iface/port; for Diameter point it at a
  Go Diameter stack, e.g. `fiorix/go-diameter`, over TCP 3868, S6a/Gx apps), (b) `PROTOCOL_POC`
  (prompts.py:26-29), (c) `protocol_port` (config.py:28-30 — add `"diameter": 3868`), (d) NF label
  matching in `testbed._nf_labels`. No scapy-style fixed sender to replace. Note UDP-vs-TCP: nothing in
  the orchestrator assumes UDP except the comment; `run_poc` is transport-blind.

## 7. Reproduction scripts

`scripts/reproduce_one_candidate_<target>.sh` (8 targets; e.g.
`reproduce_one_candidate_open5gs_5g.sh`):
1. Preflight: requires `docker`(+compose v2), `go`, `python3`, `claude`, `ifinder` on PATH;
   credentials from `$ANTHROPIC_API_KEY` | repo `.env` | `~/.claude/.credentials.json` (lines 91-98).
2. Pin analyzed source version: git checkout vX.Y.Z or verify from meson.build (tarball mode, 101-128).
3. Ensure testbed images; auto-build via make if missing (130-160).
4. Stage discovery: `ifinder run --scope ../scope/pfcp/scope_open5gs_275.json --patterns PA1
   --target open5gs_275 --stage discovery` (166).
5. VA loop: slice DA artifact to candidate #k, run `--stage vetting`, accept first FEASIBLE, ≤5 tries
   (176-240).
6. EA: `--stage exploitation --candidate <id> --compose-file ... [--env-file ...]` (251-254).
7. Gate: artifacts must exist; CONFIRMED **or** UNCONFIRMED both pass the smoke (goal = pipeline wired,
   259-289). Idempotent cleanup trap restores backups + `compose down -v` (67-81).

Env vars needed: `ANTHROPIC_API_KEY` (or claude login), optional `IFINDER_CLAUDE_CLI`;
`DOCKER_HOST_IP` inside testbed `.env` (open5gs only). Runtime ≈10 min per script.
Full-eval scripts (`reproduce_full_pfcp_open5gs_5g.sh`, `reproduce_full_gtpc_oai_epc.sh`): all 6
patterns × every FEASIBLE candidate, 1–4 h, real token cost, per-pattern
candidates/feasible/confirmed summary printed (script header lines 3-15).

**Output/report format**: pydantic-pretty-JSON artifacts under `outputs/discovery_results/<target>/<PID>.json`
(candidates + coverage_report), `outputs/vetting_results/<target>/<PID>.json` (verdicts + statistics),
`outputs/exploitation_results/<target>/<CANDIDATE>.json` (verdict, attempts, trigger_evidence w/ log
snippet, poc_path, refinement trace) — `config.Paths.*_out` config.py:76-83; plus `.raw.txt` dumps of
agent text (discovery.py:136-143). Sample ground-truth record format: `dataset/open5gs/OPEN5GS-PFCP-001.json`.

## 8. Diameter-port effort estimate

**Reusable untouched (~85% of the framework)**: `pipeline.py`, `client.py`, `util.py`, whole
orchestrator; `pattern/*.json` (prose, protocol-free); `models.py` artifact contracts; testbed lifecycle
+ crash-regex + LogJudge oracle; scripts (swap scope/compose args); DA/VA prompts modulo tiny
protocol-label tables.

**New work items:**

| Item | Files | Est. |
|------|-------|------|
| `schema/diameter/` message+AVP KB (S6a ~14 msgs, Gx ~6 msgs, AVP catalog incl. grouped: Subscription-Data, Supported-Features, MSISDN, Experimental-Result-Code…) | 2 generated JSON (+ optional raw/) | 1.5–2 d (author + cross-validate names vs 3GPP TS 29.272/29.214; can bootstrap AVP codes from freeDiameter/go-diameter dicts) |
| `procedure/diameter/` (~10 files: ULR, AIR, PUR, IDR, CLR, NOR, DWA, CCR-I/U/T, RAR + dep closures) | 10 JSON | 0.5 d |
| Code touch-points: `models.NetworkFunction` (add HSS/PCRF/MME/DRA), `prompts.{PROTOCOL_LABELS,NFS,POC}`, `config.protocol_port`(3868/TCP), `testbed._nf_labels` diameter branch, DA tool enum | 5 files, ~60 LOC | 0.5–1 d incl. tests |
| Testbed compose: Nextgen DRA + sas-diameter-testapp (HSS/PCRF sim) with pinned IPv4s, restartable services, greppable logs | our infra | 0.5–1 d (we own both sides already) |
| Java-oracle tuning: extend `_CRASH_MARKERS` w/ JVM signals (hs_err, OOM, uncaught exception, our fail-closed DIAMETER_UNABLE_TO_DELIVER 3002 audit lines); LogJudge prompt examples de-SEID'd | 1–2 spots | 0.5 d |
| E2E shakedown: DA recall on Java tree, VA prerequisite quality, EA Diameter PoC convergence | — | 2–4 d iteration |

**Total: ≈ 1.5–2 engineer-weeks** for full DA→VA→EA on S6a/Gx; **minimum viable (DA+VA only,
`--no-exploit`, zero Docker) ≈ 3–4 days** — this path needs only scope+schema+procedure and gives static
findings immediately (documented as identical-for-every-core in `src/README.md:20-27`).

**Biggest risks:**
1. **Confirmation semantics for a Java DRA** — the paper's EA oracle is crash-centric (SIGSEGV/panic/
   assert; dataset is 100 % DoS-class). A correct-by-construction DRA fails closed (house rule: 3002,
   no silent drop), so most real findings will be *behavioral* (mis-routing, sticky-binding leaks,
   overload bypass) confirmed only via LogJudge over logs — strictly weaker evidence; needs deliberate
   log instrumentation in DRA + testapp, and possibly new patterns beyond the 6 (e.g. trust-boundary
   origin-State-ID / Realm routing trust).
2. **KB authoring consistency** — three-way name agreement required: `schema.messages` keys ↔
   `procedure.message_flow[].message` ↔ DA `trigger_message`; AVP-code mistakes poison EA silently.
3. **Provider lock-in** — hard dependency on claude CLI + Anthropic key; local models only via external
   Anthropic-format gateway (§1).
4. Token cost: full eval burns real tokens (paper scripts warn 1–4 h real cost per target).

## 9. License (PolyForm Noncommercial 1.0.0)

Covered: everything authored by NTU — `src/`, `scripts/`, `pattern/`, `procedure/`, `schema/`, `scope/`,
`dataset/` (`README.md:52-57`, `LICENSE` PolyForm NC 1.0.0). `target/` + `testbed/` stay upstream-licensed
(`README.md:59-63`).

- Permitted purposes: "Any noncommercial purpose"; **Personal Uses** explicitly include
  "research, experiment, and testing for the benefit of public knowledge, personal study…" (`LICENSE:56-66`);
  noncommercial orgs listed separately.
- **Internal R&D lab evaluation**: running it privately, studying it, publishing nothing — squarely
  "experiment/testing … personal study"; acceptable reading for a pure research exercise.
- **Gray zone / risk**: our context is a vendor security team evaluating tooling to harden a commercial
  product (Elisa Nextgen DRA). PolyForm conditions *distribution*, not private execution; "commercial
  purpose" is judged by anticipated commercial application. Using the *tool* internally without
  distributing it is defensible; but (a) shipping modified iFinder code inside our repos/products is
  NOT allowed, (b) folding its artifacts (schema/procedure JSONs, dataset) into a commercial test
  harness is a commercial-purpose stretch, (c) results/findings themselves are facts — unrestricted.
- **Recommendation**: treat iFinder as an internal evaluation oracle only; do NOT copy its
  code/artifacts into Elisa trees. If we want durable assets, re-author schema/procedure content
  ourselves from 3GPP specs (ideas/format ≠ copyright) or email ziyu.lin@ntu.edu.sg for permission
  (contact given README.md:57). Also note pre-push/commit rules of our trees: no iFinder-derived files
  should enter commits.

## Verdict

**KHẢ THI (feasible)** với kế hoạch 2 pha: Pha A (3–4 ngày) DA+VA tĩnh trên source Java của Nextgen DRA —
chỉ cần `schema/diameter` + `procedure/diameter` + `scope/sdra.json`, không Docker; Pha B (+1–1.5 tuần)
EA động qua compose DRA↔sas-diameter-testapp, thay PoC lib sang go-diameter (TCP/3868) và chuyển trọng tâm
oracle sang log-judge vì DRA Java ít khi crash. Framework tái dùng nguyên xi ~85%; rủi ro số 1 là định nghĩa
lại "confirmation" cho lỗi hành-vi (routing/trust) thay vì crash, và tuân thủ PolyForm NC (không đưa code/artifact vào repo thương mại).
