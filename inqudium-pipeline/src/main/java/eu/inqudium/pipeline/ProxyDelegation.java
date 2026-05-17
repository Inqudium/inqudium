package eu.inqudium.pipeline;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reflective bridge from {@link InqPipeline#protect(Class, Object)}
 * to {@code eu.inqudium.proxy.ProxyDispatcher.protect(...)}.
 *
 * <p>This bridge exists because a direct class-literal reference
 * from {@code inqudium-pipeline} to
 * {@code eu.inqudium.proxy.ProxyDispatcher} would require
 * {@code inqudium-pipeline} to compile-depend on
 * {@code inqudium-proxy}. That is impossible: {@code inqudium-proxy}
 * already depends on {@code inqudium-pipeline}, so a class-literal
 * back-reference would create a Maven cycle.</p>
 *
 * <p>Loaded lazily — only when {@link InqPipeline#protect(Class, Object)}
 * is actually called and {@code DetectionProxy.isPresent()} has
 * returned {@code true}. The static initialiser's
 * {@link Class#forName(String, boolean, ClassLoader)} therefore
 * succeeds by precondition; if it fails, the classpath is
 * inconsistent with what {@code DetectionProxy} reported.</p>
 *
 * <p>One reflective lookup at class initialisation; one
 * {@link Method#invoke(Object, Object...)} per
 * {@code pipeline.protect(...)} call. The reflection overhead is
 * cold-path (per proxy construction, not per method invocation).</p>
 *
 * <p><strong>Transitional bridge.</strong> If a future refactor
 * splits the protect-call API differently (SPI via ServiceLoader,
 * for instance), this class can be replaced. The reflection
 * pattern is the simplest correct solution for the directional
 * asymmetry imposed by ADR-037.</p>
 */
final class ProxyDelegation {

    private static final Method PROTECT_METHOD;

    static {
        try {
            Class<?> dispatcher = Class.forName(
                    "eu.inqudium.proxy.ProxyDispatcher",
                    false,
                    ProxyDelegation.class.getClassLoader());
            PROTECT_METHOD = dispatcher.getMethod(
                    "protect",
                    InqPipeline.class, Class.class, Object.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException(
                    "DetectionProxy reported the proxy module as present "
                            + "but ProxyDispatcher.protect(...) is not loadable. "
                            + "This indicates a corrupt or mismatched classpath.", e);
        }
    }

    private ProxyDelegation() {
        // utility class
    }

    /**
     * Delegates to {@code ProxyDispatcher.protect(pipeline,
     * serviceInterface, target)} via reflection. Unwraps the
     * reflective {@link InvocationTargetException} so callers see
     * the underlying throwable directly.
     */
    @SuppressWarnings("unchecked")
    static <T> T delegateProtect(InqPipeline pipeline, Class<T> serviceInterface, T target) {
        try {
            return (T) PROTECT_METHOD.invoke(null, pipeline, serviceInterface, target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Reflective invocation of ProxyDispatcher.protect refused", e);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(
                    "ProxyDispatcher.protect threw a checked exception "
                            + "(this should not happen — its declared throws "
                            + "are unchecked)", cause);
        }
    }
}
