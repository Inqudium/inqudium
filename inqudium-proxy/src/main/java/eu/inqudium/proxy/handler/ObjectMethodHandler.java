package eu.inqudium.proxy.handler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Dispatcher for {@code java.lang.Object} methods on proxies
 * produced by {@code ProxyDispatcher}. Implements the semantics of
 * ARCHITECTURE.md §10 / ADR-035 §8:
 *
 * <ul>
 *   <li>{@code equals}: two proxies are equal iff both are JDK
 *       proxies whose invocation handlers are
 *       {@link InqInvocationHandler}s wrapping equal real targets.
 *       Comparison with non-proxies always returns {@code false}.</li>
 *   <li>{@code hashCode}: delegates to the real target.</li>
 *   <li>{@code toString}: descriptive — proxy class simple name plus
 *       the real target's {@code toString}.</li>
 * </ul>
 *
 * <p><strong>Why only three {@link Kind}s?</strong> The JDK
 * {@link java.lang.reflect.Proxy} class routes only {@code hashCode},
 * {@code equals}, and {@code toString} from {@link Object} to the
 * invocation handler. {@code wait}, {@code notify}, {@code notifyAll},
 * and {@code getClass} are handled by the JVM directly on the proxy
 * instance and never reach our handler. ARCHITECTURE.md §10's older
 * seven-value enumeration is corrected as part of sub-step 3.10.</p>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from {@code entries/}; not part of the stable public
 * surface.</p>
 */
public final class ObjectMethodHandler {

    /**
     * The three {@link Object} methods that the JDK
     * {@link java.lang.reflect.Proxy} class routes to invocation
     * handlers.
     */
    public enum Kind {
        EQUALS, HASH_CODE, TO_STRING
    }

    private ObjectMethodHandler() {
        // utility class
    }

    /**
     * Dispatches an {@link Object}-method call.
     *
     * @param kind       which Object method was invoked
     * @param proxy      the proxy instance (the JDK passes this as
     *                   the first arg of {@code InvocationHandler.invoke})
     * @param realTarget the real implementation the proxy wraps
     * @param args       the call arguments after normalisation
     *                   (non-null array; possibly empty)
     * @return           the {@code equals}/{@code hashCode}/{@code toString} result
     */
    public static Object dispatch(
            Kind kind, Object proxy, Object realTarget, Object[] args) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(realTarget, "realTarget");
        Objects.requireNonNull(args, "args");
        return switch (kind) {
            case EQUALS -> equalsImpl(realTarget, args);
            case HASH_CODE -> realTarget.hashCode();
            case TO_STRING -> toStringImpl(proxy, realTarget);
        };
    }

    /**
     * {@code equals} semantics per ARCHITECTURE.md §10:
     *
     * <ul>
     *   <li>The "other" argument must be a JDK proxy.</li>
     *   <li>Its invocation handler must be an
     *       {@link InqInvocationHandler}.</li>
     *   <li>The other handler's {@code realTarget} must be equal to
     *       our own.</li>
     * </ul>
     *
     * <p>Reflexivity, symmetry, and transitivity all follow from
     * {@code Object.equals}'s contract because we ultimately delegate
     * the comparison to the targets' own {@code equals}.</p>
     */
    private static boolean equalsImpl(Object realTarget, Object[] args) {
        if (args.length != 1) {
            // Defensive: equals always has arity 1, but normalisation
            // could in principle leave args of unexpected length.
            return false;
        }
        Object other = args[0];
        if (other == null) {
            return false;
        }
        if (!Proxy.isProxyClass(other.getClass())) {
            return false;
        }
        InvocationHandler otherHandler = Proxy.getInvocationHandler(other);
        if (!(otherHandler instanceof InqInvocationHandler otherInq)) {
            return false;
        }
        return realTarget.equals(otherInq.realTarget());
    }

    private static String toStringImpl(Object proxy, Object realTarget) {
        return proxy.getClass().getSimpleName() + "[" + realTarget + "]";
    }
}
