package et.elisa.dra.bench;

import et.elisa.dra.core.wire.DiaAvp;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal MME simulator for introductions: connects to the DRA ingress,
 * performs CER/CEA, sends one or more ULRs and prints each ULA result.
 *
 * Usage:
 *   java -cp bench.jar et.elisa.dra.bench.FakeMme \
 *     --host 127.0.0.1 --port 3868 --source-port 40001 \
 *     --origin-host mme-01.epc.mnc01.mcc452.3gppnetwork.org \
 *     --realm epc.mnc01.mcc452.3gppnetwork.org \
 *     --imsi 4520402000000001 --count 1
 */
public final class FakeMme {

    private static final int CMD_CER = 257;
    private static final int CMD_ULR = 316;
    private static final int APP_S6A = 16777251;

    public static void main(String[] args) throws Exception {
        String host = opt(args, "host", "127.0.0.1");
        int port = Integer.parseInt(opt(args, "port", "3868"));
        int sourcePort = Integer.parseInt(opt(args, "source-port", "40001"));
        String originHost = opt(args, "origin-host", "mme-01.epc.mnc01.mcc452.3gppnetwork.org");
        String realm = opt(args, "realm", "epc.mnc01.mcc452.3gppnetwork.org");
        String imsi = opt(args, "imsi", "4520402000000001");
        int count = Integer.parseInt(opt(args, "count", "1"));

        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), sourcePort));
            socket.connect(new InetSocketAddress(host, port), 10_000);
            System.out.println("[fake-mme] connected " + socket.getLocalSocketAddress()
                    + " -> " + socket.getRemoteSocketAddress());

            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream(), 65_536));

            long hbh = 1000L;
            send(out, DiaWire.encode(0x80, CMD_CER, 0, hbh, hbh, cerAvps(originHost, realm)));
            byte[] cea = awaitUla(socket, in);
            System.out.println("[fake-mme] CEA result=" + resultCode(cea) + " (expect 2001)");

            for (int i = 0; i < count; i++) {
                hbh += 2;
                String sessionId = "fake-mme-" + sourcePort + "-" + i;
                send(out, ulr(hbh, sessionId, originHost, realm, imsi));
                byte[] ula = awaitUla(socket, in);
                if (ula == null) {
                    System.out.println("[fake-mme] ULA TIMEOUT for " + sessionId);
                    break;
                }
                System.out.println("[fake-mme] ULA session=" + sessionId
                        + " result=" + resultCode(ula)
                        + " hbhRestored=" + (hbhOf(ula) == hbh));
            }
        }
        System.out.println("[fake-mme] done");
    }

    private static List<DiaAvp> cerAvps(String originHost, String realm) {
        List<DiaAvp> avps = new ArrayList<>();
        avps.add(DiaAvp.utf8(263, "fake-mme-cer"));
        avps.add(DiaAvp.utf8(264, originHost));
        avps.add(DiaAvp.utf8(296, realm));
        avps.add(DiaAvp.raw(257, 0, true, new byte[]{0x00, 0x01, 127, 0, 0, 1}));
        avps.add(DiaAvp.uint32(266, 0));
        avps.add(DiaAvp.utf8(269, "fake-mme"));
        avps.add(DiaAvp.uint32(278, 1));
        avps.add(DiaAvp.uint32(258, APP_S6A));
        return avps;
    }

    private static byte[] ulr(long hbh, String sessionId, String originHost,
                              String realm, String imsi) {
        List<DiaAvp> avps = new ArrayList<>();
        avps.add(DiaAvp.utf8(263, sessionId));
        avps.add(DiaAvp.utf8(264, originHost));
        avps.add(DiaAvp.utf8(296, realm));
        avps.add(DiaAvp.utf8(283, realm));
        avps.add(DiaAvp.utf8(1, imsi));
        return DiaWire.encode(0xC0, CMD_ULR, APP_S6A, hbh, hbh + 500_000, avps);
    }

    private static void send(OutputStream out, byte[] frame) throws IOException {
        synchronized (out) {
            out.write(frame);
            out.flush();
        }
    }

    private static byte[] awaitUla(Socket socket, DataInputStream in) throws IOException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            socket.setSoTimeout((int) Math.min(remaining, 10_000));
            byte[] frame;
            try {
                frame = DiaStream.readFrame(in);
            } catch (java.io.InterruptedIOException e) {
                return null;
            }
            if (frame == null) {
                return null;
            }
            if (isUla(frame)) {
                return frame;
            }
        }
    }

    private static boolean isUla(byte[] frame) {
        if (frame.length < 20) {
            return false;
        }
        boolean request = (frame[4] & 0x80) != 0;
        int code = ((frame[5] & 0xFF) << 16) | ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF);
        return !request && code == CMD_ULR;
    }

    private static long resultCode(byte[] frame) {
        for (DiaAvp avp : DiaWire.decodeAvps(frame)) {
            if (avp.code() == 268 && avp.rawBytes() != null && avp.rawBytes().length >= 4) {
                return ((avp.rawBytes()[0] & 0xFFL) << 24) | ((avp.rawBytes()[1] & 0xFFL) << 16)
                        | ((avp.rawBytes()[2] & 0xFFL) << 8) | (avp.rawBytes()[3] & 0xFFL);
            }
        }
        return -1;
    }

    private static long hbhOf(byte[] frame) {
        return (((frame[12] & 0xFFL) << 24) | ((frame[13] & 0xFFL) << 16)
                | ((frame[14] & 0xFFL) << 8) | (frame[15] & 0xFFL)) & 0xFFFFFFFFL;
    }

    private static String opt(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (("--" + name).equals(args[i])) {
                return args[i + 1];
            }
        }
        return def;
    }

    private FakeMme() {
    }
}
