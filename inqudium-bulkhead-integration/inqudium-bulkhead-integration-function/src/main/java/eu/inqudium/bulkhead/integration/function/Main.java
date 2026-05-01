package eu.inqudium.bulkhead.integration.function;

import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.imperative.bulkhead.InqBulkhead;

import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * End-to-end demonstration of the function-based integration style.
 *
 * <p>The flow follows the natural structure of a function-based application:
 * <ol>
 *   <li>build an {@link InqRuntime} via the DSL,</li>
 *   <li>obtain the bulkhead handle from the runtime's imperative paradigm container,</li>
 *   <li>wrap the service's methods through {@link InqBulkhead#decorateFunction
 *       decorateFunction} (or its sibling {@code decorateSupplier}),</li>
 *   <li>call the wrapped function — the bulkhead acquires and releases its permit
 *       around the delegate transparently.</li>
 * </ol>
 *
 * <p>The class is verbose by design: it doubles as a tutorial. Comments describe what each
 * step demonstrates so a reader can map the code onto the function-based pattern's shape.
 */
public final class Main {

    private Main() {
        // entry point only
    }

    public static void main(String[] args) {
        // The runtime owns every Inqudium component for the duration of the application.
        // Try-with-resources guarantees a clean shutdown — paradigm containers close,
        // strategies tear down, the runtime-scoped event publisher releases.
        try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
            OrderService service = new OrderService();
            InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

            // The headline shape of the function-based pattern: the bulkhead wraps an
            // ordinary method reference into a Function. The wrapped function carries the
            // resilience behaviour; the service stays unaware of it.
            Function<String, String> protectedPlaceOrder =
                    bulkhead.decorateFunction(service::placeOrder);

            System.out.println(protectedPlaceOrder.apply("Widget"));
            System.out.println(protectedPlaceOrder.apply("Sprocket"));
            System.out.println(protectedPlaceOrder.apply("Gadget"));

            demonstrateSaturation(bulkhead, service);
        }
    }

    /**
     * Look up the example bulkhead by name and cast to the typed surface so that the
     * fully-type-safe {@link InqBulkhead#decorateFunction} factory becomes available. The
     * cast is safe because the runtime registry stores the same instance under both views.
     */
    @SuppressWarnings("unchecked")
    private static InqBulkhead<String, String> orderBulkhead(InqRuntime runtime) {
        return (InqBulkhead<String, String>) runtime.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }

    /**
     * Saturate the bulkhead with two virtual-thread holders, attempt a third call from the
     * main thread, and observe the rejection. The pattern mirrors the saturation test
     * fixture — the example shows the same shape an application developer would write to
     * verify rejection behaviour by hand.
     */
    private static void demonstrateSaturation(InqBulkhead<String, String> bulkhead,
                                              OrderService service) {
        CountDownLatch holderAcquired1 = new CountDownLatch(1);
        CountDownLatch holderAcquired2 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder1 = Thread.startVirtualThread(() ->
                bulkhead.decorateSupplier(
                        () -> service.placeOrderHolding(holderAcquired1, release)).get());
        Thread holder2 = Thread.startVirtualThread(() ->
                bulkhead.decorateSupplier(
                        () -> service.placeOrderHolding(holderAcquired2, release)).get());

        try {
            holderAcquired1.await();
            holderAcquired2.await();

            try {
                bulkhead.decorateFunction(service::placeOrder).apply("Saturated");
                System.out.println("unexpected: third call returned");
            } catch (InqBulkheadFullException rejected) {
                System.out.println("third call rejected: " + rejected.getRejectionReason());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            release.countDown();
            joinQuietly(holder1);
            joinQuietly(holder2);
        }
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
