package et.elisa.dra.ra;

@FunctionalInterface
public interface IngressListener {

    void onIngress(IngressEvent event);
}
