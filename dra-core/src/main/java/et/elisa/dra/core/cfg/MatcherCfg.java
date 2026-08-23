package et.elisa.dra.core.cfg;

import et.elisa.dra.core.engine.Matcher;

public record MatcherCfg(Matcher matcher) {

    public static final MatcherCfg ALWAYS_TRUE = new MatcherCfg(Matcher.Always.TRUE);
}
