package eu.inqudium.proxy.smoke;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.pipeline.InqPipeline;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end smoke tests for {@code pipeline.protect(...)} with the
 * <strong>real production</strong>
 * {@link eu.inqudium.imperative.bulkhead.InqBulkhead} component from
 * {@code inqudium-imperative} (not the {@code FakeBulkhead} fixtures
 * of sub-steps 3.5&ndash;3.12).
 *
 * <p>These tests verify that the proxy machinery integrates with
 * actual resilience components users deploy in production:
 * semaphore-based concurrency limiting, exception-driven permit
 * release, and async permit semantics.</p>
 *
 * <p>Determinism comes from {@link CountDownLatch} synchronisation
 * rather than {@code Thread.sleep} time guesses, except for the
 * concurrency-limit test which uses virtual threads holding their
 * permits behind a latch so the rejection assertions are observed
 * on the main thread.</p>
 */
final class RealBulkheadSmokeTest {

    public interface BarrierService {
        String enter(CountDownLatch hold);
    }

    public static final class DefaultBarrierService implements BarrierService {
        @InqBulkhead("real-sync-bh")
        @Override
        public String enter(CountDownLatch hold) {
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return "done";
        }
    }

    public interface AsyncBarrierService {
        CompletableFuture<String> enterAsync(CompletableFuture<String> downstream);
    }

    public static final class DefaultAsyncBarrierService implements AsyncBarrierService {
        @InqBulkhead("real-async-bh")
        @Override
        public CompletableFuture<String> enterAsync(CompletableFuture<String> downstream) {
            return downstream;
        }
    }

    public interface ExceptionService {
        String fail();
    }

    public static final class DefaultExceptionService implements ExceptionService {
        @InqBulkhead("real-exc-bh")
        @Override
        public String fail() {
            throw new IllegalStateException("intentional failure");
        }
    }

    @Nested
    class SyncConcurrencyLimit {

        @Test
        void should_reject_calls_beyond_the_concurrency_limit() throws Exception {
            // What is to be tested? — A real InqBulkhead configured for
            //   max-concurrent=2 with Duration.ZERO (fail-fast) refuses
            //   the third concurrent caller through pipeline.protect.
            //   The two in-flight calls hold their permits behind a
            //   latch so the rejection is observable on the main thread
            //   without timing guesses.
            // Successful when? — Two callers acquire permits and block
            //   on the latch; a third call from the main thread throws
            //   InqBulkheadFullException. Releasing the latch lets the
            //   first two return.
            // Why important? — Pins the contract that pipeline.protect
            //   plus a real InqBulkhead behaves as documented: bounded
            //   concurrency, fail-fast on saturation.
            eu.inqudium.imperative.bulkhead.InqBulkhead<Object[], Object> bulkhead =
                    RealBulkheadFixture.newBulkhead("real-sync-bh", 2, Duration.ZERO);

            // Given
            InqPipeline pipeline = InqPipeline.builder().shield(bulkhead).build();
            BarrierService proxy = pipeline.protect(
                    BarrierService.class, new DefaultBarrierService());
            CountDownLatch release = new CountDownLatch(1);

            // When — two virtual threads acquire permits and hold them
            Thread firstHolder = Thread.startVirtualThread(() -> proxy.enter(release));
            Thread secondHolder = Thread.startVirtualThread(() -> proxy.enter(release));
            // Wait until both permits are taken
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (bulkhead.concurrentCalls() < 2) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("two holders did not acquire permits in time");
                }
                Thread.onSpinWait();
            }

            try {
                // Then — third caller is rejected fail-fast
                assertThatThrownBy(() -> proxy.enter(new CountDownLatch(0)))
                        .isInstanceOf(InqBulkheadFullException.class)
                        .hasMessageContaining("Bulkhead 'real-sync-bh'");
            } finally {
                release.countDown();
                firstHolder.join();
                secondHolder.join();
            }

