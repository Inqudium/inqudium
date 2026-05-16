package eu.inqudium.pipeline;

/**
 * Classpath-presence probe for the proxy integration module
 * ({@code inqudium-proxy}). Following ADR-037 §4, the probe uses a
 * string-based {@link Class#forName(String, boolean, ClassLoader)}
 * call with {@code initialize=false}, so the probed class is not
 * loaded and initialised — only its reachability is tested.
 *
 * <p>The class itself is always loaded, regardless of whether the
 * probed module is on the classpath. Carries no class-literal
 * reference to anything inside {@code inqudium-proxy}; the only
 * coupling is the string name of the probed entry point.</p>
 *
 * <p>The result is computed once at class initialisation and cached
 * in a {@code static final} field — repeated calls to
 * {@link #isPresent()} are equivalent to a field read.</p>
 */
public final class DetectionProxy {

    private static final String PROBED_CLASS = "eu.inqudium.proxy.ProxyDispatcher";

    private static final boolean PRESENT;

    static {
        boolean found;
        try {
            Class.forName(PROBED_CLASS, false, DetectionProxy.class.getClassLoader());
            found = true;
        } catch (ClassNotFoundException ignored) {
            found = false;
        }
        PRESENT = found;
    }

    private DetectionProxy() {
        // utility class
    }

    /**
     * Returns {@code true} if {@code eu.inqudium.proxy.ProxyDispatcher}
     * is reachable from the current class loader, {@code false}
     * otherwise.
     *
     * @return whether the proxy integration module is on the classpath
     */
    public static boolean isPresent() {
        return PRESENT;
    }
}
