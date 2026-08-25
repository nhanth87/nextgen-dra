package et.elisa.dra.bench;

import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal SCTP Diameter seeder (MME role): CER (app-id 0) then N ULRs,
 * mirroring SeederClient's wire behaviour over kernel SCTP.
 */
public final class SctpSeederClient implements AutoCloseable {

    private static final int CMD_CER_CEA = 257;
    private static final int CMD_ULR_ULA = 316;

    public record Stats(long sent, long received, long timeouts, long elapsedMillis) {
    }

    private final String host;
    private final int port;
    private final int sourcePort;
    private final double tps;
    private final long timeoutNanos;
    private final String imsiPrefix;
    private final String destinationHost;
    private final SctpChannel channel;
    private final ConcurrentMap<Long, Long> pending = new ConcurrentHashMap<>();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong hbhSeq = new AtomicLong(1);
    private final AtomicLong e2eSeq = new AtomicLong(0);

    public SctpSeederClient(String host, int port, int sourcePort, double tps,
                            long timeoutMillis, String imsiPrefix,
                            String destinationHost) throws IOException {
        this.host = host;
        this.port = port;
        this.sourcePort = sourcePort;
        this.tps = tps;
        this.timeoutNanos = timeoutMillis * 1_000_000L;
        this.imsiPrefix = imsiPrefix;
        this.destinationHost = destinationHost == null ? "" : destinationHost;
        var addr = new InetSocketAddress(host, port);
        channel = SctpChannel.open();
        if (sourcePort > 0) {
            channel.bind(new InetSocketAddress(host.equals("127.0.0.1") ? "127.0.0.1" : "0.0.0.0", sourcePort));
        }
        channel.connect(addr, 0, 0);
        handshake();
    }

