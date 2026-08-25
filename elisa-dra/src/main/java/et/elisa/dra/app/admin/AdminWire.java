package et.elisa.dra.app.admin;

import et.elisa.dra.core.cfg.RuleSet;
import et.elisa.dra.core.cfg.RuleSetHolder;
import et.elisa.dra.core.engine.RuleEngineImpl;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.util.function.Consumer;

@ApplicationScoped
@RegisterForReflection
public class AdminWire {

    @Produces
    @ApplicationScoped
    public RuleEngineImpl ruleEngine() {
        return new RuleEngineImpl();
    }

    @Produces
    @ApplicationScoped
    public RuleSetHolder ruleSetHolder(RuleEngineImpl engine) {
        Consumer<RuleSet> sink = engine::installRuleSet;
        return new RuleSetHolder(sink);
    }

    @Produces
    @ApplicationScoped
    public TelemetryPort telemetryPort() {
        return TelemetryPort.NOOP;
    }

}
