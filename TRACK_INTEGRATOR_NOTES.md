# TRACK_INTEGRATOR_NOTES - Tong hop merge P0-P6 (T1-T7)

Ngay: 2026-08-23. Thuc hien theo docs/design/04-plan.md: cac track chay song song
trong sandbox rieng /tmp/opencode/dra-tN, merge ve main tree boi integrator.

## Ket qua build hop nhat

mvn clean test -> BUILD SUCCESS, Java 25 zulu-25, 4 module:

- dra-core: 163 tests xanh (engine/cfg/lb/tx/bind/overload/screen/th/common)
- dra-ra:    39 tests xanh (PeerRegistry, CorsacPeerFabric, SimulatedPeerFabric, wire codec)
- dra-app:   44 tests xanh (sbbs relay + admin REST + persist)
- bench:      4 tests xanh (DiaWire roundtrip + smoke 200 ULR zero-loss)

Frozen contracts nguyen ven: diff-check wire/, peer/, tx contracts, bind contracts,
lb contracts, engine contracts, metrics/, common seams truoc/sau merge - khong doi.

## Trang thai tung track

| Track | Pham vi | Trang thai |
|-------|---------|------------|
| T1 | module dra-ra moi: PeerRegistry N-peer readiness truth (channel+CEA+watchdog), DraRaPort impls (corsac + simulated), any-command relay khong drop, event mang ingressPeerId | XONG |
| T2 | DefaultTxTable/HbhAllocator/RelaySupport + RelayCore (screen->loop 3005->3004->rule->TH->tx->forward; answer correlate hbhIn; failover retryable; server-initiated fail-closed) + SBB shells mong | XONG |
| T3 | RuleEngineImpl (matchers day du, sticky, redirect cache TTL), KeyExtractorImpl (IMSI/MSISDN/VPLMN-TBCD/FRAMED_IP/APN), 4 LB strategies (RR/WRR/LeastOutstanding/LoadAware), cfg loader+validator+RuleSetHolder last-good rollback, admin REST + HTMX dashboard | XONG |
| T4 | Bind plane: entity @Column khop V1 (KEY reserved trong H2), BindingSweepJob scheduler, resolver them FRAMED_IP_APN, write-behind fix race that (seq-number atomic transfer), PgBindingDao dual-dialect PG/H2-MERGE, cluster hook fire-and-forget | XONG |
| T5 | DOIC reacting OlrCache/LoadCache/DrmpPolicy/AdmissionController token-bucket + OverloadGateImpl tryAdmit cmd-aware; ScreeningServiceImpl allowlist app/cmd/realm-spoof/IP-CIDR fail-closed | XONG |
| T6 | TopologyHiderImpl pseudo-host deterministic SHA-256, Session-Id rewrite host-part, Route-Record strip/append, FULL_EDGE leak scan de quy; TLS decision record (a) SslHandler corsac TCP + (c) IPsec TS 33.210 cho SCTP, SCTP-DTLS hoan | XONG (integrator tu lam - subagent loi mang 2 lan) |
| T7 | bench harness (DiaWire/FakeHssServer/SeederClient/BenchScenario + Jackson report), dist-tools/package-dist.sh (guard JDK25, khong clobber configs, bytecode major 69), configs seed templates, docs/runbook.md | XONG (integrator tu lam - cung ly do) |

## Cac quyet dinh hoi tu cross-track

