package et.elisa.dra.bench;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class SeederClient implements AutoCloseable {

    private static final int CMD_CER_CEA = 257;
    private static final int CMD_ULR_ULA = 316;
    private static final int HISTO_BUCKETS = 40;
    private static final long BASE_NANOS = 100_000L;
    private static final double BUCKET_FACTOR = 1.2589;

    public record Stats(long sent, long received, long timeouts,
                        long p50Nanos, long p90Nanos, long p99Nanos,
                        long maxNanos, long elapsedMillis) {
    }

    private record Conn(Socket socket, DataInputStream in) {
    }

    private final String host;
    private final int port;
    private final int connections;
    private final double tps;
    private final long timeoutNanos;
    private final List<Conn> conns = new java.util.ArrayList<>();
    private final ConcurrentMap<Long, Long> pending = new ConcurrentHashMap<>();
    private final LongAdder sent = new LongAdder();
    private final LongAdder received = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder[] histogram = new LongAdder[HISTO_BUCKETS];
    private final AtomicLong hbhSeq = new AtomicLong(1);
    private final AtomicLong e2eSeq = new AtomicLong(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong maxNanos = new AtomicLong();
    private final String imsiPrefix;

    public SeederClient(String host, int port, int connections, double tps,
                        long timeoutMillis, String imsiPrefix) throws IOException {
        this.host = host;
        this.port = port;
        this.connections = connections;
        this.tps = tps;
        this.timeoutNanos = timeoutMillis * 1_000_000L;
        this.imsiPrefix = imsiPrefix;
        for (int i = 0; i < HISTO_BUCKETS; i++) {
            histogram[i] = new LongAdder();
        }
        for (int i = 0; i < connections; i++) {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream(), 65536));
            Conn conn = new Conn(socket, in);
            handshake(conn);
            conns.add(conn);
        }
    }

    public Stats run(int count) throws InterruptedException {
        running.set(true);
        Thread sweeper = Thread.ofVirtual().start(this::sweepLoop);
        long begin = System.nanoTime();
        Thread[] workers = new Thread[connections];
        int perConn = count / connections;
        int remainder = count % connections;
        for (int i = 0; i < connections; i++) {
            int quota = perConn + (i < remainder ? 1 : 0);
            double connTps = tps / connections;
            workers[i] = Thread.ofVirtual().start(() -> sendLoop(quota, connTps));
        }
        for (Thread w : workers) {
            w.join();
        }
        long deadline = System.nanoTime() + timeoutNanos + 2_000_000_000L;
        while (received.sum() + timeouts.sum() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        running.set(false);
        sweeper.join(1000);
        long elapsed = (System.nanoTime() - begin) / 1_000_000L;
        return new Stats(sent.sum(), received.sum(), timeouts.sum(),
                percentile(0.50), percentile(0.90), percentile(0.99),
                maxLatency(), elapsed);
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        for (Conn conn : conns) {
            conn.socket().close();
        }
    }

    private void sendLoop(int quota, double connTps) {
        long gapNanos = connTps <= 0 ? 0 : (long) (1_000_000_000L / connTps);
        long nextDeadline = System.nanoTime();
        try {
            for (int i = 0; i < quota; i++) {
                if (!running.get()) {
                    return;
                }
                if (gapNanos > 0) {
                    long now = System.nanoTime();
                    if (nextDeadline > now) {
                        java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nextDeadline - now);
                    }
                    nextDeadline += gapNanos;
                }
                sendOneUlr(pickConn());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Conn pickConn() {
        int idx = (int) (e2eSeq.get() % conns.size());
        return conns.get(idx);
    }

    private void sendOneUlr(Conn conn) throws IOException {
        long hbh = hbhSeq.incrementAndGet();
        long e2e = e2eSeq.getAndIncrement();
        String imsi = imsiPrefix + String.format("%08d", (int) (e2e % 100_000_000));
        byte[] frame = DiaWire.encode(DiaWire.FLAG_REQUEST | DiaWire.FLAG_PROXYABLE,
                CMD_ULR_ULA, 16777251, hbh, e2e,
                List.of(
                        DiaWire.utf8(263, 0, false, imsi + "@" + host),
                        DiaWire.utf8(264, 0, false, "seeder.example.org"),
                        DiaWire.utf8(296, 0, false, "epc.mnc01.mcc452.3gppnetwork.org"),
                        DiaWire.utf8(283, 0, false, "epc.mnc01.mcc452.3gppnetwork.org"),
                        DiaWire.u32(258, 0, true, 16777251),
                        DiaWire.utf8(1, 0, true, imsi)));
        pending.put(hbh, System.nanoTime());
        OutputStream out = conn.socket().getOutputStream();
        synchronized (conn.socket()) {
            out.write(frame);
            out.flush();
        }
        sent.increment();
    }

    private void readLoop(Conn conn) {
        try {
            while (running.get() && !conn.socket().isClosed()) {
                byte[] frame = DiaStream.readFrame(conn.in());
                if (frame == null) {
                    return;
                }
                DiaWire.Header header = DiaWire.decodeHeader(frame);
                if (header.isRequest()) {
                    continue;
                }
                Long start = pending.remove(header.hopByHopId());
                if (start != null) {
                    addSample(System.nanoTime() - start);
                    received.increment();
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void handshake(Conn conn) throws IOException {
        byte[] cer = DiaWire.encode(DiaWire.FLAG_REQUEST | DiaWire.FLAG_PROXYABLE,
                CMD_CER_CEA, 16777251, 1, 1,
                List.of(
                        DiaWire.utf8(264, 0, false, "seeder.example.org"),
                        DiaWire.utf8(296, 0, false, "epc.mnc01.mcc452.3gppnetwork.org"),
                        DiaWire.u32(258, 0, true, 16777251)));
        OutputStream out = conn.socket().getOutputStream();
        synchronized (conn.socket()) {
            out.write(cer);
            out.flush();
        }
        conn.socket().setSoTimeout(5000);
        try {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                byte[] frame = DiaStream.readFrame(conn.in());
                if (frame == null) {
                    throw new IOException("connection closed during CER/CEA");
                }
                DiaWire.Header header = DiaWire.decodeHeader(frame);
                if (!header.isRequest() && header.commandCode() == CMD_CER_CEA) {
                    if (DiaWire.resultCodeOf(frame) != 2001) {
                        throw new IOException("CEA not 2001");
                    }
                    conn.socket().setSoTimeout(0);
                    Thread.ofVirtual().start(() -> readLoop(conn));
                    return;
                }
            }
            throw new IOException("no CEA within timeout");
        } catch (IOException e) {
            conn.socket().close();
            throw e;
        }
    }

    private void sweepLoop() {
        while (running.get()) {
            long now = System.nanoTime();
            for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                if (now - entry.getValue() > timeoutNanos) {
                    it.remove();
                    timeouts.increment();
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void addSample(long latencyNanos) {
        updateMax(latencyNanos);
        int bucket = HISTO_BUCKETS - 1;
        for (int i = 0; i < HISTO_BUCKETS; i++) {
            if (latencyNanos < bucketEdge(i)) {
                bucket = i;
                break;
            }
        }
        histogram[bucket].increment();
    }

    private void updateMax(long value) {
        long current = maxNanos.get();
        while (value > current && !maxNanos.compareAndSet(current, value)) {
            current = maxNanos.get();
        }
    }

    private static long bucketEdge(int i) {
        return (long) Math.ceil(BASE_NANOS * Math.pow(BUCKET_FACTOR, i));
    }

    private long percentile(double q) {
        long total = totalSamples();
        if (total == 0) {
            return 0;
        }
        long target = (long) Math.ceil(total * q);
        long cumulative = 0;
        for (int i = 0; i < HISTO_BUCKETS; i++) {
            cumulative += histogram[i].sum();
            if (cumulative >= target) {
                return Math.min(bucketEdge(i), maxLatency());
            }
        }
        return maxLatency();
    }

    private long maxLatency() {
        return maxNanos.get();
    }

    private long totalSamples() {
        long total = 0;
        for (LongAdder bucket : histogram) {
            total += bucket.sum();
        }
        return total;
    }

}
