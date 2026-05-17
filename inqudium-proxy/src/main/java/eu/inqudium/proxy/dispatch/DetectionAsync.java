package eu.inqudium.proxy.dispatch;

/**
 * Probes whether {@code inqudium-imperative} is on the classpath.
 *
 * <p>Pattern matches {@code eu.inqudium.pipeline.DetectionProxy}: a
 * single boolean check at class-init time via
 * {@link Class#forName(String, boolean, ClassLoader)}. No
 * class-literal reference to async types appears here, so this
 * class is always loadable.</p>
 *
 * <p>Used by {@code MethodDispatchEntryFactory} to gate the async
 * dispatch branch (ADR-037 §6 / ARCHITECTURE.md §13). When this
 * class returns {@code false} and the service interface contains a
 * {@link java.util.concurrent.CompletionStage}-returning method,
 * construction fails with a descriptive
 * {@link IllegalStateException}.</p>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from {@code construction/}; not part of the stable
 * public surface.</p>
 */
public final class DetectionAsync {

    private static final String ASYNC_DECORATOR_FQN =
            "eu.inqudium.imperative.core.pipeline.InqAsyncDecorator";

    private static final boolean PRESENT = checkPresent();

    private DetectionAsync() {
        // utility class
    }

    /**
     * Returns {@code true} if {@code inqudium-imperative} is on the
     * classpath. The result is computed once at class load time;
     * subsequent invocations are constant.
     */
    public static boolean isPresent() {
        return PRESENT;
    }

    private static boolean checkPresent() {
        try {
            Class.forName(
                    ASYNC_DECORATOR_FQN,
                    false,
                    DetectionAsync.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
