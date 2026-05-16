package eu.inqudium.proxy.invocation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * {@link MethodInvoker} implementation that uses
 * {@link Method#invoke(Object, Object...)} directly. Selected when
 * the JVM property {@code inqudium.proxy.invoker=reflective}. Mostly
 * useful for benchmarks comparing against the
 * {@link MethodHandleInvoker} default.
 *
 * <p>Unlike the {@code MethodHandle} variant, this implementation
 * wraps the method's thrown exceptions in
 * {@link InvocationTargetException}. Callers unwrap via
 * {@code ThrowableUnwrap}.</p>
 */
final class ReflectiveInvoker implements MethodInvoker {

    private final Object target;
    private final Method method;

    ReflectiveInvoker(Object target, Method method) {
        this.target = target;
        this.method = method;
        // The proxy bypasses access checks on service-interface methods.
        // setAccessible(true) is safe here because the method is part of
        // a service-interface contract the application is actively using.
        method.setAccessible(true);
    }

    @Override
    public Object invoke(Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            // Should not happen — setAccessible(true) ran in the constructor.
            throw new IllegalStateException(
                    "Reflective invocation refused for " + method, e);
        }
        // InvocationTargetException is left unwrapped here; the caller
        // (typically ExceptionClassifier via ThrowableUnwrap) unwraps it.
    }

    @Override
    public Object invokeAsync(Object[] args) throws Throwable {
        return invoke(args);
    }
}
