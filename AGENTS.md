# AGENTS.md — Elisa Nextgen DRA (tree-local)

## JDK
Java 25 only — mise `zulu-25`. Build: `export JAVA_HOME=$(mise where java@zulu-25)`.
Không bao giờ hạ `maven.compiler.release`.

## Bản sắc dự án
Dự án thuộc họ **Elisa** (Elisa core MVNO). Không gắn thương hiệu khác.

## Kiến trúc module (sở hữu file theo track — MULTI-AGENT LAW)

| Module/đường dẫn | Chủ track | Nội dung |
|------------------|-----------|----------|
| `dra-core/src/main/java/et/elisa/dra/core/{wire,peer,tx/TxState,TxTable,bind/{BindingEntry,BindingStore,BindingKeys,ServerInitiatedResolver,PeerRouteTarget},lb/{LbStrategy,LoadBalancer,PeerHandle},engine/{RoutingContext,RouteDecision,ThMode,StickyBinding,AvpOp},metrics}` | **FROZEN CONTRACTS** | Không ai sửa; cần đổi ⇒ nêu trong TRACK_NOTES để integrator xử lý |
| `dra-core/.../engine/**` (trừ contracts) + `cfg/**` | T3 | Rule engine impl + config model |
| `dra-core/.../tx/**` (trừ contracts) | T2 | TxTable impl |
| `dra-core/.../bind/**` (trừ contracts) | T4 | Binding store impl |
| `dra-core/.../overload/**`, `screen/**` | T5 | Overload/resilience/screening libs |
| `dra-core/.../th/**` | T6 | Topology hiding lib |
| `dra-ra/**` | T1 | Multi-peer RA (copy-adapt từ micro-jainslee vendor-ras/ra-diameter) |
| `dra-app/**` | T2 (sbbs relay), T3 (admin/config REST), T4 (persist/flyway) | Mỗi bên package riêng: `app.sbbs.*`(T2), `app.admin.*`(T3), `app.persist.*`(T4), `app.bootstrap` chỉ integrator sửa |
| `bench/**`, `dist-tools/**`, `docs/runbook.md` | T7 | Harness + packaging + runbook |

- Agent KHÔNG commit git. KHÔNG sửa docs/design/*.md.
- Ghi chú bàn giao vào `TRACK_<X>_NOTES.md` ở root tree.
- Style: Java 25, không comment trừ khi bắt buộc, immutable-first,
  LongAdder cho counter, không blocking IO trên SLEE event thread.
- Test: JUnit 5, mỗi track phải có test cho phần mình, `mvn -pl <module> -am test`
  xanh trước khi báo xong.

## Peer truth law (kế thừa house-style)
LISTEN ≠ ready. Ready = channel up + CEA 2001 + watchdog hợp lệ.
Không route vào peer chưa ready; fail-closed `DIAMETER_UNABLE_TO_DELIVER`
(3002) khi không deliver được — không bao giờ silent-drop.

## Prove the artifact
Green test chưa đóng bài. Deploy thật phải: package dist → rsync runtime →
restart → chứng minh live (dashboard READY + ULR live + jar mtime/PID).

## Resource hygiene (workplace-wide rule, 2026-08-23)

- When done (tests/smoke/dev): stop everything you started — `docker compose down` (keep volumes), kill dev servers/JVMs you spawned. Never leave them running "for later"; RAM is shared across ALL worktrees on this machine.
- Before ending a session verify: `docker ps` shows nothing from this tree; no stray `java`/`node` processes left (`ps -eo pid,rss,args --sort=-rss | head`).
- Long-lived services (EPC / FreeSWITCH / PG / app servers) run only while their session needs them. If the owner asks to keep one up, note which and why in the session handoff.
- DB/app port binds use loopback (`127.0.0.1:`) unless explicitly public; never expose default credentials beyond lab.
