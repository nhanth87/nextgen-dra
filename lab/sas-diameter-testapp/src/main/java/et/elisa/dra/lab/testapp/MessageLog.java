package et.elisa.dra.lab.testapp;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class MessageLog {

    public static final int CAPACITY = 500;

    public static final String SUCCESS_RESULT = "2001";
    private static final String REQ_DIRECTION = "req";

    public record Entry(Instant time, String direction, String command,
                        String sessionId, String result, String details) {

        @Override
        public String toString() {
            return time + " " + direction + " " + command + " session=" + sessionId
                    + " result=" + result + (details.isEmpty() ? "" : " " + details);
        }
    }

    private final Deque<Entry> ring = new ArrayDeque<>(CAPACITY);
    private final LongAdder requestsTotal = new LongAdder();
    private final LongAdder answersTotal = new LongAdder();
    private final LongAdder errorsTotal = new LongAdder();
    private final AtomicLong lastMessageNanos = new AtomicLong(0L);

    public synchronized void add(Entry entry) {
        while (ring.size() >= CAPACITY) {
            ring.pollFirst();
        }
        ring.addLast(entry);
        if (REQ_DIRECTION.equals(entry.direction())) {
            requestsTotal.increment();
        } else if (SUCCESS_RESULT.equals(entry.result())) {
            answersTotal.increment();
        } else {
            errorsTotal.increment();
        }
        lastMessageNanos.set(System.nanoTime());
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(ring);
    }

    public synchronized void clear() {
        ring.clear();
    }

    public long requestsTotal() {
        return requestsTotal.sum();
    }

    public long answersTotal() {
        return answersTotal.sum();
    }

    public long errorsTotal() {
        return errorsTotal.sum();
    }

    public long lastMessageAgeMillis() {
        long nanos = lastMessageNanos.get();
        return nanos == 0L ? -1L : Math.max(0L, (System.nanoTime() - nanos) / 1_000_000L);
    }
}
