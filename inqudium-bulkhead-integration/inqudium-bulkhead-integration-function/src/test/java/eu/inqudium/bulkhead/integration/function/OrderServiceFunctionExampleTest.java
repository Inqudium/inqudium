package eu.inqudium.bulkhead.integration.function;

import eu.inqudium.config.runtime.BulkheadHandle;
import eu.inqudium.config.runtime.ImperativeTag;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the function-based bulkhead example.
 *
 * <p>The tests exercise the example application — they use the same {@link OrderService},
 * the same {@link BulkheadConfig#newRuntime()} entry point, and the same
 * {@code decorateFunction}/{@code decorateSupplier} wrapping pattern that {@link Main}
 * demonstrates. The tests do not reach into bulkhead internals: assertions read
 * {@link InqBulkhead#availablePermits()}, the public handle accessor an application could
 * also consult.
 */
@DisplayName("Function-based bulkhead example")
class OrderServiceFunctionExampleTest {

    @SuppressWarnings("unchecked")
    private static InqBulkhead<String, String> orderBulkhead(InqRuntime runtime) {
        return (InqBulkhead<String, String>) runtime.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        void place_order_succeeds_through_the_bulkhead() {
            // Given: a runtime with the example's bulkhead and a wrapped service method
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService();
                Function<String, String> protectedPlaceOrder =
                        orderBulkhead(runtime).decorateFunction(service::placeOrder);

                // When: a single order is placed through the wrapped function
                String result = protectedPlaceOrder.apply("Widget");

                // Then: the service's reply propagates back unchanged
                assertThat(result).isEqualTo("ordered:Widget");
            }
        }

        @Test
        void place_order_releases_the_permit_after_each_call() {
            // What is to be tested: the bulkhead releases the acquired permit at the end of
            // every wrapped call, so that sequential calls never deplete the permit pool.
            // How will the test be deemed successful and why: availablePermits() reads two
            // (the configured limit) before and after each call. If the bulkhead leaked a
            // permit on the synchronous return path, the count would drop monotonically.
            // Why is it important: a leaked permit on the happy path is the most
            // user-impacting class of bulkhead defect — the protection mechanism turns into
            // a cliff for every subsequent caller.
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService();
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);
                Function<String, String> protectedPlaceOrder =
                        bulkhead.decorateFunction(service::placeOrder);

                // Given: a fully-released bulkhead at the configured limit
                assertThat(bulkhead.availablePermits()).isEqualTo(2);

                // When: the same wrapped function is called multiple times sequentially
                for (int i = 0; i < 5; i++) {
                    protectedPlaceOrder.apply("item-" + i);

                    // Then: the permit count returns to two after every call
                    assertThat(bulkhead.availablePermits())
                            .as("after call %d", i)
                            .isEqualTo(2);
                }
            }
        }
    }

    @Nested
    @DisplayName("Saturation")
    class Saturation {

        @Test
        void concurrent_calls_above_the_limit_are_rejected_with_InqBulkheadFullException() throws InterruptedException {
            // What is to be tested: when both permits are held by concurrent in-flight
            // calls, a third synchronous call cannot acquire a permit and is rejected with
            // InqBulkheadFullException. How will the test be deemed successful and why: two
            // virtual-thread holders enter placeOrderHolding and decrement their acquired
            // latches; a third call from the main thread is rejected synchronously; both
            // holders complete cleanly once the release latch fires. Why is it important:
            // saturation rejection is the bulkhead's reason to exist — a regression here
            // means the protection mechanism has been silently disabled or the rejection
            // type has been re-wrapped, breaking the user-facing contract.
            try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
                OrderService service = new OrderService();
                InqBulkhead<String, String> bulkhead = orderBulkhead(runtime);

                CountDownLatch holderAcquired1 = new CountDownLatch(1);
                CountDownLatch holderAcquired2 = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                List<Throwable> holderErrors = new ArrayList<>();

                // Given: two virtual threads each holding a permit
                Thread holder1 = Thread.startVirtualThread(() -> {
                    try {
                        bulkhead.decorateSupplier(
                                () -> service.placeOrderHolding(holderAcquired1, release)).get();
                    } catch (Throwable t) {
                        holderErrors.add(t);
                    }
                });
                Thread holder2 = Thread.startVirtualThread(() -> {
                    try {
                        bulkhead.decorateSupplier(
                                () -> service.placeOrderHolding(holderAcquired2, release)).get();
                    } catch (Throwable t) {
                        holderErrors.add(t);
                    }
                });

                assertThat(holderAcquired1.await(5, TimeUnit.SECONDS))
                        .as("holder 1 must enter the body").isTrue();
                assertThat(holderAcquired2.await(5, TimeUnit.SECONDS))
                        .as("holder 2 must enter the body").isTrue();

                try {
                    // When: a third call attempts to acquire a permit
                    Function<String, String> protectedPlaceOrder =
                            bulkhead.decorateFunction(service::placeOrder);

                    // Then: it is rejected synchronously with the bulkhead's own exception
                    assertThatThrownBy(() -> protectedPlaceOrder.apply("Saturated"))
                            .isInstanceOf(InqBulkheadFullException.class);
                } finally {
                    release.countDown();
                    holder1.join();
                    holder2.join();
                }

                assertThat(holderErrors)
                        .as("holders must release without errors").isEmpty();
                assertThat(bulkhead.availablePermits())
                        .as("permits return to the configured limit after holders release")
                        .isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        void the_runtime_can_be_closed_and_a_fresh_one_built_in_the_same_test_class() {
            // Sanity check: BulkheadConfig.newRuntime() produces independent runtimes that
            // do not interfere with one another, so two consecutive tests in this class
            // each build and close their own without observing artefacts from the other.
            // The check is structural — if the example's runtime construction were
            // accidentally tied to a process-level singleton, this would surface.
            try (InqRuntime first = BulkheadConfig.newRuntime()) {
                BulkheadHandle<ImperativeTag> firstHandle =
                        first.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME);
                assertThat(firstHandle.name()).isEqualTo(BulkheadConfig.BULKHEAD_NAME);
            }

            try (InqRuntime second = BulkheadConfig.newRuntime()) {
                BulkheadHandle<ImperativeTag> secondHandle =
                        second.imperative().bulkhead(BulkheadConfig.BULKHEAD_NAME);
                assertThat(secondHandle.name()).isEqualTo(BulkheadConfig.BULKHEAD_NAME);
                assertThat(secondHandle.availablePermits()).isEqualTo(2);
            }
        }
    }
}
