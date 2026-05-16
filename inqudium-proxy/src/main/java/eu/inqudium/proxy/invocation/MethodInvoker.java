package eu.inqudium.proxy.invocation;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Bound invocation site for a single {@code (target, method)} pair.
 * The {@link #invoke(Object[])} method calls the bound method on the
 * bound target with the supplied arguments and returns the result.
 *
 * <p><strong>Internal API.</strong> This interface is {@code public}
 * only because Java without modules requires package-cross visibility
 * for sealed types referenced from sibling subpackages
 * ({@code construction/}, {@code folding/}, {@code entries/}). The
 * type is not part of {@code inqudium-proxy}'s stable public API and
 * may change in future minor releases. Application code should not
 * depend on it.</p>
 *
 * <p>Implementation strategy is selected via the JVM property
 * {@code inqudium.proxy.invoker} (values: {@code mh} default,
 * {@code reflective} fallback). See {@link #create(Object, Method)}.</p>
 *
 * @see MethodHandleInvoker
 * @see ReflectiveInvoker
 */
public sealed interface MethodInvoker permits MethodHandleInvoker, ReflectiveInvoker {

    /**
     * Invokes the bound method on the bound target with the given
     * arguments. Returns the method's return value, or {@code null}
     * for {@code void} methods.
     *
     * <p>If the underlying method throws, the original throwable is
     * propagated — possibly wrapped in
     * {@link java.lang.reflect.InvocationTargetException} (for the
     * reflective implementation). Callers responsible for unwrapping
     * use {@code ThrowableUnwrap}.</p>
     */
    Object invoke(Object[] args) throws Throwable;

    /**
     * Async-context invocation. At sub-step 3.5 the implementation is
     * identical to {@link #invoke}; sub-step 3.11 (async dispatch) may
     * specialise it.
     *
     * <p>Only meaningful when {@code DetectionAsync.isPresent()}. The
     * synchronous loading path never calls this method, so async
     * machinery from {@code inqudium-imperative} stays unloaded.</p>
     */
    Object invokeAsync(Object[] args) throws Throwable;

    /**
     * Creates a {@code MethodInvoker} bound to {@code (target,
     * method)}, picking the implementation strategy from the JVM
     * property {@code inqudium.proxy.invoker}.
     *
     * @param target the receiver instance; must not be {@code null}
     * @param method the method to invoke; must not be {@code null}
     * @throws IllegalArgumentException if the JVM property value is
     *                                  neither {@code mh} nor
     *                                  {@code reflective}
     */
    static MethodInvoker create(Object target, Method method) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(method, "method");
        String type = System.getProperty("inqudium.proxy.invoker", "mh");
        return switch (type) {
            case "mh" -> new MethodHandleInvoker(target, method);
            case "reflective" -> new ReflectiveInvoker(target, method);
            default -> throw new IllegalArgumentException(
                    "Unknown invoker type '" + type + "' for property "
                            + "inqudium.proxy.invoker (expected 'mh' or "
                            + "'reflective')");
        };
    }
}
