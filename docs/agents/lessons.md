# Lessons — Nextgen-DRA

Shared law: workspace `docs/agents/lessons.md` + skill `digicom-et-host`. This file holds
DRA-specific + synced cross-product lessons.

## Synced 2026-08-23 — from gmlc-microjainslee (Monitor Hub / KPI / fast-jar deploy)

- **Quarkus fast-jar deploy = rsync `quarkus/` + `lib/` TOGETHER with the app jar — never jar-only.** Quarkus also loads app classes from `quarkus/transformed-bytecode.jar` + `generated-bytecode.jar`; a stale `quarkus/` shadows the new root jar (old code runs despite fresh mtime) or an H2-era augment over a PG URL crash-loops with `Driver does not support jdbc:postgresql`. Prove boot from **log lines**, not mtime. (GMLC incident 2026-08-23.)
- **ServiceLoader cannot see `META-INF/services` inside the ROOT app jar** at boot under fast-jar layering — only packs in `lib/main` are found. App-owned SPI providers (e.g. `RaAdminDashboardContributor`) must be registered explicitly by merging several ClassLoaders, deduped by name. Reference: gmlc `AdminHttpHandler.buildHub()`.
- **Monitor Hub routing law** (when DRA adopts jainslee-monitor): route all hub paths (`isMonitorHubPath`); **`/metrics` is NOT a hub path** — serve `port.scrape()` in-app; anonymous = inert static extensions only; hub Overview polls `/admin/monitor-feed` every 1s.
- **Protocol-KPI pattern** (reference gmlc `GmlcKpi` + `GmlcKpiContributor`): LongAdder map as truth + passive Micrometer mirrors (`gmlc_kpi_*` on `/metrics`) + own hub tab polling `/api/ra/{ra}/status.html`. Direct analog for DRA: Diameter counters per op (CER/DWR/CEA/DWA), request/response success/fail, per-peer and per-realm — same shape.
- Readiness probe: admin status endpoints are auth-gated — **401 anonymous = HTTP plane up only**; ready = 200 WITH the admin key header. Never probe unauthenticated and claim ready.