            // After release the bulkhead returns to its full pool
            assertThat(bulkhead.availablePermits()).isEqualTo(2);
            assertThat(bulkhead.concurrentCalls()).isZero();
        }
    }

    @Nested
    class PermitReleaseOnException {

        @Test
        void should_release_the_permit_when_the_target_throws() {
            // Given — fail-fast single-permit bulkhead
            eu.inqudium.imperative.bulkhead.InqBulkhead<Object[], Object> bulkhead =
                    RealBulkheadFixture.newBulkhead("real-exc-bh", 1, Duration.ZERO);
            InqPipeline pipeline = InqPipeline.builder().shield(bulkhead).build();
            ExceptionService proxy = pipeline.protect(
                    ExceptionService.class, new DefaultExceptionService());

            // When — first call throws from the target
            assertThatThrownBy(proxy::fail)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("intentional failure");

            // Then — the second call also reaches the target and throws.
            //   If the permit had leaked, the second call would have
            //   raised InqBulkheadFullException instead.
            assertThatThrownBy(proxy::fail)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("intentional failure");
            assertThat(bulkhead.availablePermits()).isEqualTo(1);
            assertThat(bulkhead.concurrentCalls()).isZero();
        }
    }

    @Nested
    class AsyncPermitSemantics {

        @Test
        void should_release_the_permit_when_the_async_stage_completes() throws Exception {
            // Given — single-permit async bulkhead
            eu.inqudium.imperative.bulkhead.InqBulkhead<Object[], Object> bulkhead =
                    RealBulkheadFixture.newBulkhead("real-async-bh", 1, Duration.ZERO);
            InqPipeline pipeline = InqPipeline.builder().shield(bulkhead).build();
            AsyncBarrierService proxy = pipeline.protect(
                    AsyncBarrierService.class, new DefaultAsyncBarrierService());

            // When — first call, downstream completes synchronously
            CompletableFuture<String> firstDownstream = new CompletableFuture<>();
            CompletableFuture<String> first = proxy.enterAsync(firstDownstream);
            firstDownstream.complete("first");
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");

            // Then — second call succeeds; the first permit was released
            CompletableFuture<String> secondDownstream = new CompletableFuture<>();
            CompletableFuture<String> second = proxy.enterAsync(secondDownstream);
            secondDownstream.complete("second");
            assertThat(second.get(1, TimeUnit.SECONDS)).isEqualTo("second");
            assertThat(bulkhead.availablePermits()).isEqualTo(1);
        }

        @Test
        void should_hold_the_permit_during_an_in_flight_async_call() {
            // What is to be tested? — While an async call is in flight
            //   (downstream stage still pending), a concurrent second
            //   call on the same single-permit bulkhead is rejected.
            //   The real InqBulkhead reports back-pressure as a
            //   synchronous throw (per InqBulkheadTest's
            //   executeAsync_throws_synchronously contract), and the
            //   proxy's async path does not convert it to a failed
            //   stage — the throw propagates synchronously through
            //   proxy.enterAsync(...).
            // Successful when? — The second call throws
            //   InqBulkheadFullException synchronously; once the first
            //   downstream completes, the permit returns.
            // Why important? — Pins the proxy-plus-real-bulkhead
            //   contract that async saturation is reported the same
            //   way as sync saturation; callers do not need two error
            //   channels.

            // Given
            eu.inqudium.imperative.bulkhead.InqBulkhead<Object[], Object> bulkhead =
                    RealBulkheadFixture.newBulkhead("real-async-bh", 1, Duration.ZERO);
            InqPipeline pipeline = InqPipeline.builder().shield(bulkhead).build();
            AsyncBarrierService proxy = pipeline.protect(
                    AsyncBarrierService.class, new DefaultAsyncBarrierService());

            // When — first call holds the permit (downstream stays pending)
            CompletableFuture<String> firstDownstream = new CompletableFuture<>();
            CompletableFuture<String> first = proxy.enterAsync(firstDownstream);
            assertThat(bulkhead.concurrentCalls()).isEqualTo(1);

            try {
                // Then — concurrent second call is rejected synchronously
                CompletableFuture<String> ignored = new CompletableFuture<>();
                assertThatThrownBy(() -> proxy.enterAsync(ignored))
                        .isInstanceOf(InqBulkheadFullException.class)
                        .hasMessageContaining("Bulkhead 'real-async-bh'");
                assertThat(bulkhead.concurrentCalls())
                        .as("rejected attempt must not consume a permit")
                        .isEqualTo(1);
            } finally {
                firstDownstream.complete("done");
                try {
                    first.get(2, TimeUnit.SECONDS);
                } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException ex) {
                    throw new AssertionError("first call did not complete after release", ex);
                }
            }

            // After the first call settles the permit is back
            assertThat(bulkhead.availablePermits()).isEqualTo(1);
        }
    }
}