0. **Strict micro-jainslee pass (2026-08-23 lan 2)**:
   - `app.bootstrap.DraBootstrap` + `DraBootstrapBean` (CDI StartupEvent): container
     start → registerSbbType($Concrete) → createIesDispatcher → mapEventToSbb
     (Request+Answer → DraRelaySbb) → registerRa(DraRaEndpoint) → tx-sweeper 250ms.
   - `DraRaEndpoint implements RaEndpointPort+RaCommandPort`: fabric ingress →
     RaBootstrapPort.fireEvent với activity key `dra-sess/<sessionId>`
     (fallback `dra-hbh/<hbh>`); outbound qua DraSendCommand.
   - `DraRelaySbb` viết lại đúng convention: extends `CmpBackedSbb`,
     `@SbbAnnotation(et.elisa)`, CMP `sessionId`, `@InjectRa("dra-diameter-ra")`,
     lifecycle sbbCreate/sbbPostCreate/sbbActivate bindCommandPort, `$Concrete`.
   - XOÁ DraBindingSbb/DraOverloadSbb/RaEventBridge (trước đây 3 SBB cùng gọi
     core.onRequest ⇒ xử lý 3 lần); sweep chạy bằng scheduler của bootstrap.
   - Proof: `DraBootstrapContainerTest` — hermetic MicroSleeContainer thật,
     SimulatedPeerFabric, ULR vào qua fireEvent → SBB → forward → ULA correlate
     về đúng link, txActive về 0, commandPort được inject. 278 tests xanh.
   - Protocol fix: answer do DRA sinh (3002/3004/reject/redirect) giờ mang
     Origin-Host/Realm CỦA DRA (`withOrigin`) — RFC 6733 §6.2; `asAnswer` clear
     cả T-bit.
1. **corsac fork patch (worktrees/diameter/corsac-diameter, KHÔNG commit ở đó —
   owner commit)**: `DiameterLinkImpl.onPayload` fallback `sessionID=linkId`
   khi answer thiếu Session-Id (trước đây NPE WorkerPool). Đã `mvn install`
   diameter-impl 10.0.0-41-SNAPSHOT (jar mtime 23:19 23/8).

1. Redirect = 3006 REDIRECT_INDICATION, Loop = 3005 LOOP_DETECTED (RFC 6733 dung chu;
   design doc 01/03 ghi 3005 cho redirect la sai - da sua trong code).
2. Sticky binding store key format "<KEY>:<value>" (vi du IMSI:4520402xxxx).
3. Forward.preferredPeerId = peer egress duoc chon cuoi cung (sticky hoat LB);
   T4 resolver giu preferredPeerId = ingressPeerId cho server-initiated route nguoc ve MME-link.
4. DOIC AVP codes theo RFC text local: OC-OLR con 624(seq)/625(report-type)/626(reduction)/627(validity);
   Load-Type=651/Load-Value=652.
5. S6a command codes kiem chung TS 29.272: ULR=316, CLR=317, AIR=318, DSR/DSA=320,
   PUR=321, NOR=323, ECR=324. Visited-PLMN-Id=1407 (design doc ghi 1408 - sai).

## Bug baseline da fix lam integrator

- AuditLogRepository thieu (AuditRecorder khong compile).
- application.properties thieu datasource config -> Quarkus build fail.
- dra-core pom: merge ca jackson-databind (T3) va h2 test (T4).

## Bug that bat duoc khi hop nhat bench

- AVP header 8/12 bytes (khong phai 12/16) - encoder va decoder deu sai cung luc.
- BufferedInputStream tao moi moi lan readFrame mat byte da buffer - frame loss tinh.
- SEQ_STEP identity function -> moi request cung hbh=2 - correlate rac.

## Con lai cho GATE A-FINAL (can host lab)

1. Bootstrap wiring (integrator-only package app.bootstrap): RelayCore <- RuleEngineImpl +
   CorsacPeerFabric + InMemoryBindingStore(+write-behind) + OverloadGateImpl +
   ScreeningServiceImpl + TopologyHiderImpl + DefaultTxTable + scheduler sweep.
2. Functional N-N lab: 2 IMSI-block -> 2 HSS-pool; IDR ve dung MME; hot-reload giua luu luong.
3. Resilience: kill peer giua tx retryable > 99.9%; kill node DRA lease transfer.
4. NODE_10K: bench harness san sang, chua do tren host that - KHONG claim so lieu.
5. TH pcap checklist IR.88; secret scan; admin auth bcrypt bat truoc khi expose.

Chi tiet tung track: TRACK_T{1..7}_NOTES.md cung thu muc.
