package eu.inqudium.proxy.introspection;

import eu.inqudium.proxy.handler.InqInvocationHandler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.Optional;

/**
 * Introspection adapter for proxies produced by
 * {@code ProxyDispatcher.protect(...)}.
 *
 * <p>Two static methods per ADR-039:</p>
 * <ul>
 *   <li>{@link #supports(Object)}: returns {@code true} for a JDK
 *       proxy whose invocation handler is an
 *       {@link InqInvocationHandler}.</li>
 *   <li>{@link #inspect(Object)}: builds the {@link ProxyStackInfo}
 *       DTO for a supported instance.</li>
 * </ul>
 *
 * <p><strong>Public API.</strong> Standalone adapter per the
 * Option-B scope decision: not yet wired into a central
 * {@code InqIntrospector} (which doesn't exist yet — separate
 * refactor). Client code calls {@link #inspect(Object)} directly.</p>
 */
public final class ProxyStackAdapter {

    private ProxyStackAdapter() {
        // utility class
    }

    /**
     * Returns {@code true} iff {@code instance} is a JDK dynamic
     * proxy whose invocation handler is an
     * {@link InqInvocationHandler}.
     */
    public static boolean supports(Object instance) {
        if (instance == null) {
            return false;
        }
        if (!Proxy.isProxyClass(instance.getClass())) {
            return false;
        }
        InvocationHandler handler = Proxy.getInvocationHandler(instance);
        return handler instanceof InqInvocationHandler;
    }

    /**
     * Inspects {@code instance} and returns its
     * {@link ProxyStackInfo}.
     *
     * @throws IllegalArgumentException if {@code instance} is not
     *         a proxy produced by {@code ProxyDispatcher.protect(...)}
     *         (use {@link #supports(Object)} to guard before calling)
     */
    public static ProxyStackInfo inspect(Object instance) {
        Objects.requireNonNull(instance, "instance");
        if (!supports(instance)) {
            throw new IllegalArgumentException(
                    "instance is not a proxy produced by ProxyDispatcher; "
                            + "use supports(...) to guard before calling inspect(...)");
        }
        InqInvocationHandler handler =
                (InqInvocationHandler) Proxy.getInvocationHandler(instance);
        return new ProxyStackInfo(
                handler.stackId(),
                Optional.of(handler.serviceInterface()),
                handler.elements(),
                handler.methodLayers());
    }
}
