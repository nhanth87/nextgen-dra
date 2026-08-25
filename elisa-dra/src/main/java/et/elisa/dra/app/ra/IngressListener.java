package et.elisa.dra.app.ra;

@FunctionalInterface
public interface IngressListener {

    void onIngress(IngressEvent event);
}
