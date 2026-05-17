package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.folding.FoldedAsyncChain;
import eu.inqudium.proxy.folding.FoldedSyncChain;
import eu.inqudium.proxy.handler.InqInvocationHandler;
import eu.inqudium.proxy.handler.ObjectMethodHandler;
import eu.inqudium.proxy.invocation.MethodInvoker;

import java.lang.reflect.Method;
import java.util.List;

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
 *   <li>{@link SyncCacheEntry} — folded sync chain (sub-step 3.7).</li>
 *   <li>{@link ObjectMethodEntry} — {@code Object}-declared methods
 *       ({@code equals}, {@code hashCode}, {@code toString}), routed
 *       to {@link ObjectMethodHandler} (sub-step 3.10).</li>
 *   <li>{@link AsyncCacheEntry} — folded async chain (sub-step 3.11).</li>
 * </ul>
 *
 * <p><strong>Internal API.</strong> Permitted types are
 * package-private; this interface is {@code public} so that
 * {@code construction/} and {@code handler/} subpackages can name
 * the type.</p>
 */
public sealed interface MethodDispatchEntry
        permits PassThroughEntry, DefaultMethodEntry, SyncCacheEntry,
                ObjectMethodEntry, AsyncCacheEntry {

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

    /**
     * Creates a {@link PassThroughEntry} bound to {@code invoker}.
     * Cross-package entry point for {@code construction/}; the
     * permitted record itself stays package-private.
     */
    static MethodDispatchEntry passThrough(MethodInvoker invoker) {
        return new PassThroughEntry(invoker);
    }

    /**
     * Creates a {@link DefaultMethodEntry} for the given Java
     * {@code default} method. Cross-package entry point for
     * {@code construction/}; the permitted record itself stays
     * package-private.
     */
    static MethodDispatchEntry defaultMethod(Method defaultMethod) {
        return new DefaultMethodEntry(defaultMethod);
    }

    /**
     * Creates a {@link SyncCacheEntry} carrying the pre-folded chain
     * and the layer descriptions for introspection (ADR-039).
     * Cross-package entry point for {@code construction/}; the
     * permitted record itself stays package-private.
     */
    static MethodDispatchEntry syncCache(
            FoldedSyncChain chain, List<String> layerDescriptions) {
        return new SyncCacheEntry(chain, layerDescriptions);
    }

    /**
     * Creates an {@link ObjectMethodEntry} for one of the three
     * {@link Object} methods routed to the
     * {@link ObjectMethodHandler}. Cross-package entry point for
     * {@code construction/}; the permitted record itself stays
     * package-private.
     */
    static MethodDispatchEntry objectMethod(ObjectMethodHandler.Kind kind) {
        return new ObjectMethodEntry(kind);
    }

    /**
     * Creates an {@link AsyncCacheEntry} carrying the pre-folded
     * async chain and the layer descriptions for introspection
     * (ADR-039). Cross-package entry point for {@code construction/};
     * the permitted record itself stays package-private.
     */
    static MethodDispatchEntry asyncCache(
            FoldedAsyncChain chain, List<String> layerDescriptions) {
        return new AsyncCacheEntry(chain, layerDescriptions);
    }
}
