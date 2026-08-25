package et.elisa.dra.app.bootstrap;

import com.microjainslee.core.MicroSleeContainer;

import com.microjainslee.telemetry.MicrometerTelemetryPort;
import com.microjainslee.telemetry.TelemetryDispatchObserver;
import com.microjainslee.telemetry.TelemetryPort;
import com.microjainslee.telemetry.TelemetryRaObserver;

import et.elisa.dra.app.ra.DraAnswerEvent;
import et.elisa.dra.app.ra.DraRaEndpoint;
import et.elisa.dra.app.ra.DraRequestEvent;
import et.elisa.dra.app.sbbs.DraRelaySbb;
import et.elisa.dra.app.sbbs.relay.RelayCore;
import et.elisa.dra.core.peer.DraRaPort;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Strict micro-jainslee wiring for the DRA relay plane (integrator-owned):
 * container start, SBB type registration ($Concrete), IES dispatcher,
 * event→SBB mapping, RA endpoint activation and tx sweep.
 */
public final class DraBootstrap implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(DraBootstrap.class);
    static final String RELAY_SBB = "DraRelaySbb";
    private static final long SWEEP_PERIOD_MILLIS = 250L;

    private final MicroSleeContainer container;
    private final RelayCore core;
    private final DraRaPort raPort;
    private volatile DraRaEndpoint endpoint;
    private volatile TelemetryPort telemetryPort;
    private volatile ScheduledExecutorService sweeper;
    private volatile boolean started;

    public DraBootstrap(MicroSleeContainer container, RelayCore core, DraRaPort raPort) {
        this.container = Objects.requireNonNull(container, "container");
        this.core = Objects.requireNonNull(core, "core");
        this.raPort = Objects.requireNonNull(raPort, "raPort");
    }

    public synchronized void init() {
        if (started) {
            return;
        }
        started = true;
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }
        registerSbbTypes();
        bindInitialEventSelector();
        mapEventsToSbb();
        wireTelemetry();
        wireRaEndpoint();
        startSweeper();
        LOG.info("[dra-bootstrap] strict micro-jainslee wiring complete (relay sbb={}, ra={})",
                RELAY_SBB, DraRaEndpoint.RA_NAME);
    }

    private void registerSbbTypes() {
        container.registerSbbType(DraRelaySbb.class, () -> new DraRelaySbb.$Concrete(core));
    }

    private void bindInitialEventSelector() {
        container.createIesDispatcher();
    }

    private void mapEventsToSbb() {
        container.mapEventToSbb(DraRequestEvent.class, RELAY_SBB);
        container.mapEventToSbb(DraAnswerEvent.class, RELAY_SBB);
    }

    private void wireTelemetry() {
        try {
            PrometheusMeterRegistry registry =
                    new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            MicrometerTelemetryPort micrometer = new MicrometerTelemetryPort(registry, container);
            micrometer.start();
            telemetryPort = micrometer;
            container.getEventRouter().setDispatchObserver(new TelemetryDispatchObserver(micrometer));
            container.setRaObserver(new TelemetryRaObserver(micrometer));
        } catch (RuntimeException e) {
            LOG.debug("[dra-bootstrap] telemetry skipped: {}", e.toString());
        }
    }

    private void wireRaEndpoint() {
        endpoint = new DraRaEndpoint(raPort);
        container.registerRa(endpoint, endpoint);
        if (!endpoint.isStarted()) {
            throw new IllegalStateException("dra ra endpoint did not activate");
        }
    }

    /** Test/RA entry: feed an ingress event through the container path. */
    public void ingest(et.elisa.dra.app.ra.IngressEvent event) {
        DraRaEndpoint ep = endpoint;
        if (ep == null) {
            throw new IllegalStateException("bootstrap not initialized");
        }
        ep.onRaIngress(event);
    }

    private void startSweeper() {
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dra-tx-sweep");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(() -> {
            try {
                core.sweep(System.currentTimeMillis());
            } catch (RuntimeException e) {
                LOG.debug("[dra-bootstrap] sweep error {}", e.toString());
            }
        }, SWEEP_PERIOD_MILLIS, SWEEP_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        ScheduledExecutorService s = sweeper;
        sweeper = null;
        if (s != null) {
            s.shutdownNow();
        }
        DraRaEndpoint ep = endpoint;
        endpoint = null;
        if (ep != null) {
            ep.deactivate();
        }
        TelemetryPort tp = telemetryPort;
        telemetryPort = null;
        if (tp instanceof MicrometerTelemetryPort mtp) {
            mtp.stop();
        }
        if (container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
        started = false;
    }

    public RelayCore core() {
        return core;
    }

    public DraRaEndpoint endpoint() {
        return endpoint;
    }
}
