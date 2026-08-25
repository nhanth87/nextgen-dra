package et.elisa.dra.core.common;

public final class DraResultCodes {

    public static final int SUCCESS = 2001;
    public static final int LIMITED_SUCCESS = 2002;
    public static final int UNABLE_TO_DELIVER = 3002;
    public static final int REALM_NOT_SERVED = 3003;
    public static final int TOO_BUSY = 3004;
    public static final int LOOP_DETECTED = 3005;
    public static final int REDIRECT_INDICATION = 3006;
    public static final int APPLICATION_UNSUPPORTED = 3007;
    public static final int UNABLE_TO_COMPLY = 5012;

    private DraResultCodes() {
    }
}
