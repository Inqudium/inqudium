package eu.inqudium.proxy.invocation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * {@link MethodInvoker} implementation that uses a
 * {@link MethodHandle} bound to the target. This is the default
 * strategy selected when the JVM property
 * {@code inqudium.proxy.invoker} is unset or {@code mh}.
 *
 * <p>One {@code MethodHandle} per {@code (target, method)} pair is
 * cached in this instance. Arity-specialised variants (one handle
 * per parameter count) are deferred until benchmarks justify the
 * complexity (per ARCHITECTURE.md §11).</p>
 */
final class MethodHandleInvoker implements MethodInvoker {

    private final MethodHandle handle;

    MethodHandleInvoker(Object target, Method method) {
        try {
            MethodHandle raw = MethodHandles.lookup().unreflect(method);
            this.handle = raw.bindTo(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot reflect method " + method
                            + " for MethodHandle-based invocation", e);
        }
    }

    @Override
    public Object invoke(Object[] args) throws Throwable {
        return handle.invokeWithArguments(args);
    }

    @Override
    public Object invokeAsync(Object[] args) throws Throwable {
        return invoke(args);
    }
}
