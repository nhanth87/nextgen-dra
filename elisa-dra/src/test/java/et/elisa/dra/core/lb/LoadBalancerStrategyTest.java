package et.elisa.dra.core.lb;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadBalancerStrategyTest {

    private static PeerHandle p(String id, int weight, int outstanding, Integer load) {
        return new PeerHandle(id, weight, outstanding, load);
    }

    @Test
    void rrWheelCyclesEvenly() {
        RrLoadBalancer rr = new RrLoadBalancer();
        List<PeerHandle> two = List.of(p("a", 1, 0, null), p("b", 1, 0, null));
        int a = 0;
        int b = 0;
        for (int i = 0; i < 100; i++) {
            if (rr.choose(two, null).peerId().equals("a")) {
                a++;
            } else {
                b++;
            }
        }
        assertEquals(50, a);
        assertEquals(50, b);
    }

    @Test
    void weightedRrDistributes70Over30WithinTolerance() {
        WeightedRrLoadBalancer wrr = new WeightedRrLoadBalancer();
        List<PeerHandle> pool = List.of(p("heavy", 70, 0, null), p("light", 30, 0, null));
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        counts.put("heavy", 0);
        counts.put("light", 0);
        for (int i = 0; i < 10_000; i++) {
            String id = wrr.choose(pool, null).peerId();
            counts.merge(id, 1, Integer::sum);
        }
        int heavy = counts.get("heavy");
        assertTrue(Math.abs(heavy - 7000) <= 500,
                "expected ~7000 got " + heavy);
        assertEquals(3000, counts.get("light"));
    }

    @Test
    void weightedRrZeroWeightNeverChosenUnlessAlone() {
        WeightedRrLoadBalancer wrr = new WeightedRrLoadBalancer();
        List<PeerHandle> pool = List.of(p("hot", 90, 0, null), p("cold", 0, 0, null));
        for (int i = 0; i < 200; i++) {
            assertEquals("hot", wrr.choose(pool, null).peerId());
        }
        List<PeerHandle> solo = List.of(p("only", 0, 0, null));
        assertEquals("only", wrr.choose(solo, null).peerId());
    }

    @Test
    void leastOutstandingPicksMinAndBreaksTiesByWheel() {
        LeastOutstandingLoadBalancer lo = new LeastOutstandingLoadBalancer();
        List<PeerHandle> pool = List.of(p("busy", 1, 42, null),
                p("free", 1, 7, null), p("mid", 1, 20, null));
        for (int i = 0; i < 10; i++) {
            assertEquals("free", lo.choose(pool, null).peerId());
        }
        List<PeerHandle> tied = List.of(p("t1", 1, 5, null), p("t2", 1, 5, null));
        var picks = new ArrayList<String>();
        for (int i = 0; i < 4; i++) {
            picks.add(lo.choose(tied, null).peerId());
        }
        assertEquals("t1", picks.get(0));
        assertEquals("t2", picks.get(1));
        assertEquals("t1", picks.get(2));
    }

    @Test
    void loadAwareWeightsFollowReportedLoad() {
        LoadAwareLoadBalancer la = new LoadAwareLoadBalancer();
        List<PeerHandle> pool = List.of(p("low", 50, 0, 10), p("high", 50, 0, 90));
        int lowPicks = 0;
        for (int i = 0; i < 1_000; i++) {
            if (la.choose(pool, null).peerId().equals("low")) {
                lowPicks++;
            }
        }
        assertTrue(lowPicks > 800, "load-aware should prefer low-load host: " + lowPicks);

        LoadAwareLoadBalancer fallback = new LoadAwareLoadBalancer();
        List<PeerHandle> noReports = List.of(p("w70", 70, 0, null), p("w30", 30, 0, null));
        int w70 = 0;
        for (int i = 0; i < 2_000; i++) {
            if (fallback.choose(noReports, null).peerId().equals("w70")) {
                w70++;
            }
        }
        assertTrue(Math.abs(w70 - 1400) <= 120, "null loadValue must fall back to WRR: " + w70);
    }

    @Test
    void preferredPeerHonoredAcrossStrategies() {
        List<PeerHandle> pool = List.of(p("a", 70, 0, null), p("b", 30, 0, null));
        assertEquals("b", new RrLoadBalancer().choose(pool, "b").peerId());
        assertEquals("b", new WeightedRrLoadBalancer().choose(pool, "b").peerId());
        assertEquals("a", new LeastOutstandingLoadBalancer().choose(pool, "a").peerId());
        assertEquals("b", new LoadAwareLoadBalancer().choose(pool, "b").peerId());
    }

    @Test
    void smoothPickFallsBackToWheelWhenAllWeightsZero() {
        ConcurrentHashMap<String, Integer> currents = new ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicInteger wheel = new java.util.concurrent.atomic.AtomicInteger();
        List<PeerHandle> pool = List.of(p("x", 0, 0, null), p("y", 0, 0, null));
        assertEquals("x", LoadBalancers.smoothPick(pool, PeerHandle::weight, currents, wheel).peerId());
        assertEquals("y", LoadBalancers.smoothPick(pool, PeerHandle::weight, currents, wheel).peerId());
    }

    @Test
    void groupRuntimeKeepsCandidatesSnapshot() {
        GroupRuntime g = new GroupRuntime("pool", LbStrategy.WEIGHTED_RR, true, 2);
        assertTrue(g.candidates().isEmpty());
        g.updateCandidates(List.of(p("hss-a", 70, 0, null)));
        assertEquals(List.of(p("hss-a", 70, 0, null)), g.candidates());
        assertEquals("pool", g.groupId());
        assertTrue(g.failoverEnabled());
        assertEquals(2, g.maxRetries());
        assertTrue(g.sameShape(LbStrategy.WEIGHTED_RR, true, 2));
        assertTrue(!g.sameShape(LbStrategy.RR, true, 2));
    }
}
