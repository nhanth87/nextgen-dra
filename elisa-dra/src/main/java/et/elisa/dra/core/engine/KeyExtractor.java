package et.elisa.dra.core.engine;

import et.elisa.dra.core.wire.DiaMsg;

import java.util.Map;

public interface KeyExtractor {

    Map<String, String> extract(DiaMsg msg);
}
