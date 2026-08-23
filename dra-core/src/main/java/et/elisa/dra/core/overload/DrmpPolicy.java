package et.elisa.dra.core.overload;

import java.util.Set;

public final class DrmpPolicy {

    public static final int MIN_PRIORITY = 0;
    public static final int MAX_PRIORITY = 15;
    public static final int DEFAULT_PRIORITY = 10;
    public static final int TIERS = MAX_PRIORITY + 1;

    public static final Set<Integer> CRITICAL_COMMANDS = Set.of(316, 318, 321, 323);

    private DrmpPolicy() {
    }

    public static int clamp(int drmpPriority) {
        if (drmpPriority < MIN_PRIORITY || drmpPriority > MAX_PRIORITY) {
            return DEFAULT_PRIORITY;
        }
        return drmpPriority;
    }

    public static int throttleOrder(int drmpPriority) {
        return MAX_PRIORITY - clamp(drmpPriority);
    }

    public static boolean isCriticalCommand(int cmdCode, Set<Integer> criticalSet) {
        return criticalSet != null && criticalSet.contains(cmdCode);
    }
}
