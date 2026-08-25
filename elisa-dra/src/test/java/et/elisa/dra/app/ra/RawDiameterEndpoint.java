package et.elisa.dra.app.ra;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

final class RawDiameterEndpoint implements AutoCloseable {

    static final int CMD_CER = 257;
    static final int CMD_DWR = 280;
    static final int CMD_ULR = 316;

    private static final int RESULT_SUCCESS = 2001;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final LongAdder requestsSeen = new LongAdder();
    private final ConcurrentLinkedQueue<byte[]> answersReceived = new ConcurrentLinkedQueue<>();
    private final List<Socket> connections = new ArrayList<>();
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private OutputStream out;

    private RawDiameterEndpoint() {
    }

    static RawDiameterEndpoint listen(int port) throws IOException {
        RawDiameterEndpoint ep = new RawDiameterEndpoint();
        ep.serverSocket = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
        Thread.ofVirtual().name("raw-dia-accept-" + port).start(ep::acceptLoop);
        return ep;
    }

    static RawDiameterEndpoint connect(String host, int remotePort, int bindSourcePort)
            throws IOException {
        RawDiameterEndpoint ep = new RawDiameterEndpoint();
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), bindSourcePort));
        socket.connect(new InetSocketAddress(host, remotePort), 10_000);
        ep.clientSocket = socket;
        synchronized (ep.connections) {
            ep.connections.add(socket);
            ep.out = socket.getOutputStream();
        }
        Thread.ofVirtual().name("raw-dia-client-" + bindSourcePort).start(() -> ep.readLoop(socket));
        return ep;
    }

    int localPort() {
        return serverSocket != null ? serverSocket.getLocalPort()
                : clientSocket != null ? clientSocket.getLocalPort() : -1;
    }

    long requestsSeen() {
        return requestsSeen.sum();
    }

    byte[] awaitAnswer(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            byte[] frame = answersReceived.poll();
            if (frame != null) {
                return frame;
            }
            Thread.sleep(20);
        }
        return null;
    }

    void send(byte[] frame) throws IOException {
        OutputStream o = out;
        if (o == null) {
            throw new IOException("not connected");
        }
        synchronized (o) {
            o.write(frame);
            o.flush();
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                synchronized (connections) {
                    connections.add(socket);
                }
                Thread.ofVirtual().name("raw-dia-serve").start(() -> readLoop(socket));
            } catch (IOException e) {
                if (running.get()) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void readLoop(Socket socket) {
        try (socket) {
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream(), 65_536));
            OutputStream sockOut = socket.getOutputStream();
            while (running.get() && !socket.isClosed()) {
                byte[] frame = readFrame(in);
                if (frame == null) {
                    return;
                }
                int flags = frame[4] & 0xFF;
                boolean isRequest = (flags & 0x80) != 0;
                if (isRequest) {
                    requestsSeen.increment();
                    byte[] answer = answerFor(frame);
                    synchronized (sockOut) {
                        sockOut.write(answer);
                        sockOut.flush();
                    }
                } else {
                    answersReceived.add(frame);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static byte[] answerFor(byte[] requestFrame) {
        int cmd = ((requestFrame[5] & 0xFF) << 16) | ((requestFrame[6] & 0xFF) << 8)
                | (requestFrame[7] & 0xFF);
        long hbh = u32(requestFrame, 12);
        long e2e = u32(requestFrame, 16);
        int appId = i32(requestFrame, 8);
        byte[] sessionId = sessionIdOf(requestFrame);

        List<byte[]> avps = new ArrayList<>();
        if (sessionId != null) {
            avps.add(rawAvp(263, sessionId));
        }
        avps.add(u32Avp(268, RESULT_SUCCESS));
        avps.add(utf8Avp(264, "raw-peer.epc.mnc01.mcc452.3gppnetwork.org"));
        avps.add(utf8Avp(296, "epc.mnc01.mcc452.3gppnetwork.org"));
        avps.add(u32Avp(278, 1));
        if (cmd == CMD_CER || cmd == CMD_ULR) {
            avps.add(u32Avp(258, appId != 0 ? appId : 16777251));
        }
        if (cmd == CMD_CER) {
            avps.add(addressAvp(257, new byte[]{127, 0, 0, 1}));
            avps.add(u32Avp(266, 0));
            avps.add(utf8Avp(269, "raw-test-peer"));
        }
        return encode((requestFrame[4] & 0x7F), cmd, appId, hbh, e2e, avps);
    }

    private static byte[] sessionIdOf(byte[] frame) {
        int pos = 20;
        while (pos + 8 <= frame.length) {
            int code = i32(frame, pos);
            boolean vendor = (frame[pos + 4] & 0x80) != 0;
            int len = ((frame[pos + 5] & 0xFF) << 16) | ((frame[pos + 6] & 0xFF) << 8)
                    | (frame[pos + 7] & 0xFF);
            int hdr = vendor ? 12 : 8;
            int dataLen = Math.max(0, len - hdr);
            if (code == 263 && dataLen > 0 && pos + hdr + dataLen <= frame.length) {
                byte[] data = new byte[dataLen];
                System.arraycopy(frame, pos + hdr, data, 0, dataLen);
                return data;
            }
            int padded = ((len + 3) & ~3);
            if (padded <= 0 || pos + padded > frame.length) {
                break;
            }
            pos += padded;
        }
        return null;
    }

    static byte[] encode(int flags, int commandCode, int applicationId,
                         long hopByHopId, long endToEndId, List<byte[]> avps) {
        int total = 20;
        for (byte[] a : avps) {
            total += a.length;
        }
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        for (byte[] a : avps) {
            body.writeBytes(a);
        }
        byte[] payload = body.toByteArray();
        byte[] out = new byte[total];
        out[0] = 0x01;
        out[1] = (byte) ((total >>> 16) & 0xFF);
        out[2] = (byte) ((total >>> 8) & 0xFF);
        out[3] = (byte) (total & 0xFF);
        out[4] = (byte) flags;
        out[5] = (byte) ((commandCode >>> 16) & 0xFF);
        out[6] = (byte) ((commandCode >>> 8) & 0xFF);
        out[7] = (byte) (commandCode & 0xFF);
        putInt(out, 8, applicationId);
        putLong(out, 12, hopByHopId);
        putLong(out, 16, endToEndId);
        System.arraycopy(payload, 0, out, 20, payload.length);
        return out;
    }

    static byte[] cerFrame(long hbh, long e2e, String originHost) {
        List<byte[]> avps = new ArrayList<>();
        avps.add(utf8Avp(263, "raw-cer-" + hbh));
        avps.add(utf8Avp(264, originHost));
        avps.add(utf8Avp(296, "epc.mnc01.mcc452.3gppnetwork.org"));
        avps.add(addressAvp(257, new byte[]{127, 0, 0, 1}));
        avps.add(u32Avp(266, 0));
        avps.add(utf8Avp(269, "raw-mme"));
        avps.add(u32Avp(278, 1));
        avps.add(u32Avp(258, 16777251));
        return encode(0x80, CMD_CER, 0, hbh, e2e, avps);
    }

    static byte[] ulrFrame(long hbh, long e2e, String imsi, String sessionId, String originHost) {
        List<byte[]> avps = new ArrayList<>();
        avps.add(utf8Avp(263, sessionId));
        avps.add(utf8Avp(264, originHost));
        avps.add(utf8Avp(296, "epc.mnc01.mcc452.3gppnetwork.org"));
        avps.add(utf8Avp(283, "epc.mnc01.mcc452.3gppnetwork.org"));
        avps.add(utf8Avp(1, imsi));
        return encode(0xC0, CMD_ULR, 16777251, hbh, e2e, avps);
    }

    static byte[] u32Avp(int code, long value) {
        return rawAvp(code, new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value});
    }

    static byte[] utf8Avp(int code, String value) {
        return rawAvp(code, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static byte[] addressAvp(int code, byte[] ipv4) {
        byte[] data = new byte[ipv4.length + 2];
        data[0] = 0x00;
        data[1] = 0x01;
        System.arraycopy(ipv4, 0, data, 2, ipv4.length);
        return rawAvp(code, data);
    }

    private static byte[] rawAvp(int code, byte[] data) {
        int len = 8 + data.length;
        int pad = (len + 3) & ~3;
        byte[] out = new byte[pad];
        putInt(out, 0, code);
        out[4] = 0x40;
        out[5] = (byte) ((len >>> 16) & 0xFF);
        out[6] = (byte) ((len >>> 8) & 0xFF);
        out[7] = (byte) (len & 0xFF);
        System.arraycopy(data, 0, out, 8, data.length);
        return out;
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    private static void putLong(byte[] b, int off, long v) {
        putInt(b, off, (int) v);
    }

    private static int i32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static long u32(byte[] b, int off) {
        return i32(b, off) & 0xFFFFFFFFL;
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        DataInputStream data = in instanceof DataInputStream d ? d
                : new DataInputStream(new BufferedInputStream(in));
        byte[] header = new byte[20];
        try {
            data.readFully(header);
        } catch (IOException e) {
            return null;
        }
        int length = ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8)
                | (header[3] & 0xFF);
        if (length < 20 || length > 4_096_000) {
            throw new IOException("bad diameter length " + length);
        }
        byte[] frame = new byte[length];
        System.arraycopy(header, 0, frame, 0, 20);
        data.readFully(frame, 20, length - 20);
        return frame;
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        synchronized (connections) {
            for (Socket s : connections) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            connections.clear();
        }
        if (serverSocket != null) {
            serverSocket.close();
        }
    }
}
