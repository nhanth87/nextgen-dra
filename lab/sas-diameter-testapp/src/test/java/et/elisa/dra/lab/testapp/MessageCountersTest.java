package et.elisa.dra.lab.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class MessageCountersTest {

    @Test
    void countersRiseOnHandlerLoggingPath() {
        MessageLog log = new MessageLog();
        assertEquals(0L, log.requestsTotal());
        assertEquals(0L, log.answersTotal());
        assertEquals(0L, log.errorsTotal());

        log.add(new MessageLog.Entry(Instant.now(), "req", "ULR", "s1", "-", "user=a"));
        log.add(new MessageLog.Entry(Instant.now(), "ans", "ULA", "s1", "2001", "ok"));
        log.add(new MessageLog.Entry(Instant.now(), "req", "AIR", "s2", "-", "user=b"));
        log.add(new MessageLog.Entry(Instant.now(), "ans", "AIA", "s2", "5001", "user unknown"));

        assertEquals(2L, log.requestsTotal());
        assertEquals(1L, log.answersTotal());
        assertEquals(1L, log.errorsTotal());
    }

    @Test
    void nonSuccessAnswersCountAsErrors() {
        MessageLog log = new MessageLog();
        for (String code : List.of("3002", "5001", "5421", "5030")) {
            log.add(new MessageLog.Entry(Instant.now(), "ans", "X", "s", code, "err"));
        }
        assertEquals(0L, log.answersTotal());
        assertEquals(4L, log.errorsTotal());
    }

    @Test
    void lastMessageAgeMillisTracksFreshness() throws Exception {
        MessageLog log = new MessageLog();
        assertEquals(-1L, log.lastMessageAgeMillis());
        log.add(new MessageLog.Entry(Instant.now(), "req", "ULR", "s", "-", "d"));
        long age = log.lastMessageAgeMillis();
        assertTrue(age >= 0);
        Thread.sleep(15);
        assertTrue(log.lastMessageAgeMillis() >= age);
    }

    @Test
    void clearKeepsCumulativeTotalsForOracle() {
        MessageLog log = new MessageLog();
        log.add(new MessageLog.Entry(Instant.now(), "req", "CCR", "s", "-", "d"));
        log.add(new MessageLog.Entry(Instant.now(), "ans", "CCA", "s", "2001", "d"));
        log.clear();
        assertTrue(log.snapshot().isEmpty());
        assertEquals(1L, log.requestsTotal());
        assertEquals(1L, log.answersTotal());
    }
}
