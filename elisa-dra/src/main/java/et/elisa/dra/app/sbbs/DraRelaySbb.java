package et.elisa.dra.app.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;

import et.elisa.dra.app.ra.DraAnswerEvent;
import et.elisa.dra.app.ra.DraRequestEvent;
import et.elisa.dra.app.sbbs.relay.RelayCore;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SbbAnnotation(name = "DraRelaySbb", vendor = "et.elisa", version = "1.0")
public abstract class DraRelaySbb extends CmpBackedSbb implements SleeEventHandler {

    private final RelayCore core;

    @InjectRa(name = "dra-diameter-ra")
    private volatile RaCommandPort diameterRa;

    public DraRelaySbb() {
        this(null);
    }

    public DraRelaySbb(RelayCore core) {
        this.core = core;
    }

    @CmpField("sessionId")
    public abstract String getSessionId();

    @CmpField("sessionId")
    public abstract void setSessionId(String sessionId);

    @Override
    public void sbbCreate() {
        bindRa();
    }

    @Override
    public void sbbPostCreate() {
        bindRa();
    }

    @Override
    public void sbbActivate() {
        bindRa();
    }

    @Override
    public void sbbPassivate() {
    }

    @Override
    public void sbbRemove() {
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        bindRa();
        if (core == null) {
            return;
        }
        switch (event) {
            case DraRequestEvent req -> {
                setSessionId(req.msg().sessionId());
                core.onRequest(req.peerId(), req.msg());
            }
            case DraAnswerEvent ans -> {
                setSessionId(ans.msg().sessionId());
                core.onAnswer(ans.msg(), ans.peerId());
            }
            default -> {
            }
        }
    }

    private void bindRa() {
        if (diameterRa != null && core != null) {
            core.bindCommandPort(diameterRa);
        }
    }

    public static final class $Concrete extends DraRelaySbb {
        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(RelayCore core) {
            super(core);
        }

        @Override
        public String getSessionId() {
            return str("sessionId");
        }

        @Override
        public void setSessionId(String v) {
            put("sessionId", v);
            write("setSessionId", String.class, v);
        }

        private String str(String key) {
            Object v = local.get(key);
            return v instanceof String s ? s : null;
        }

        private void put(String key, Object value) {
            if (value == null) {
                local.remove(key);
            } else {
                local.put(key, value);
            }
        }

        private void write(String setter, Class<?> type, Object value) {
            try {
                cmpWrite(method(setter, type), value);
            } catch (IllegalStateException ignored) {
                // local map when container store unbound
            }
        }

        private static Method method(String name, Class<?>... params) {
            try {
                return DraRelaySbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
