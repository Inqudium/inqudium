package eu.inqudium.bulkhead.integration.proxy;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.snapshot.BulkheadEventConfig;

/**
 * Single point where Inqudium is configured for this example.
 *
 * <p>The configuration is identical to the function-based example: one bulkhead named
 * {@code orderBh}, the {@code balanced} preset overridden to two concurrent permits. The
 * configuration code does not change between integration styles — the runtime is the same;
 * only the wiring on top differs. This module exercises the same runtime through a JDK
 * dynamic proxy rather than by wrapping method references at the call site.
 *
 * <p>The bulkhead is configured to emit acquire / release / reject / rollback events on its
 * per-component publisher (the four event types covered by sub-step&nbsp;6.D of
 * {@code REFACTORING_BULKHEAD_LOGGING_AND_RUNTIME_CONFIG.md}). The DSL default
 * ({@link BulkheadEventConfig#disabled()}) keeps the hot path event-free; the example opts
 * back in so the {@code DefaultOrderService}'s subscribers have something to log. The fifth
 * flag ({@code waitTrace}, gating {@code BulkheadWaitTraceEvent}) is intentionally left off
 * — the plan's decision&nbsp;4 enumerates four event types and
 * {@code BulkheadEventConfig.allEnabled()} would silently include the fifth.
 */
public final class BulkheadConfig {

    /** The name under which the example's bulkhead is registered. */
    public static final String BULKHEAD_NAME = "orderBh";

    /**
     * Per-event flag set the example opts into. The four flags map one-to-one to the four
     * bulkhead event types subscribed in {@code DefaultOrderService}.
     */
    private static final BulkheadEventConfig EXAMPLE_EVENTS = new BulkheadEventConfig(
            true,   // onAcquire   -> BulkheadOnAcquireEvent (TRACE)
            true,   // onRelease   -> BulkheadOnReleaseEvent (TRACE)
            true,   // onReject    -> BulkheadOnRejectEvent (WARN)
            false,  // waitTrace   -> not in scope for sub-step 6.D
            true);  // rollback    -> BulkheadRollbackTraceEvent (ERROR)

    private BulkheadConfig() {
        // utility class
    }

    /**
     * @return a freshly built runtime with a single bulkhead named {@link #BULKHEAD_NAME}.
     *         The runtime is independent of any other call site and must be closed by its
     *         caller — typically via try-with-resources.
     */
    public static InqRuntime newRuntime() {
        return Inqudium.configure()
                .imperative(im -> im.bulkhead(BULKHEAD_NAME,
                        b -> b.balanced()
                                .maxConcurrentCalls(2)
                                .events(EXAMPLE_EVENTS)))
                .build();
    }
}
