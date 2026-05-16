package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.handler.InqInvocationHandler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Dispatches an unoverridden Java {@code default} method on the
 * service interface via {@link InvocationHandler#invokeDefault(Object,
 * Method, Object...)} (Java 16+, per ADR-035 §7).
 *
 * <p>Used when the annotation evaluator's plan for the method is
 * {@code MethodPlan.PassThrough} <strong>and</strong> the method is
 * a Java default method that the implementation class does not
 * override. The proxy must invoke the interface's default body, not
 * the (non-existent) implementation override.</p>
 *
 * <p>{@code invokeDefault} transparently handles JPMS module
 * boundaries — no {@code MethodHandles.privateLookupIn} is needed.</p>
 */
final class DefaultMethodEntry implements MethodDispatchEntry {

    private final Method defaultMethod;

    DefaultMethodEntry(Method defaultMethod) {
        this.defaultMethod = Objects.requireNonNull(defaultMethod, "defaultMethod");
        if (!defaultMethod.isDefault()) {
            throw new IllegalArgumentException(
                    "Method " + defaultMethod + " is not a default method");
        }
    }

    @Override
    public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args)
            throws Throwable {
        return InvocationHandler.invokeDefault(proxy, defaultMethod, args);
    }
}
