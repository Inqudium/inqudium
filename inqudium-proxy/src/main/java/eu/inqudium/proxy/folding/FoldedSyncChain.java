package eu.inqudium.proxy.folding;

/**
 * Pre-folded synchronous resilience chain for one service method.
 * Produced once at proxy construction time by {@link SyncChainFolder};
 * called once per method invocation by {@code SyncCacheEntry}.
 *
 * <p>The arguments flow through the {@code args} parameter — they
 * are <strong>not</strong> captured in the closure. This is what
 * makes per-call allocations small: each invocation captures
 * {@code stackId}, {@code callId}, and {@code args} freshly.</p>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from {@code entries/}; not part of the stable public
 * surface.</p>
 */
@FunctionalInterface
public interface FoldedSyncChain {

    /**
     * Runs the folded chain with the given correlation IDs and
     * method arguments. Returns the method's return value, or
     * {@code null} for {@code void} methods.
     */
    Object run(long stackId, long callId, Object[] args) throws Throwable;
}
