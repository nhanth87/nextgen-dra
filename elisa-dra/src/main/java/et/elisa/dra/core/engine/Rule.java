package et.elisa.dra.core.engine;

public record Rule(String name, int priority, Matcher when, Action then) {
}
