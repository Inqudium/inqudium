package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.handler.InqInvocationHandler;

/**
 * The per-method dispatch strategy stored in the proxy's
 * {@code PerProxyCache}. Each entry knows how to handle one
 * specific {@link java.lang.reflect.Method} on the proxied service
 * interface: pass-through to the real target, delegation to a
 * Java default method, or routing through a folded resilience chain.
 *
 * <p>The sealed family per ADR-035 §7 / ARCHITECTURE.md §4:</p>
 * <ul>
 *   <li>{@link PassThroughEntry} — single {@code MethodInvoker}
 *       call to the real target. Used when the annotation
 *       evaluator's plan for the method is
 *       {@code MethodPlan.PassThrough} (sub-step 3.6).</li>
 *   <li>{@link DefaultMethodEntry} — {@code InvocationHandler.invokeDefault}
 *       for unoverridden Java default methods (sub-step 3.6).</li>
 *   <li>{@code SyncCacheEntry} — folded sync chain, added in
 *       sub-step 3.7.</li>
 *   <li>{@code ObjectMethodEntry} — {@code Object}-declared methods
 *       (equals, hashCode, toString, etc.), added in sub-step 3.10.</li>
 *   <li>{@code AsyncCacheEntry} — folded async chain, added in
 *       sub-step 3.11.</li>
 * </ul>
 *
 * <p><strong>Internal API.</strong> Permitted types are
 * package-private; this interface is {@code public} so that
 * {@code construction/} and {@code handler/} subpackages can name
 * the type.</p>
 */
public sealed interface MethodDispatchEntry
        permits PassThroughEntry, DefaultMethodEntry {

    /**
     * Dispatches the call to the entry's strategy and returns the
     * result.
     *
     * @param proxy   the JDK proxy instance whose method was called
     * @param handler the {@link InqInvocationHandler} routing the
     *                call (entries that need stack/call IDs or
     *                a back-reference to the handler read from
     *                this parameter)
     * @param args    the normalised argument array (never
     *                {@code null}; see {@code ArgNormalizer})
     * @return the method's return value, or {@code null} for
     *         {@code void} methods
     */
    Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args)
            throws Throwable;
}
