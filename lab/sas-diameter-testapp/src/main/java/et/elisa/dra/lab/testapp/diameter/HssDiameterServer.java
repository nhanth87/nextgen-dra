package et.elisa.dra.lab.testapp.diameter;

import java.net.InetAddress;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.cluster.IDGenerator;
import org.restcomm.cluster.UUIDGenerator;

import com.mobius.software.common.dal.timers.WorkerPool;
import com.mobius.software.telco.protocols.diameter.ApplicationIDs;
import com.mobius.software.telco.protocols.diameter.DiameterStack;
import com.mobius.software.telco.protocols.diameter.impl.DiameterStackImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.gx.GxProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.s6a.S6aProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.swx.SwxProviderImpl;
import com.mobius.software.telco.protocols.diameter.primitives.common.VendorSpecificApplicationId;

import et.elisa.dra.lab.testapp.HssSimulator;

public final class HssDiameterServer {

    private static final Logger LOG = LogManager.getLogger(HssDiameterServer.class);

    private static final String LINK_ID = "hss-sim";

    private final HssSimulator hss;
    private final String bindAddress;
    private final int diameterPort;
    private final boolean sctp;
    private final String originHost;
    private final String originRealm;
    private final String peerHost;
    private final String peerRealm;

    private WorkerPool workerPool;
    private DiameterStack stack;
    private final IDGenerator<?> generator = new UUIDGenerator();

    public HssDiameterServer(HssSimulator hss, String bindAddress, int diameterPort, boolean sctp,
            String originHost, String originRealm, String peerHost, String peerRealm) {
        this.hss = hss;
        this.bindAddress = bindAddress;
        this.diameterPort = diameterPort;
        this.sctp = sctp;
        this.originHost = originHost;
        this.originRealm = originRealm;
        this.peerHost = peerHost;
        this.peerRealm = peerRealm;
    }

    public void start() throws Exception {
        workerPool = new WorkerPool("HSS-SIM");
        workerPool.start(4);
        stack = new DiameterStackImpl(getClass().getClassLoader(), generator, workerPool, 4,
                originHost, "HSS Simulator", 0L, 10L,
                10_000L, 2_000L, 5_000L, 5_000L, 5_000L);
        stack.getNetworkManager().addLink(LINK_ID,
                InetAddress.getByName(bindAddress), 0,
                InetAddress.getByName(bindAddress), diameterPort,
                true, sctp, originHost, originRealm, peerHost, peerRealm, false);

        Package s6aCommands = packageOf("com.mobius.software.telco.protocols.diameter.commands.s6a",
                com.mobius.software.telco.protocols.diameter.commands.s6a.UpdateLocationRequest.class);
        Package s6aImpl = packageOf("com.mobius.software.telco.protocols.diameter.impl.commands.s6a",
                com.mobius.software.telco.protocols.diameter.impl.commands.s6a.UpdateLocationRequestImpl.class);
        Package swxCommands = packageOf("com.mobius.software.telco.protocols.diameter.commands.swx",
                com.mobius.software.telco.protocols.diameter.commands.swx.MultimediaAuthRequest.class);
        Package swxImpl = packageOf("com.mobius.software.telco.protocols.diameter.impl.commands.swx",
                com.mobius.software.telco.protocols.diameter.impl.commands.swx.MultimediaAuthRequestImpl.class);
        Package gxCommands = packageOf("com.mobius.software.telco.protocols.diameter.commands.gx",
                com.mobius.software.telco.protocols.diameter.commands.gx.CreditControlRequest.class);
        Package gxImpl = packageOf("com.mobius.software.telco.protocols.diameter.impl.commands.gx",
                com.mobius.software.telco.protocols.diameter.impl.commands.gx.CreditControlRequestImpl.class);

        List<VendorSpecificApplicationId> noVendor = List.of();
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.S6A), List.of(), s6aCommands, s6aImpl);
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.SWX), List.of(), swxCommands, swxImpl);
        stack.getNetworkManager().registerApplication(LINK_ID, noVendor,
                List.of((long) ApplicationIDs.GX), List.of(), gxCommands, gxImpl);
        stack.getNetworkManager().startLink(LINK_ID);

        S6aProviderImpl s6aProvider =
                (S6aProviderImpl) stack.getProvider((long) ApplicationIDs.S6A, s6aCommands);
        s6aProvider.setServerListener(generator.generateID(),
                new S6aHandler(hss, s6aProvider.getMessageFactory(), s6aProvider.getAvpFactory()));

        SwxProviderImpl swxProvider =
                (SwxProviderImpl) stack.getProvider((long) ApplicationIDs.SWX, swxCommands);
        swxProvider.setServerListener(generator.generateID(),
                new SwxHandler(hss, swxProvider.getMessageFactory(), swxProvider.getAvpFactory()));

        GxProviderImpl gxProvider =
                (GxProviderImpl) stack.getProvider((long) ApplicationIDs.GX, gxCommands);
        gxProvider.setServerListener(generator.generateID(),
                new GxHandler(hss, gxProvider.getMessageFactory()));

        LOG.info("HSS Diameter listening on {}:{} transport={} origin={}/{} peer={}/{}",
                bindAddress, diameterPort, sctp ? "sctp" : "tcp", originHost, originRealm,
                peerHost, peerRealm);
    }

    public void stop() {
        if (stack != null) {
            try {
                stack.stop();
            } catch (Exception e) {
                LOG.warn("HSS stack stop", e);
            }
            stack = null;
        }
        if (workerPool != null) {
            workerPool.stop();
            workerPool = null;
        }
    }

    public boolean isListening() {
        return stack != null;
    }

    private static Package packageOf(String name, Class<?> anchor) {
        @SuppressWarnings("unused")
        Class<?> loaded = anchor;
        Package pkg = Package.getPackage(name);
        if (pkg == null) {
            throw new IllegalStateException("package not loaded: " + name);
        }
        return pkg;
    }
}
