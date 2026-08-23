package et.elisa.dra.core.common;

import java.util.Set;

public final class RetryableCommands {

    public static final int CMD_ULR = 316;
    public static final int CMD_CLR = 317;
    public static final int CMD_AIR = 318;
    public static final int CMD_DSR_DSA = 320;
    public static final int CMD_PUR = 321;
    public static final int CMD_NOR = 323;
    public static final int CMD_ECR = 324;
    public static final int CMD_CCR = 272;

    public static final Set<Integer> DEFAULT_RETRYABLE =
            Set.of(CMD_ULR, CMD_AIR, CMD_PUR, CMD_NOR);

    private RetryableCommands() {
    }
}
