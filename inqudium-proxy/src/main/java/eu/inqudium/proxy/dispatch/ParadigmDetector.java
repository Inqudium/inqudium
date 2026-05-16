package eu.inqudium.proxy.dispatch;

import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;

/**
 * Detects the paradigm of a service-interface method from its
 * return type. JDK types only — no reference to
 * {@code inqudium-imperative} or {@code project-reactor}.
 *
 * <p>Per ARCHITECTURE.md §4: this class loads no third-party
 * paradigm types. {@code Mono} detection arrives when reactive
 * support lands (out of scope for the proxy rewrite).</p>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from {@code construction/}; not part of the stable
 * public surface.</p>
 */
public final class ParadigmDetector {

    private ParadigmDetector() {
        // utility class
    }

    /**
     * Returns {@code true} if the method's return type is
     * {@link CompletionStage} or a subtype (e.g.
     * {@link java.util.concurrent.CompletableFuture}).
     */
    public static boolean isAsyncMethod(Method method) {
        return CompletionStage.class.isAssignableFrom(method.getReturnType());
    }
}
