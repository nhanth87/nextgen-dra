package et.elisa.dra.app.ra;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mobius.software.common.dal.timers.WorkerPool;
import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.DiameterLink;
import com.mobius.software.telco.protocols.diameter.DiameterStack;
import com.mobius.software.telco.protocols.diameter.NetworkListener;
import com.mobius.software.telco.protocols.diameter.PeerStateEnum;
import com.mobius.software.telco.protocols.diameter.commands.DiameterMessage;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;
import com.mobius.software.telco.protocols.diameter.impl.DiameterStackImpl;
import com.mobius.software.telco.protocols.diameter.parser.DiameterParser;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import et.elisa.dra.core.peer.DraRaPort;
import et.elisa.dra.core.peer.PeerHealth;
import et.elisa.dra.core.wire.DiaMsg;
import et.elisa.dra.app.ra.wire.DiameterWireCodec;
import io.netty.buffer.Unpooled;

public final class CorsacPeerFabric implements DraRaPort {

    public static final String PRODUCT_NAME = "elisa-nextgen-dra";
    public static final long RELAY_APPLICATION_ID = 0xFFFFFFFFL;
    static final int WORKER_THREADS = 4;
    static final long LINK_POLL_MILLIS = 100L;

    private static final Logger LOG = LogManager.getLogger(CorsacPeerFabric.class);
    private static final java.util.Set<Long> ALL_AUTH_APPLICATION_IDS = allApplicationIds();

    private final DiameterRaConfig config;
    private final PeerRegistry registry;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean commandPackagesRegistered = new AtomicBoolean(false);
    private final ConcurrentMap<String, Boolean> openSeen = new ConcurrentHashMap<>();
    private volatile IngressListener ingressListener;
    private volatile WorkerPool workerPool;
    private volatile DiameterStack stack;
    private volatile ScheduledExecutorService linkWatcher;

    public CorsacPeerFabric(DiameterRaConfig config) {
        Objects.requireNonNull(config, "config");
        this.config = config;
        this.registry = new PeerRegistry(config);
    }

    public void setIngressListener(IngressListener listener) {
        this.ingressListener = listener;
    }

    public PeerRegistry registry() {
        return registry;
    }

