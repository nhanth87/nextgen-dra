package et.elisa.dra.core.metrics;

public final class MetricsNames {

    public static final String TX_TOTAL = "dra_tx_total";
    public static final String ANSWER_2XX = "dra_answer_2xx_total";
    public static final String ANSWER_3XX = "dra_answer_3xx_total";
    public static final String ANSWER_4XX = "dra_answer_4xx_total";
    public static final String ANSWER_5XX = "dra_answer_5xx_total";
    public static final String FAILOVER_TOTAL = "dra_failover_total";
    public static final String THROTTLED_TOTAL = "dra_throttled_total";
    public static final String TX_ACTIVE = "dra_tx_active";
    public static final String BINDING_SIZE = "dra_binding_size";
    public static final String ROUTE_NOMATCH = "dra_route_nomatch_total";
    public static final String PEER_READY = "dra_peer_ready";
    public static final String AGENT_LATENCY = "dra_agent_latency";

    private MetricsNames() {
    }
}
