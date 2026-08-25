package et.elisa.dra.app.bootstrap;

import com.microjainslee.core.MicroSleeContainer;

import et.elisa.dra.app.ra.DraRaEndpoint;
import et.elisa.dra.app.admin.AdminPort;
import et.elisa.dra.core.peer.PeerHealth;
import et.elisa.dra.app.sbbs.relay.CandidateSource;
import et.elisa.dra.app.sbbs.relay.RelayCore;
import et.elisa.dra.core.bind.InMemoryBindingStore;
import et.elisa.dra.core.bind.BindingStore;
import et.elisa.dra.core.bind.ServerInitiatedResolverImpl;
import et.elisa.dra.core.cfg.RuleSetHolder;
import et.elisa.dra.core.engine.RuleEngine;
import et.elisa.dra.core.engine.RuleEngineImpl;
import et.elisa.dra.core.lb.PeerHandle;
import et.elisa.dra.core.overload.AdmissionController;
import et.elisa.dra.core.overload.LoadCache;
import et.elisa.dra.core.overload.OlrCache;
import et.elisa.dra.core.overload.OverloadGateImpl;
import et.elisa.dra.core.peer.DraRaPort;
import et.elisa.dra.core.screen.ScreeningConfig;
import et.elisa.dra.core.screen.ScreeningServiceImpl;
import et.elisa.dra.core.th.PseudoHostMapper;
import et.elisa.dra.core.th.ThConfig;
import et.elisa.dra.core.th.TopologyHiderImpl;
import et.elisa.dra.core.th.TopologyHiderImpl;
import et.elisa.dra.core.tx.DefaultTxTable;
import et.elisa.dra.core.tx.HbhAllocator;
import et.elisa.dra.app.ra.DiameterRaConfig;
import et.elisa.dra.app.ra.PeerConfig;
import et.elisa.dra.app.ra.SimulatedPeerFabric;
import et.elisa.dra.app.ra.cfg.DiameterRaConfigJson;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Quarkus CDI entry (integrator-owned). Wires the strict micro-jainslee
 * relay plane on StartupEvent and tears it down on shutdown.
 */
@ApplicationScoped
public class DraBootstrapBean implements AdminPort {

    private static final Logger LOG = LogManager.getLogger(DraBootstrapBean.class);

    @Inject
    MicroSleeContainer container;

    @Inject
    RuleEngineImpl engine;

    @Inject
    RuleSetHolder ruleSetHolder;

    private volatile DraBootstrap bootstrap;
    private volatile DraRaPort raPort;
    private volatile DiameterRaConfig activeConfig;

    public DraBootstrapBean() {
    }

    void onStart(@Observes StartupEvent ev) {
        LOG.info("DRA bootstrap triggered by StartupEvent");
        init();
    }

    synchronized void init() {
        if (bootstrap != null) {
            return;
        }
        DiameterRaConfig config = loadConfig();
        activeConfig = config;
        raPort = createFabric(config);
        syncCandidates(config);

        BindingStore bindings = new InMemoryBindingStore();
        var overload = new OverloadGateImpl(new OlrCache(), new LoadCache(),
                new AdmissionController(50_000, 5_000, 5_000, 500));
        var screener = new ScreeningServiceImpl(new ScreeningConfig(java.util.Map.of()));
        String suffix = config.primaryRealm();
        var thider = new TopologyHiderImpl(new PseudoHostMapper(
                new ThConfig(suffix, "dra-edge", 4, false, java.util.Set.of())),
                config.originHost());
        RelayCore core = new RelayCore(engine, new DefaultTxTable(),
                new HbhAllocator(), raPort, bindings,
                new ServerInitiatedResolverImpl(bindings), overload, screener, thider,
                candidatesFrom(engine),
                config.originHost(), config.primaryRealm(), config.twTimeoutMillis(), 3);

        bootstrap = new DraBootstrap(container, core, raPort);
        bootstrap.init();
        if (raPort instanceof SimulatedPeerFabric sim) {
            sim.setIngressListener(bootstrap.endpoint()::onRaIngress);
        } else if (raPort instanceof et.elisa.dra.app.ra.CorsacPeerFabric corsac) {
            corsac.setIngressListener(bootstrap.endpoint()::onRaIngress);
            corsac.start();
        }
        syncCandidates(config);
        LOG.info("[dra-bean] DRA relay plane live (peers={}, ready={})",
                config.peers().size(), raPort.peersHealth().size());
    }

