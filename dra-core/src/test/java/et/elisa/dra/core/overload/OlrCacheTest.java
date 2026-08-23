package et.elisa.dra.core.overload;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OlrCacheTest {

    @Test
    void updateAndQueryRoundTrip() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-23T00:00:00Z"));
        OlrCache cache = new OlrCache(now::get);
        cache.update("hss-a", 7, DoicAvps.REPORT_HOST, 40, now.get().plusSeconds(30));
        assertEquals(40, cache.reductionPercentFor("hss-a"));
        assertEquals(1, cache.activeReports());
        assertEquals(1, cache.updatesAcceptedCount());
    }

    @Test
    void expiredReportReturnsZeroAndPurges() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-23T00:00:00Z"));
        OlrCache cache = new OlrCache(now::get);
        cache.update("hss-a", 7, DoicAvps.REPORT_HOST, 40, now.get().plusSeconds(30));
        now.set(now.get().plusSeconds(31));
        assertEquals(0, cache.reductionPercentFor("hss-a"));
        assertEquals(0, cache.activeReports());
        assertEquals(1, cache.expiredPurgedCount());
    }

    @Test
    void zeroValidityExpiresImmediately() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-23T00:00:00Z"));
        OlrCache cache = new OlrCache(now::get);
        cache.update("hss-a", 9, DoicAvps.REPORT_REALM, 90, now.get());
        assertEquals(0, cache.reductionPercentFor("hss-a"));
    }

    @Test
    void staleLowerSequenceIgnored() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-23T00:00:00Z"));
        OlrCache cache = new OlrCache(now::get);
        cache.update("hss-a", 10, DoicAvps.REPORT_HOST, 80, now.get().plusSeconds(60));
        cache.update("hss-a", 5, DoicAvps.REPORT_HOST, 20, now.get().plusSeconds(60));
        assertEquals(80, cache.reductionPercentFor("hss-a"));
        assertEquals(1, cache.staleIgnoredCount());
    }

    @Test
    void equalSequenceIgnored() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-23T00:00:00Z"));
        OlrCache cache = new OlrCache(now::get);
        cache.update("hss-a", 10, DoicAvps.REPORT_HOST, 80, now.get().plusSeconds(60));
        cache.update("hss-a", 10, DoicAvps.REPORT_HOST, 20, now.get().plusSeconds(60));
        assertEquals(80, cache.reductionPercentFor("hss-a"));
    }

    @Test
    void higherSequenceUpdates() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-23T00:00:00Z"));
        OlrCache cache = new OlrCache(now::get);
        cache.update("hss-a", 10, DoicAvps.REPORT_HOST, 80, now.get().plusSeconds(60));
        cache.update("hss-a", 11, DoicAvps.REPORT_HOST, 30, now.get().plusSeconds(60));
        assertEquals(30, cache.reductionPercentFor("hss-a"));
        assertEquals(2, cache.updatesAcceptedCount());
    }

    @Test
    void sequenceRolloverAccepted() {
        assertTrue(OlrCache.acceptsSeq(-1L, 5));
        assertTrue(OlrCache.acceptsSeq(-1L, 0));
        assertTrue(OlrCache.acceptsSeq(Long.MAX_VALUE, Long.MIN_VALUE + 1000));
        assertTrue(!OlrCache.acceptsSeq(100L, 99L));
        assertTrue(!OlrCache.acceptsSeq(42L, 42L));
        assertTrue(OlrCache.acceptsSeq(42L, 43L));
    }

    @Test
    void maxActiveReductionAcrossPeers() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-23T00:00:00Z"));
        OlrCache cache = new OlrCache(now::get);
        cache.update("hss-a", 1, DoicAvps.REPORT_HOST, 20, now.get().plusSeconds(60));
        cache.update("hss-b", 1, DoicAvps.REPORT_HOST, 70, now.get().plusSeconds(60));
        assertEquals(70, cache.maxActiveReduction());
        now.set(now.get().plusSeconds(120));
        assertEquals(0, cache.maxActiveReduction());
    }

    @Test
    void reductionClampedToValidRange() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-23T00:00:00Z"));
        OlrCache cache = new OlrCache(now::get);
        cache.update("hss-a", 1, DoicAvps.REPORT_HOST, 250, now.get().plusSeconds(60));
        assertEquals(100, cache.reductionPercentFor("hss-a"));
        cache.update("hss-b", 1, DoicAvps.REPORT_HOST, -5, now.get().plusSeconds(60));
        assertEquals(0, cache.reductionPercentFor("hss-b"));
    }
}
