package et.elisa.dra.lab.testapp;

public record Config(int listenPort, int webPort, String bind, boolean tcp,
        String originHost, String originRealm, String peerHost, String peerRealm,
        String subscribersJson, String statusFile) {

    public static final String ORIGIN_HOST = "hss-a.epc.mnc01.mcc452.3gppnetwork.org";
    public static final String ORIGIN_REALM = "epc.mnc01.mcc452.3gppnetwork.org";
    public static final String DEFAULT_PEER_HOST = "dra1.epc.mnc01.mcc452.3gppnetwork.org";

    private static final int DEFAULT_LISTEN_PORT = 3869;
    private static final int DEFAULT_WEB_PORT = 8086;
    private static final String DEFAULT_BIND = "127.0.0.1";

    public static Config parse(String[] args) {
        int listenPort = DEFAULT_LISTEN_PORT;
        int webPort = DEFAULT_WEB_PORT;
        String bind = DEFAULT_BIND;
        boolean tcp = false;
        String originHost = ORIGIN_HOST;
        String originRealm = ORIGIN_REALM;
        String peerHost = DEFAULT_PEER_HOST;
        String peerRealm = ORIGIN_REALM;
        String subscribersJson = null;
        String statusFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--listen-port", "--diameter-port" -> listenPort = Integer.parseInt(value(args, ++i, args[i - 1]));
                case "--web-port" -> webPort = Integer.parseInt(value(args, ++i, args[i - 1]));
                case "--bind" -> bind = value(args, ++i, args[i - 1]);
                case "--tcp" -> tcp = true;
                case "--origin-host" -> originHost = value(args, ++i, args[i - 1]);
                case "--origin-realm" -> originRealm = value(args, ++i, args[i - 1]);
                case "--peer-host" -> peerHost = value(args, ++i, args[i - 1]);
                case "--peer-realm" -> peerRealm = value(args, ++i, args[i - 1]);
                case "--subscribers-json" -> subscribersJson = value(args, ++i, args[i - 1]);
                case "--status-file" -> statusFile = value(args, ++i, args[i - 1]);
                default -> throw new IllegalArgumentException("unknown arg " + args[i]
                        + " (expected --listen-port, --web-port, --bind, --tcp, --origin-host,"
                        + " --origin-realm, --peer-host, --peer-realm, --subscribers-json, --status-file)");
            }
        }
        return new Config(listenPort, webPort, bind, tcp, originHost, originRealm,
                peerHost, peerRealm, subscribersJson, statusFile);
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return args[index];
    }
}
