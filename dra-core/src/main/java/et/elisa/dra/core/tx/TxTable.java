package et.elisa.dra.core.tx;

import java.util.function.Consumer;

public interface TxTable {

    void put(TxState tx);

    TxState byHbhOut(long hbhOut);

    TxState remove(long hbhOut);

    int activeCount();

    void forEachExpired(long nowMillis, Consumer<TxState> action);
}
