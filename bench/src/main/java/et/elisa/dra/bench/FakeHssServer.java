package et.elisa.dra.bench;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

public final class FakeHssServer implements AutoCloseable {

    public static final int CMD_CER_CEA = 257;
    public static final int CMD_DWR_DWA = 280;
    public static final int CMD_ULR_ULA = 316;
    private static final int RESULT_SUCCESS = 2001;

    private final ServerSocket serverSocket;
    private final long answerDelayMillis;
    private final int failurePercent;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final LongAdder requests = new LongAdder();
    private final LongAdder answers = new LongAdder();

    public FakeHssServer(int port, long answerDelayMillis, int failurePercent)
            throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.answerDelayMillis = answerDelayMillis;
        this.failurePercent = failurePercent;
    }

    public void start() {
        Thread.ofVirtual().name("fake-hss-accept").start(() -> {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    executor.submit(() -> serve(socket));
                } catch (IOException e) {
                    if (running.get()) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public long requests() {
        return requests.sum();
    }

    public long answers() {
        return answers.sum();
    }

    private void serve(Socket socket) {
        try (socket) {
            java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.BufferedInputStream(socket.getInputStream(), 65536));
            OutputStream out = socket.getOutputStream();
            while (running.get() && !socket.isClosed()) {
                byte[] frame = DiaStream.readFrame(in);
                if (frame == null) {
                    return;
                }
                DiaWire.Header header = DiaWire.decodeHeader(frame);
                if (!header.isRequest()) {
                    continue;
                }
                requests.increment();
                byte[] response = respond(header);
                if (response != null) {
                    out.write(response);
                    out.flush();
                    answers.increment();
                }
            }
        } catch (IOException ignored) {
        }
    }

    private byte[] respond(DiaWire.Header req) {
        if (req.commandCode() == CMD_ULR_ULA && answerDelayMillis > 0) {
            try {
                Thread.sleep(answerDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        int result = RESULT_SUCCESS;
        if (req.commandCode() == CMD_ULR_ULA
                && failurePercent > 0
                && java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < failurePercent) {
            result = 3002;
        }
        List<et.elisa.dra.core.wire.DiaAvp> avps = List.of(
                DiaWire.u32(268, 0, true, result),
                DiaWire.utf8(264, 0, false, "fake-hss.epc.mnc01.mcc452.3gppnetwork.org"),
                DiaWire.utf8(296, 0, false, "epc.mnc01.mcc452.3gppnetwork.org"));
        if (req.commandCode() == CMD_CER_CEA) {
            avps = append(avps, DiaWire.u32(258, 0, true, 16777251));
        }
        int flags = req.flags() & ~DiaWire.FLAG_REQUEST;
        return DiaWire.encode(flags, req.commandCode(), req.applicationId(),
                req.hopByHopId(), req.endToEndId(), avps);
    }

    private List<et.elisa.dra.core.wire.DiaAvp> append(
            List<et.elisa.dra.core.wire.DiaAvp> avps, et.elisa.dra.core.wire.DiaAvp extra) {
        var list = new java.util.ArrayList<>(avps);
        list.add(extra);
        return List.copyOf(list);
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        executor.close();
        serverSocket.close();
    }
}