    @io.quarkus.scheduler.Scheduled(every = "5s")
    void onCron() {
        DiameterRaConfig config = activeConfig;
        DraRaPort port = raPort;
        if (config == null || port == null || bootstrap == null) {
            return;
        }
        try {
            syncCandidates(config);
        } catch (RuntimeException e) {
            LOG.debug("[dra-bean] candidate refresh skipped: {}", e.toString());
        }
    }

    private static CandidateSource candidatesFrom(RuleEngineImpl engine) {
        return (groupId, excludePeers) -> {
            var runtime = engine.group(groupId);
            if (runtime == null) {
                return List.of();
            }
            return runtime.candidates().stream()
                    .map(PeerHandle::peerId)
                    .filter(id -> !excludePeers.contains(id))
                    .toList();
        };
    }

    private void syncCandidates(DiameterRaConfig config) {
        var health = raPort.peersHealth();
        var byGroup = new java.util.HashMap<String, List<PeerHandle>>();
        for (PeerConfig peer : config.peers()) {
            var h = health.get(peer.id());
            boolean up = h != null && h.ready();
            byGroup.computeIfAbsent(peer.group(), g -> new java.util.ArrayList<>())
                    .add(new PeerHandle(peer.id(), peer.weight(), 0, up ? null : -1));
        }
        byGroup.forEach((group, handles) -> {
            if (engine instanceof RuleEngineImpl impl) {
                impl.updateCandidates(group, handles);
            }
        });
    }

    private DraRaPort createFabric(DiameterRaConfig config) {
        String mode = System.getProperty("dra.fabric.mode",
                System.getenv().getOrDefault("DRA_FABRIC_MODE", "corsac"));
        if ("simulated".equalsIgnoreCase(mode)) {
            LOG.info("[dra-bean] simulated fabric selected (mode=simulated)");
            return new SimulatedPeerFabric(config);
        }
        try {
            Class.forName("com.mobius.software.telco.protocols.diameter.impl.DiameterStackImpl");
            return new et.elisa.dra.app.ra.CorsacPeerFabric(config);
        } catch (RuntimeException | ClassNotFoundException e) {
            LOG.warn("[dra-bean] corsac unavailable, falling back to simulated fabric: {}",
                    e.toString());
            return new SimulatedPeerFabric(config);
        }
    }

    private DiameterRaConfig loadConfig() {
        for (Path candidate : List.of(Path.of("configs/dra-peers.json"),
                Path.of("../configs/dra-peers.json"))) {
            if (Files.exists(candidate)) {
                try {
                    return DiameterRaConfigJson.parse(Files.readString(candidate));
                } catch (Exception e) {
                    throw new IllegalStateException("invalid " + candidate, e);
                }
            }
        }
        LOG.warn("[dra-bean] configs/dra-peers.json not found — empty peer set");
        return new DiameterRaConfig(java.util.List.of(),
                System.getProperty("dra.origin-host", "dra1.epc.mnc01.mcc452.3gppnetwork.org"),
                java.util.Set.of(System.getProperty("dra.realm",
                        "epc.mnc01.mcc452.3gppnetwork.org")),
                DiameterRaConfig.DEFAULT_WATCHDOG_MILLIS, DiameterRaConfig.DEFAULT_TW_MILLIS);
    }

    @PreDestroy
    void shutdown() {
        DraRaPort port = raPort;
        if (port instanceof et.elisa.dra.app.ra.CorsacPeerFabric corsac) {
            corsac.stop();
        }
        raPort = null;
        DraBootstrap b = bootstrap;
        bootstrap = null;
        if (b != null) {
            b.close();
        }
    }

    public DraBootstrap bootstrap() {
        return bootstrap;
    }

    public DraRaEndpoint endpoint() {
        DraBootstrap b = bootstrap;
        return b == null ? null : b.endpoint();
    }

    // ── AdminPort: REST reads real fabric truth, not a NOOP ──

    @Override
    public boolean live() {
        return bootstrap != null && endpoint() != null && endpoint().isStarted();
    }

    @Override
    public java.util.Map<String, PeerHealth> peersHealth() {
        DraRaPort port = raPort;
        return port == null ? java.util.Map.of() : port.peersHealth();
    }

    @Override
    public long bindingsCount() {
        DraBootstrap b = bootstrap;
        return b == null ? 0 : b.core().txActive();
    }

    @Override
    public boolean enablePeer(String peerId) {
        return false;
    }

    @Override
    public boolean disablePeer(String peerId) {
        return false;
    }
}
