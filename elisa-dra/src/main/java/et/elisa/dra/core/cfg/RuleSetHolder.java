package et.elisa.dra.core.cfg;

import et.elisa.dra.core.lb.LbStrategy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class RuleSetHolder {

    private record State(int version, RuleSetFile file, RuleSet runtime) {
        static final State EMPTY = new State(0, new RuleSetFile(0, null, Map.of(), List.of()),
                new RuleSet(0, "dra.local", java.util.Set.of(), List.of(), Map.of()));
    }

    private final JsonRuleSetLoader loader = new JsonRuleSetLoader();
    private final DraConfigValidator validator = new DraConfigValidator();
    private final Consumer<RuleSet> sink;
    private final AtomicReference<State> ref = new AtomicReference<>(State.EMPTY);

    public RuleSetHolder() {
        this(rs -> {
        });
    }

    public RuleSetHolder(Consumer<RuleSet> sink) {
        this.sink = sink;
    }

    public synchronized List<String> applyCandidate(String json) {
        RuleSetFile file;
        try {
            file = loader.parse(json);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return List.of("json parse error: " + firstLine(msg));
        }
        State current = ref.get();
        List<String> errors = validator.validate(file, current.version());
        if (!errors.isEmpty()) {
            return errors;
        }
        RuleSet runtime = compile(file);
        ref.set(new State(file.version(), file, runtime));
        sink.accept(runtime);
        return List.of();
    }

    private static RuleSet compile(RuleSetFile file) {
        List<et.elisa.dra.core.engine.Rule> rules = file.rules().stream()
                .map(r -> new et.elisa.dra.core.engine.Rule(r.name(), r.priority(),
                        r.when().matcher(), r.then().action()))
                .toList();
        Map<String, RuleSet.GroupSpec> groups = new java.util.LinkedHashMap<>();
        if (file.peerGroups() != null) {
            for (var e : file.peerGroups().entrySet()) {
                LbStrategy strategy;
                try {
                    strategy = LbStrategy.valueOf(e.getValue().lb().toUpperCase(Locale.ROOT)
                            .replace('-', '_'));
                } catch (RuntimeException ex) {
                    continue;
                }
                RuleSetFile.Failover f = e.getValue().failover();
                boolean failoverEnabled = f == null || f.enabled() == null || f.enabled();
                int maxRetries = f == null || f.maxRetries() == null ? 1 : f.maxRetries();
                groups.put(e.getKey(), new RuleSet.GroupSpec(e.getKey(), strategy,
                        e.getValue().peers(), failoverEnabled, maxRetries));
            }
        }
        return new RuleSet(file.version(),
                file.self() == null ? "dra.local" : file.self().originHost(),
                file.self() == null || file.self().realms() == null
                        ? Set.of() : Set.copyOf(file.self().realms()),
                rules, groups);
    }

    public RuleSet runtime() {
        return ref.get().runtime();
    }

    public RuleSetFile file() {
        return ref.get().file();
    }

    public int version() {
        return ref.get().version();
    }

    public String currentJson() {
        try {
            return loader.toJson(ref.get().file());
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
