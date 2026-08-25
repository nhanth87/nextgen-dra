package et.elisa.dra.core.engine;

public sealed interface AvpOp {

    record AppendRouteRecord(String host) implements AvpOp {
    }

    record Set(int code, int vendorId, int typeIndex, String value) implements AvpOp {
    }

    record Drop(int code, int vendorId) implements AvpOp {
    }
}
