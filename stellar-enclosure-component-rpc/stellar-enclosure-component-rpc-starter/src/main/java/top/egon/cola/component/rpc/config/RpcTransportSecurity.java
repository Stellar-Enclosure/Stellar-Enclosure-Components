package top.egon.cola.component.rpc.config;

/** The sole internal application transport supported by the Cilium deployment. */
public final class RpcTransportSecurity {

    public static final String H2C = "h2c";
    public static final int GAMMA_FALLBACK_PORT = 19099;

    private RpcTransportSecurity() {
    }
}
