package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.handler.InqInvocationHandler;
import eu.inqudium.proxy.invocation.MethodInvoker;

import java.util.Objects;

/**
 * Dispatches a service-interface method directly to the real
 * target via a bound {@link MethodInvoker}, without any resilience
 * layers around the call.
 *
 * <p>Used when the annotation evaluator's plan for the method is
 * {@code MethodPlan.PassThrough} — i.e. no annotations on the method
 * (or its implementation) request decoration by any pipeline
 * element.</p>
 */
final class PassThroughEntry implements MethodDispatchEntry {

    private final MethodInvoker invoker;

    PassThroughEntry(MethodInvoker invoker) {
        this.invoker = Objects.requireNonNull(invoker, "invoker");
    }

    @Override
    public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args)
            throws Throwable {
        return invoker.invoke(args);
    }
}
