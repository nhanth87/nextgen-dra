package et.elisa.dra.lab.testapp.diameter;

import java.security.SecureRandom;
import java.time.Instant;

import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.commands.DiameterMessage;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;

import et.elisa.dra.lab.testapp.HssSimulator;
import et.elisa.dra.lab.testapp.MessageLog;
import et.elisa.dra.lab.testapp.SubscriberState;

final class Answers {

    static final long UNABLE_TO_DELIVER = ResultCodes.DIAMETER_UNABLE_TO_DELIVER;
    static final long SUCCESS = ResultCodes.DIAMETER_SUCCESS;

    static final long ER_USER_UNKNOWN = 5001L;
    static final long ER_NO_SUBSCRIPTION = 5421L;

    static final long VENDOR_3GPP = 10415L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Answers() {
    }

    static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    static String sessionId(DiameterMessage message) {
        try {
            return message.getSessionId();
        } catch (DiameterException e) {
            return "?";
        }
    }

    static String usernameOf(DiameterMessage message) {
        try {
            String username = message.getUsername();
            return username == null ? "-" : username;
        } catch (DiameterException e) {
            return "-";
        }
    }

    static Instant now() {
        return Instant.now();
    }

    static MessageLog log(HssSimulator hss) {
        return hss.log();
    }

    static void received(HssSimulator hss, String command, DiameterMessage request,
            String details) {
        log(hss).add(new MessageLog.Entry(now(), "req", command,
                sessionId(request), "-", details));
    }

    static boolean serviceable(SubscriberState state) {
        return state.attached() && !state.barred();
    }
}