    private void send(byte[] frame) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(frame);
        var remote = channel.getRemoteAddresses().iterator().next();
        channel.send(buf, MessageInfo.createOutgoing(remote, 0));
    }

    /** Non-blocking receive polled until the deadline; null on timeout/close. */
    private byte[] receive(long deadlineMillis) {
        ByteBuffer buf = ByteBuffer.allocate(65536);
        try {
            while (System.currentTimeMillis() < deadlineMillis) {
                MessageInfo info = channel.receive(buf, null, null);
                if (info != null) {
                    buf.flip();
                    byte[] out = new byte[buf.remaining()];
                    buf.get(out);
                    return out;
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException closed) {
            return null;
        }
        return null;
    }

    private void handshake() throws IOException {
        List<et.elisa.dra.core.wire.DiaAvp> cerAvps = List.of(
                DiaWire.utf8(264, 0, false, System.getProperty("seeder.host", "seeder.example.org")),
                DiaWire.utf8(296, 0, false, "epc.mnc01.mcc452.3gppnetwork.org"),
                DiaWire.u32(258, 0, true, 16777251));
        send(DiaWire.encode(DiaWire.FLAG_REQUEST | DiaWire.FLAG_PROXYABLE,
                CMD_CER_CEA, 0, 1, 1, cerAvps));
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            byte[] frame = receive(deadline);
            if (frame == null) {
                throw new IOException("connection closed during CER/CEA");
            }
            DiaWire.Header h = DiaWire.decodeHeader(frame);
            if (!h.isRequest() && h.commandCode() == CMD_CER_CEA) {
                long rc = DiaWire.resultCodeOf(frame);
                if (rc != 2001) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : frame) sb.append(String.format("%02x", b));
                    throw new IOException("CEA rc=" + rc + " HEX=" + sb);
                }
                return;
            }
        }
        throw new IOException("no CEA within timeout");
    }

    public Stats run(int count) throws IOException, InterruptedException {
        long begin = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            long gapNanos = (long) (1_000_000_000L / tps);
            long next = System.nanoTime() + gapNanos;
            sendOneUlr();
            long remain = next - System.nanoTime();
            if (remain > 0) {
                Thread.sleep(remain / 1_000_000, (int) (remain % 1_000_000));
            }
        }
        long deadline = System.currentTimeMillis() + timeoutNanos / 1_000_000L;
        while (received.get() < sent.get() && System.currentTimeMillis() < deadline) {
            byte[] frame = receive(deadline);
            if (frame == null) {
                break;
            }
            DiaWire.Header h = DiaWire.decodeHeader(frame);
            if (!h.isRequest()) {
                Long start = pending.remove(h.hopByHopId());
                StringBuilder sb = new StringBuilder();
                for (byte b : frame) sb.append(String.format("%02x", b));
                System.out.printf("ans cmd=%d hbh=%d e2e=%d rc=%d len=%d HEX=%s%n",
                        h.commandCode(), h.hopByHopId(), h.endToEndId(),
                        DiaWire.resultCodeOf(frame), frame.length, sb);
                if (start != null) {
                    received.incrementAndGet();
                    lastResultCode = (int) DiaWire.resultCodeOf(frame);
                }
            }
        }
        timeouts.addAndGet(sent.get() - received.get());
        return new Stats(sent.get(), received.get(), timeouts.get(),
                System.currentTimeMillis() - begin);
    }

    private volatile int lastResultCode;

    public int lastResultCode() {
        return lastResultCode;
    }

    private void sendOneUlr() throws IOException {
        long hbh = hbhSeq.incrementAndGet();
        long e2e = e2eSeq.incrementAndGet();
        String imsi = imsiPrefix + String.format("%08d", (int) (e2e % 100_000_000));
        var avps = new java.util.ArrayList<et.elisa.dra.core.wire.DiaAvp>();
        avps.add(DiaWire.utf8(263, 0, false, imsi + "@" + host));
        avps.add(DiaWire.utf8(264, 0, false, System.getProperty("seeder.host", "seeder.example.org")));
        avps.add(DiaWire.utf8(296, 0, false, "epc.mnc01.mcc452.3gppnetwork.org"));
        avps.add(DiaWire.utf8(283, 0, false, "epc.mnc01.mcc452.3gppnetwork.org"));
        if (!destinationHost.isBlank()) {
            avps.add(DiaWire.utf8(293, 0, false, destinationHost));
        }
        avps.add(DiaWire.u32(258, 0, true, 16777251));
        avps.add(DiaWire.utf8(1, 0, true, imsi));
        pending.put(hbh, System.nanoTime());
        send(DiaWire.encode(DiaWire.FLAG_REQUEST | DiaWire.FLAG_PROXYABLE,
                CMD_ULR_ULA, 16777251, hbh, e2e, avps));
        sent.incrementAndGet();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    public static void main(String[] args) throws Exception {
        java.util.Map<String, String> opts = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opts.put(args[i].startsWith("--") ? args[i].substring(2) : args[i], args[i + 1]);
        }
        String host = opts.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(opts.getOrDefault("port", "3868"));
        int srcPort = Integer.parseInt(opts.getOrDefault("src-port", "38680"));
        int count = Integer.parseInt(opts.getOrDefault("count", "4"));
        double tps = Double.parseDouble(opts.getOrDefault("tps", "1"));
        long timeoutMs = Long.parseLong(opts.getOrDefault("timeout-ms", "5000"));
        String prefix = opts.getOrDefault("imsi-prefix", "45204020");
        String destHost = opts.getOrDefault("dest-host",
                "hss-a.epc.mnc01.mcc452.3gppnetwork.org");

        try (SctpSeederClient client = new SctpSeederClient(host, port, srcPort,
                tps, timeoutMs, prefix, destHost)) {
            Stats stats = client.run(count);
            System.out.println("=== Nextgen DRA SCTP smoke ===");
            System.out.printf("sent     : %d%nreceived : %d (%d timeouts)%n",
                    stats.sent(), stats.received(), stats.timeouts());
            System.out.printf("elapsed  : %d ms%nlast rc  : %d%n",
                    stats.elapsedMillis(), client.lastResultCode());
            System.exit(stats.received() == stats.sent()
                    && client.lastResultCode() == 2001 ? 0 : 1);
        }
    }
}
