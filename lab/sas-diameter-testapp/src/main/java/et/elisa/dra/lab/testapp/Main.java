package et.elisa.dra.lab.testapp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import et.elisa.dra.lab.testapp.diameter.HssDiameterServer;
import et.elisa.dra.lab.testapp.web.ControlWebServer;

public final class Main {

    private static final Logger LOG = LogManager.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Config cfg = Config.parse(args);

        MessageLog messageLog = new MessageLog();
        HssSimulator hss = new HssSimulator(messageLog);
        List<SubscriberSeeds.Spec> seeds = cfg.subscribersJson() == null
                ? SubscriberSeeds.labDefaults()
                : SubscriberSeeds.parse(Files.readString(Path.of(cfg.subscribersJson())));
        SubscriberSeeds.apply(hss, seeds);

        HssDiameterServer diameter = new HssDiameterServer(hss, cfg.bind(), cfg.listenPort(),
                !cfg.tcp(), cfg.originHost(), cfg.originRealm(), cfg.peerHost(), cfg.peerRealm());
        ControlWebServer web = new ControlWebServer(hss, diameter);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            writeStatusFile(cfg.statusFile());
            LOG.info("shutting down");
            web.stop();
            diameter.stop();
        }, "shutdown"));

        diameter.start();
        web.start(cfg.bind(), cfg.webPort());
        LOG.info("HSS simulator lab ready — listen={}:{} transport={} origin={}/{} peer={}/{}"
                        + " seeds={} statusFile={}",
                cfg.bind(), cfg.listenPort(), cfg.tcp() ? "tcp" : "sctp",
                cfg.originHost(), cfg.originRealm(), cfg.peerHost(), cfg.peerRealm(),
                seeds.size(), cfg.statusFile() == null ? "-" : cfg.statusFile());
    }

    private static void writeStatusFile(String statusFile) {
        if (statusFile == null || statusFile.isBlank()) {
            return;
        }
        try {
            ExitReason.write(Path.of(statusFile), ExitReason.SHUTDOWN);
        } catch (Exception e) {
            LOG.warn("failed to write exit-reason file {}", statusFile, e);
        }
    }

    private Main() {
    }
}
