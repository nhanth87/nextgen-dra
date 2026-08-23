package et.elisa.dra.app.sbbs.relay;

import et.elisa.dra.core.common.AvpCodes;
import et.elisa.dra.core.engine.AvpOp;
import et.elisa.dra.core.wire.DiaAvp;
import et.elisa.dra.core.wire.DiaMsg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvpOpsTest {

    private static DiaMsg msg(List<DiaAvp> avps) {
        return new DiaMsg(1, DiaMsg.FLAG_REQUEST, 316, 16777216, 1, 1, "s",
                "o", "r", null, "r", 0, avps);
    }

    @Test
    void appendRouteRecordAdds282() {
        DiaMsg out = AvpOps.apply(msg(List.of()), List.of(new AvpOp.AppendRouteRecord("dra-01")));
        assertEquals(Optional.of("dra-01"), AvpOps.firstUtf8(out, AvpCodes.ROUTE_RECORD));
        assertEquals(1, out.avps().size());
    }

    @Test
    void setReplacesExistingAvpOfSameCodeVendor() {
        DiaMsg base = msg(List.of(DiaAvp.utf8(AvpCodes.DESTINATION_HOST, "old"), DiaAvp.utf8(1, "keep")));
        DiaMsg out = AvpOps.apply(base, List.of(
                new AvpOp.Set(AvpCodes.DESTINATION_HOST, 0, DiaAvp.TYPE_UTF8, "new-host")));
        assertEquals(Optional.of("new-host"), AvpOps.firstUtf8(out, AvpCodes.DESTINATION_HOST));
        assertEquals(2, out.avps().size());
        assertEquals(Optional.of("keep"), AvpOps.firstUtf8(out, 1));
    }

    @Test
    void setUint32ParsesNumericValue() {
        DiaMsg out = AvpOps.apply(msg(List.of()), List.of(
                new AvpOp.Set(AvpCodes.RESULT_CODE, 0, DiaAvp.TYPE_UINT32, "2001")));
        assertEquals(Optional.of(2001L), AvpOps.firstUint32(out, AvpCodes.RESULT_CODE));
    }

    @Test
    void dropRemovesOnlyMatchingCodeVendor() {
        DiaMsg base = msg(List.of(DiaAvp.utf8(33, "x"), DiaAvp.utf8(34, "y")));
        DiaMsg out = AvpOps.apply(base, List.of(new AvpOp.Drop(33, 0)));
        assertTrue(AvpOps.firstUtf8(out, 33).isEmpty());
        assertEquals(Optional.of("y"), AvpOps.firstUtf8(out, 34));
    }

    @Test
    void dropUnknownCodeIsNoOpReturningSameInstance() {
        DiaMsg base = msg(List.of(DiaAvp.utf8(1, "a")));
        DiaMsg out = AvpOps.drop(base, 9999, 0);
        assertEquals(base, out);
    }

    @Test
    void withDestinationHostRewritesFieldAndUpsertsAvp() {
        DiaMsg base = msg(List.of(DiaAvp.utf8(AvpCodes.DESTINATION_HOST, "pseudo.example")));
        DiaMsg out = AvpOps.withDestinationHost(base, "real.example");
        assertEquals("real.example", out.destinationHost());
        assertEquals(1, out.avps().size());
        assertEquals(Optional.of("real.example"), AvpOps.firstUtf8(out, AvpCodes.DESTINATION_HOST));
    }

    @Test
    void drmpPriorityReadsLongStringAndDefault() {
        assertEquals(3, AvpOps.drmpPriority(msg(List.of(DiaAvp.uint32(AvpCodes.DRMP, 3)))));
        assertEquals(5, AvpOps.drmpPriority(msg(List.of(DiaAvp.utf8(AvpCodes.DRMP, "5")))));
        assertEquals(10, AvpOps.drmpPriority(msg(List.of())));
        assertEquals(10, AvpOps.drmpPriority(msg(List.of(DiaAvp.utf8(AvpCodes.DRMP, "bogus")))));
    }

    @Test
    void stringsOfCollectsAllRouteRecords() {
        DiaMsg base = msg(List.of(
                DiaAvp.utf8(AvpCodes.ROUTE_RECORD, "hop-1"),
                DiaAvp.utf8(1, "other"),
                DiaAvp.utf8(AvpCodes.ROUTE_RECORD, "hop-2")));
        assertEquals(List.of("hop-1", "hop-2"), AvpOps.stringsOf(base, AvpCodes.ROUTE_RECORD));
    }
}
