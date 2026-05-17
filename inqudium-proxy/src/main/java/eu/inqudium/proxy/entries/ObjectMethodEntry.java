package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.handler.InqInvocationHandler;
import eu.inqudium.proxy.handler.ObjectMethodHandler;

import java.util.Objects;

/**
 * Dispatch entry for {@code java.lang.Object} methods routed to the
 * {@link ObjectMethodHandler}. Per ARCHITECTURE.md §10 only three
 * Object methods reach the invocation handler ({@code equals},
 * {@code hashCode}, {@code toString}); the {@link ObjectMethodHandler.Kind}
 * field identifies which.
 *
 * <p>Package-private — proxy code constructs these via the
 * {@link MethodDispatchEntry#objectMethod(ObjectMethodHandler.Kind)}
 * static factory.</p>
 */
record ObjectMethodEntry(ObjectMethodHandler.Kind kind) implements MethodDispatchEntry {

    ObjectMethodEntry {
        Objects.requireNonNull(kind, "kind");
    }

    @Override
    public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args) {
        return ObjectMethodHandler.dispatch(kind, proxy, handler.realTarget(), args);
    }
}
