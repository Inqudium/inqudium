package eu.inqudium.proxy.handler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The {@link InvocationHandler} installed on every JDK proxy
 * produced by {@code ProxyDispatcher.protect(...)}.
 *
 * <p>Holds the per-proxy state shared by every dispatch entry:</p>
 * <ul>
 *   <li>{@code stackId} — the stable ID of the proxy stack, per
 *       ADR-034. Constant for the lifetime of the proxy.</li>
 *   <li>{@code callIdSource} — a fresh call-ID generator for this
 *       proxy. Each method invocation pulls one ID from it via
 *       {@link #nextCallId()}.</li>
 * </ul>
 *
 * <p><strong>Internal API.</strong> This class is {@code public} so
 * the dispatch entries in {@code eu.inqudium.proxy.entries} can
 * reference it; it is not part of {@code inqudium-proxy}'s stable
 * public API.</p>
 *
 * <p><strong>Sub-step 3.7 state:</strong> state and accessors are
 * in place; {@link #invoke} still throws
 * {@link UnsupportedOperationException}. The dispatch loop arrives
 * in sub-step 3.9.</p>
 */
public final class InqInvocationHandler implements InvocationHandler {

    private final long stackId;
    private final LongSupplier callIdSource;

    public InqInvocationHandler(long stackId, LongSupplier callIdSource) {
        this.stackId = stackId;
        this.callIdSource = Objects.requireNonNull(callIdSource, "callIdSource");
    }

    /**
     * Returns the proxy stack's stable ID per ADR-034. Constant for
     * the lifetime of this handler.
     */
    public long stackId() {
        return stackId;
    }

    /**
     * Pulls the next call ID from the source. Each call returns a
     * fresh value.
     */
    public long nextCallId() {
        return callIdSource.getAsLong();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        throw new UnsupportedOperationException(
                "InqInvocationHandler.invoke arrives in sub-step 3.9. "
                        + "Sub-step 3.7 added stackId and callIdSource state "
                        + "for the dispatch entries to read.");
    }
}
