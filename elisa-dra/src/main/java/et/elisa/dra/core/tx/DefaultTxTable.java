package et.elisa.dra.core.tx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

public final class DefaultTxTable implements TxTable {

    private final ConcurrentMap<Long, TxState> table = new ConcurrentHashMap<>();
    private final LongAdder active = new LongAdder();

    @Override
    public void put(TxState tx) {
        if (table.putIfAbsent(tx.hbhOut, tx) == null) {
            active.increment();
        }
    }

    @Override
    public TxState byHbhOut(long hbhOut) {
        return table.get(hbhOut);
    }

    @Override
    public TxState remove(long hbhOut) {
        TxState removed = table.remove(hbhOut);
        if (removed != null) {
            active.decrement();
        }
        return removed;
    }

    @Override
    public int activeCount() {
        return (int) active.sum();
    }

    @Override
    public void forEachExpired(long nowMillis, Consumer<TxState> action) {
        List<TxState> expired = new ArrayList<>();
        for (var e : table.entrySet()) {
            TxState tx = e.getValue();
            if (tx.expired(nowMillis) && table.remove(e.getKey(), tx)) {
                active.decrement();
                expired.add(tx);
            }
        }
        for (TxState tx : expired) {
            action.accept(tx);
        }
    }
}
