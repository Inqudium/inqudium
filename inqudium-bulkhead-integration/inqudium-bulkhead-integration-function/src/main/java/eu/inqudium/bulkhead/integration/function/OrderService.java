package eu.inqudium.bulkhead.integration.function;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tiny webshop order service used as the example domain.
 *
 * <p>The class is plain Java — no Inqudium type, no annotation, no framework hook. Resilience
 * is layered on at the call site by wrapping the methods through an {@code InqBulkhead}. That
 * is the function-based pattern's defining shape: the service does not know it is protected.
 */
public class OrderService {

    /**
     * Places an order for the given item. Synchronous happy-path call.
     *
     * @param item the item identifier; never {@code null}.
     * @return a confirmation string of the form {@code "ordered:<item>"}.
     */
    public String placeOrder(String item) {
        return "ordered:" + item;
    }

    /**
     * Held-permit variant used to demonstrate saturation. The method counts down the
     * {@code acquired} latch as soon as the body begins (so the caller knows the permit is
     * held) and then waits on {@code release} before returning. Combined with a small bulkhead
     * limit, two concurrent invocations of this method exhaust the available permits and a
     * third call is rejected with {@link eu.inqudium.core.element.bulkhead.InqBulkheadFullException}.
     *
     * @param acquired counted down once the permit-holding body has entered.
     * @param release  awaited before the body returns; the test (or {@code Main}) signals this
     *                 latch when the permits should be freed.
     * @return the literal string {@code "released"} once the release latch fires.
     * @throws IllegalStateException if the release latch does not fire within five seconds —
     *                               an internal sanity bound that prevents a stuck test from
     *                               hanging the JVM forever.
     */
    public String placeOrderHolding(CountDownLatch acquired, CountDownLatch release) {
        acquired.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timeout: holder never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return "released";
    }
}