    public DiameterRaConfig config() {
        return config;
    }

    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            workerPool = new WorkerPool("dra-ra");
            workerPool.start(WORKER_THREADS);
            stack = new DiameterStackImpl(getClass().getClassLoader(),
                    new org.restcomm.cluster.UUIDGenerator(),
                    workerPool,
                    WORKER_THREADS,
                    config.originHost(),
                    PRODUCT_NAME,
                    0L,
                    10L,
                    120_000L,
                    60_000L,
                    2_000L,
                    0L,
                    0L);
            var nm = stack.getNetworkManager();
            registerCommandPackages();
            nm.addNetworkListener("dra-ra-ingress", this::onCorsacIngress);
            for (PeerConfig peer : config.peers()) {
                InetAddress remote = InetAddress.getByName(peer.host());
                // Bind CLIENT dials to the remote address when it is loopback so
                // SCTP INITs advertise a single address — wildcard binds make
                // kernel multi-homing announce every interface (docker/LAN/wg),
                // and stray INITs flip server-side link connected-state.
                InetAddress local = remote.isLoopbackAddress()
                        ? remote : InetAddress.getByName("0.0.0.0");
                nm.addLink(peer.id(), remote, peer.port(), local, peer.effectiveListenPort(),
                        peer.isServer(), peer.isSctp(),
                        config.originHost(), config.primaryRealm(),
                        peer.effectiveRemoteIdentityHost(),
                        peer.effectiveRemoteIdentityRealm(config.primaryRealm()),
                        Boolean.FALSE, Boolean.FALSE);
                registerLinkApplications(nm, peer);
                registerLinkDecodePackages(peer.id());
                try {
                    nm.startLink(peer.id());
                } catch (DiameterException e) {
                    registry.onChannelDown(peer.id());
                    LOG.warn("[dra-ra] link {} failed to start: {}", peer.id(), e.toString());
                }
            }
            linkWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dra-ra-peer-watch");
                t.setDaemon(true);
                return t;
            });
            linkWatcher.scheduleWithFixedDelay(this::pollLinks,
                    LINK_POLL_MILLIS, LINK_POLL_MILLIS, TimeUnit.MILLISECONDS);
            LOG.info("[dra-ra] corsac fabric started peers={} (LISTEN != peer OPEN)", config.peers().size());
        } catch (Exception e) {
            teardownQuietly();
            started.set(false);
            throw new IllegalStateException("corsac stack failed to start", e);
        }
    }

    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        teardownQuietly();
        openSeen.clear();
        LOG.info("[dra-ra] corsac fabric stopped");
    }

    private void teardownQuietly() {
        ScheduledExecutorService watcher = linkWatcher;
        linkWatcher = null;
        if (watcher != null) {
            watcher.shutdownNow();
        }
        DiameterStack s = stack;
        stack = null;
        if (s != null) {
            try {
                s.stop();
            } catch (RuntimeException e) {
                LOG.warn("[dra-ra] stack stop error", e);
            }
        }
        WorkerPool wp = workerPool;
        workerPool = null;
        if (wp != null) {
            try {
                wp.stop();
            } catch (RuntimeException e) {
                LOG.warn("[dra-ra] worker pool stop error", e);
            }
        }
    }

    @Override
    public void sendToPeer(String peerId, DiaMsg request) {
        Objects.requireNonNull(request, "request");
        if (!request.isRequest()) {
            throw new IllegalArgumentException("sendToPeer requires a request message");
        }
        PeerConnection conn;
        try {
            conn = registry.requireDeliverable(peerId, request.applicationId());
        } catch (PeerNotReadyException e) {
            refreshRegistryBeforeFail(peerId);
            conn = registry.requireDeliverable(peerId, request.applicationId());
        }
        if (conn.saturated()) {
            throw new PeerNotReadyException(peerId, "outstanding >= maxOutstanding " + conn.config().maxOutstanding());
        }
        DiameterLink link = link(peerId);
        if (link == null || !link.isConnected()) {
            throw new PeerNotReadyException(peerId, "no live transport link");
        }
        conn.incOutstanding();
        try {
            link.sendEncodedMessage(Unpooled.wrappedBuffer(DiameterWireCodec.encode(request)), noop());
        } catch (RuntimeException e) {
            conn.decOutstanding();
            throw new PeerNotReadyException(peerId, "send failed: " + e.getMessage());
        }
    }

    @Override
    public void sendAnswerOnLink(String peerId, DiaMsg answer) {
        Objects.requireNonNull(answer, "answer");
        if (answer.isRequest()) {
            throw new IllegalArgumentException("sendAnswerOnLink requires an answer message");
        }
        try {
            registry.requireOpenLink(peerId);
        } catch (PeerNotReadyException e) {
            refreshRegistryBeforeFail(peerId);
            registry.requireOpenLink(peerId);
        }
        DiameterLink link = link(peerId);
        if (link == null || !link.isConnected()) {
            throw new PeerNotReadyException(peerId, "no live transport link");
        }
        try {
            link.sendEncodedMessage(Unpooled.wrappedBuffer(DiameterWireCodec.encode(answer)), noop());
        } catch (RuntimeException e) {
            throw new PeerNotReadyException(peerId, "send failed: " + e.getMessage());
        }
    }

    @Override
    public java.util.Map<String, PeerHealth> peersHealth() {
        return registry.healthMap();
    }

    DiameterLink link(String peerId) {
        DiameterStack s = stack;
        return s == null ? null : s.getNetworkManager().getLink(peerId);
    }

    private void onCorsacIngress(DiameterMessage message, String linkId, AsyncCallback callback) {
        if (message == null) {
            return;
        }
        registry.onActivity(linkId);
        if (CorsacMessageBridge.isBaseProtocol(message)) {
            return;
        }
        IngressListener listener = ingressListener;
        if (listener == null) {
            LOG.warn("[dra-ra] ingress dropped (no listener) link={}", linkId);
            return;
        }
        IngressEvent event;
        try {
            event = CorsacMessageBridge.toIngressEvent(message, linkId, System.nanoTime());
        } catch (RuntimeException e) {
            LOG.warn("[dra-ra] ingress bridge failed link={} err={}", linkId, e.toString(), e);
            return;
        }
        if (event instanceof IngressAnswer) {
            registry.connection(linkId).ifPresent(PeerConnection::decOutstanding);
        }
        LOG.debug("[dra-ra] ingress link={} {} cmd={} hbh={}", linkId,
                event.getClass().getSimpleName(),
                event instanceof IngressRequest r ? r.msg().commandCode()
                        : ((IngressAnswer) event).msg().commandCode(),
                event instanceof IngressRequest r2 ? r2.msg().hopByHopId()
                        : ((IngressAnswer) event).msg().hopByHopId());
        try {
            listener.onIngress(event);
        } catch (RuntimeException e) {
            LOG.warn("[dra-ra] ingress listener failed link={} err={}", linkId, e.toString(), e);
        }
    }

    private void pollLinks() {
        DiameterStack s = stack;
        if (s == null || !started.get()) {
            return;
        }
        for (PeerConfig peer : config.peers()) {
            try {
                mirrorLinkState(s, peer);
            } catch (RuntimeException e) {
                LOG.debug("[dra-ra] link watch error {}", e.toString());
            }
        }
    }

    private void mirrorLinkState(DiameterStack s, PeerConfig peer) {
        DiameterLink link = s.getNetworkManager().getLink(peer.id());
        if (link == null) {
            return;
        }
        boolean connected = link.isConnected();
        boolean open = connected && link.isUp()
                && link.getPeerState() == PeerStateEnum.OPEN;
        if (open && openSeen.putIfAbsent(peer.id(), Boolean.TRUE) == null) {
            registry.onChannelUp(peer.id());
            registry.onCeaAccepted(peer.id(), capabilitySeed(peer));
        } else if (open) {
            registry.onChannelUp(peer.id());
            registry.onCeaRefresh(peer.id());
        } else if (connected) {
            registry.onChannelUp(peer.id());
        } else {
            openSeen.remove(peer.id());
            registry.onChannelDown(peer.id());
        }
    }

    /**
     * Close the poll-lag race on first traffic after CEA: the link watcher
     * mirrors corsac state every LINK_POLL_MILLIS, so a request answered
     * immediately after handshake can hit requireOpenLink before the tick.
     * Verify against live link truth (channel up + OPEN) and sync the registry
     * before declaring the peer undeliverable — never bypass peer-truth checks.
     */
    private void refreshRegistryBeforeFail(String peerId) {
        DiameterStack s = stack;
        if (s == null || !started.get()) {
            return;
        }
        config.peers().stream()
                .filter(p -> p.id().equals(peerId))
                .findFirst()
                .ifPresent(peer -> {
                    try {
                        mirrorLinkState(s, peer);
                    } catch (RuntimeException e) {
                        LOG.debug("[dra-ra] refresh state error {}", e.toString());
                    }
                });
    }

    private Set<Integer> capabilitySeed(PeerConfig peer) {
        return peer.advertisedApps();
    }

    private void registerLinkApplications(com.mobius.software.telco.protocols.diameter.NetworkManager nm,
                                          PeerConfig peer) throws DiameterException {
        java.util.Set<Long> auth = new java.util.LinkedHashSet<>(ALL_AUTH_APPLICATION_IDS);
        auth.add(RELAY_APPLICATION_ID);
        peer.advertisedApps().forEach(app -> auth.add((long) app));
        List<Long> acct = List.of((long) com.mobius.software.telco.protocols.diameter.ApplicationIDs.ACCOUNTING,
                (long) com.mobius.software.telco.protocols.diameter.ApplicationIDs.CREDIT_CONTROL);
        Package stubPackage = et.elisa.dra.app.ra.linkreg.LinkRegMarker.class.getPackage();
        nm.registerApplication(peer.id(),
                java.util.List.<com.mobius.software.telco.protocols.diameter.primitives.common.VendorSpecificApplicationId>of(),
                List.copyOf(auth),
                acct,
                stubPackage,
                stubPackage);
    }

    private static java.util.Set<Long> allApplicationIds() {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        for (java.lang.reflect.Field f : com.mobius.software.telco.protocols.diameter.ApplicationIDs.class
                .getFields()) {
            if (f.getType() == int.class && java.lang.reflect.Modifier.isPublic(f.getModifiers())
                    && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                try {
                    out.add((long) f.getInt(null));
                } catch (IllegalAccessException ignored) {
                    return java.util.Set.of();
                }
            }
        }
        return out;
    }

    private void registerCommandPackages() {
        if (!commandPackagesRegistered.compareAndSet(false, true)) {
            return;
        }
        var parser = stack.getGlobalParser();
        registerPackagesOn(parser);
    }

    private void registerLinkDecodePackages(String linkId) {
        DiameterLink link = link(linkId);
        if (link == null || !(link instanceof com.mobius.software.telco.protocols.diameter.impl.DiameterLinkImpl impl)) {
            LOG.debug("[dra-ra] link {} not a corsac impl, skipping per-link decode packages", linkId);
            return;
        }
        try {
            java.lang.reflect.Field pf = com.mobius.software.telco.protocols.diameter.impl.DiameterLinkImpl.class
                    .getDeclaredField("parser");
            pf.setAccessible(true);
            if (pf.get(impl) instanceof DiameterParser linkParser) {
                registerPackagesOn(linkParser);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("[dra-ra] cannot access per-link parser for {}: {}", linkId, e.toString());
        }
    }

    private void registerPackagesOn(DiameterParser parser) {
        ClassLoader cl = DiameterStackImpl.class.getClassLoader();
        int registered = 0;
        for (String simple : config.commandPackages()) {
            String fq = DiameterRaConfig.COMMAND_PACKAGE_ROOT + simple;
            try {
                Package p = materializePackage(cl, fq);
                if (p == null) {
                    LOG.debug("[dra-ra] command package {} not on classpath", fq);
                    continue;
                }
                parser.registerApplication(cl, p);
                registered++;
            } catch (Exception e) {
                LOG.debug("[dra-ra] command package {} registration skipped: {}", fq, e.toString());
            }
        }
        LOG.info("[dra-ra] command packages registered {}/{} on {} (any-command decode)", registered,
                config.commandPackages().size(), parser == stack.getGlobalParser() ? "global" : "link");
    }

    private static Package materializePackage(ClassLoader cl, String fqcn) throws IOException {
        Package existing = Package.getPackage(fqcn);
        if (existing != null) {
            return existing;
        }
        String path = fqcn.replace('.', '/');
        java.util.Enumeration<java.net.URL> resources = cl.getResources(path);
        while (resources.hasMoreElements()) {
            java.net.URL resource = resources.nextElement();
            if ("jar".equals(resource.getProtocol())) {
                String jarPath = java.net.URLDecoder.decode(
                        resource.getPath().substring(5, resource.getPath().indexOf('!')),
                        java.nio.charset.StandardCharsets.UTF_8);
                try (var jarFile = new java.util.jar.JarFile(jarPath)) {
                    var entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith(path) && name.endsWith(".class") && !name.contains("$")) {
                            try {
                                return Class.forName(name.replace('/', '.')
                                        .substring(0, name.length() - 6)).getPackage();
                            } catch (ClassNotFoundException | LinkageError ignored) {
                                break;
                            }
                        }
                    }
                }
            } else if ("file".equals(resource.getProtocol())) {
                java.io.File dir = new java.io.File(resource.getFile());
                File[] children = dir.listFiles((d, n) -> n.endsWith(".class") && !n.contains("$"));
                if (children != null) {
                    for (File child : children) {
                        try {
                            return Class.forName(fqcn + '.' + child.getName()
                                    .substring(0, child.getName().length() - 6)).getPackage();
                        } catch (ClassNotFoundException | LinkageError ignored) {
                            continue;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static AsyncCallback noop() {
        return new AsyncCallback() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(DiameterException e) {
                LOG.debug("[dra-ra] async send error {}", e.toString());
            }
        };
    }
}
