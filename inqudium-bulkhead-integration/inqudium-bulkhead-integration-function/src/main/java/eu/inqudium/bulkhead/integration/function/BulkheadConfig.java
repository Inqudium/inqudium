package eu.inqudium.bulkhead.integration.function;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;

/**
 * Single point where Inqudium is configured for this example.
 *
 * <p>A reader sees the entire runtime configuration in one place: one bulkhead named
 * {@code orderBh}, the {@code balanced} preset overridden to two concurrent permits.
 * The semaphore strategy is the default of {@code balanced()}; {@code maxConcurrentCalls(2)}
 * is small enough that saturation is observable in tests without parallel pressure.
 */
public final class BulkheadConfig {

    /** The name under which the example's bulkhead is registered. */
    public static final String BULKHEAD_NAME = "orderBh";

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
                        b -> b.balanced().maxConcurrentCalls(2)))
                .build();
    }
}
