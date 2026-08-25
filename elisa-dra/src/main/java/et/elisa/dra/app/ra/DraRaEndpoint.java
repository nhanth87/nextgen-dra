package et.elisa.dra.app.ra;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.SleeEvent;

import et.elisa.dra.core.peer.DraRaPort;
import et.elisa.dra.core.wire.DiaMsg;
import et.elisa.dra.app.ra.IngressEvent;
import et.elisa.dra.app.ra.IngressListener;
import et.elisa.dra.app.ra.IngressRequest;
import et.elisa.dra.app.ra.IngressAnswer;

public final class DraRaEndpoint implements RaEndpointPort, RaCommandPort {

    public static final String RA_NAME = "dra-diameter-ra";

    @FunctionalInterface
    public interface IngressTrigger {
        void onIngress(IngressEvent event);
    }

    private final DraRaPort port;
    private volatile IngressTrigger trigger;
    private volatile boolean started;

    public DraRaEndpoint(DraRaPort port) {
        this.port = port;
    }

    @Override
    public String getRaName() {
        return RA_NAME;
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        this.trigger = event -> fire(bootstrap, event);
        started = true;
    }

    private static void fire(RaBootstrapPort bootstrap, IngressEvent event) {
        switch (event) {
            case IngressRequest req -> fireEvent(bootstrap,
                    new DraRequestEvent(req.msg(), req.ingressPeerId()), req.msg());
            case IngressAnswer ans -> fireEvent(bootstrap,
                    new DraAnswerEvent(ans.msg(), ans.egressPeerId()), ans.msg());
        }
    }

    private static void fireEvent(RaBootstrapPort bootstrap, SleeEvent event, DiaMsg msg) {
        ActivityHandle handle = bootstrap.createActivityHandle(
                activityKey(msg.sessionId(), msg.hopByHopId()));
        bootstrap.fireEvent(event, handle, null);
    }

    private static String activityKey(String sessionId, long hopByHopId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return "dra-sess/" + sessionId;
        }
        return "dra-hbh/" + Long.toHexString(hopByHopId);
    }

    /** Production + test entry: feed an RA-level ingress event into the container. */
    public void onRaIngress(IngressEvent event) {
        IngressTrigger t = trigger;
        if (t != null) {
            t.onIngress(event);
        }
    }

    @Override
    public void deactivate() {
        started = false;
        trigger = null;
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof DraSendCommand send) {
            if (send.msg().isRequest()) {
                port.sendToPeer(send.peerId(), send.msg());
            } else {
                port.sendAnswerOnLink(send.peerId(), send.msg());
            }
            return;
        }
        throw new IllegalArgumentException("unsupported outbound command: "
                + (command == null ? "null" : command.getClass().getName()));
    }

    public boolean isStarted() {
        return started;
    }
}
