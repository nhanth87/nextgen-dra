package et.elisa.dra.bench;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class DiaStream {

    private DiaStream() {
    }

    public static byte[] readFrame(InputStream in) throws IOException {
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
}
