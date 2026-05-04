package eu.inqudium.bulkhead.integration.aspectj;

import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.aspectj.lang.Aspects;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the AspectJ-native bulkhead example.
 *
 * <p>The tests exercise the example application — they construct a plain {@link OrderService}
 * and call its methods directly, just as {@link Main} does. The compile-time-woven
 * {@link OrderBulkheadAspect} binds the {@link eu.inqudium.annotation.InqBulkhead @InqBulkhead}
 * annotation, reads its {@code value()}, and routes every annotated invocation through the
 * matching bulkhead transparently, so the test code does not build a runtime, a pipeline,
 * a terminal, or an aspect manually — that is the structural payoff of CTW: tests look like
 * application code.
 *
 * <p>To inspect the bulkhead's permit count, the tests reach the aspect singleton through
 * the AspectJ-generated {@link Aspects#aspectOf(Class)} accessor. That is the canonical way
 * for any code outside the woven join points to talk to a CTW aspect, and matches what
 * {@code Main} does in {@code demonstrateAsyncSaturation}.
 *
 * <h3>Singleton state across tests</h3>
 * <p>The aspect is an AspectJ singleton — one instance per classloader, owning one runtime
 * for the lifetime of the JVM. The runtime is therefore <em>shared</em> across every test
 * method in this class; each test asserts that {@code availablePermits()} returns to the
 * configured limit (two) before it ends, so that subsequent tests start from a clean
 * baseline. This mirrors the production singleton-aspect pattern: a regression that left a
 * permit dangling would surface as a deterministic failure on a follow-up test.
 *
 * <h3>Lifecycle group: intentionally disabled</h3>
 * <p>The function-based and proxy-based modules pin a "close one runtime, build a fresh one"
 * lifecycle property; that property does not translate idiomatically to the CTW pattern,
 * because the singleton aspect owns the runtime for the JVM's lifetime — there is no second
 * runtime to build inside one test class. The lifecycle group below documents that
 * difference with an explicit {@link Disabled} marker rather than failing tests; the
 * lifecycle property is pinned by the function and proxy modules, and the corresponding
 * runtime-mutation property at the aspect-pipeline level is pinned by
 * {@link eu.inqudium.bulkhead.integration.aspectj.lifecycle.BulkheadAspectLifecycleTest}.
 */
@DisplayName("AspectJ-native bulkhead example")
class OrderServiceAspectJExampleTest {

    private static InqBulkhead<Object, Object> bulkhead() {
        return Aspects.aspectOf(OrderBulkheadAspect.class).bulkhead();
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        void place_order_succeeds_through_the_woven_aspect() {
            // Given: a plain OrderService whose @InqBulkhead-annotated methods have been
            // woven by ajc to enter OrderBulkheadAspect first
            OrderService service = new OrderService();

            // When: a single order is placed through the woven service
            String result = service.placeOrder("Widget");

            // Then: the original method's reply propagates back unchanged through the aspect
            assertThat(result).isEqualTo("ordered:Widget");
        }

        @Test
        void place_order_releases_the_permit_after_each_call() {
            // What is to be tested: the bulkhead releases the acquired permit at the end of
            // every woven sync call, so that sequential calls never deplete the permit pool.
            // How will the test be deemed successful and why: availablePermits() reads two
            // (the configured limit) before and after each call. If the aspect's sync chain
            // failed to release the permit on the synchronous return path, the count would
            // drop monotonically.
            // Why is it important: a leaked permit on the happy path is the most
            // user-impacting class of bulkhead defect — the protection mechanism turns into
            // a cliff for every subsequent caller. The aspect adds a layer of advice
            // dispatch on top of the bulkhead's own release contract; this test pins that
            // the additional layer does not perturb release.
            OrderService service = new OrderService();
            InqBulkhead<Object, Object> bh = bulkhead();

            // Given: a fully-released bulkhead at the configured limit
            assertThat(bh.availablePermits()).isEqualTo(2);

            // When: the woven sync method is invoked multiple times sequentially
            for (int i = 0; i < 5; i++) {
                service.placeOrder("item-" + i);

                // Then: the permit count returns to two after every call
                assertThat(bh.availablePermits())
                        .as("after call %d", i)
                        .isEqualTo(2);
            }
        }

        @Test
        void async_place_order_succeeds_through_the_woven_aspect() {
            // Given: a plain OrderService whose @InqBulkhead-annotated async methods have
            // been woven by ajc. The aspect reads the CompletionStage return type and routes
            // the invocation through the async pipeline chain.
            OrderService service = new OrderService();

            // When: a single async order is placed through the woven service
            String result = service.placeOrderAsync("Apple")
                    .toCompletableFuture().join();

            // Then: the original method's reply propagates back unchanged and the permit
            // has returned to the pool by the time the stage completes
            assertThat(result).isEqualTo("async-ordered:Apple");
            assertThat(bulkhead().availablePermits()).isEqualTo(2);
        }

        @Test
        void async_place_order_releases_the_permit_after_each_call() {
            // What is to be tested: the aspect's async chain releases the acquired permit on
            // stage completion, so sequential async calls never deplete the permit pool. The
            // async release fires from the bulkhead's whenComplete callback rather than from
            // a finally clause, so it earns its own coverage even though the user-visible
            // property mirrors the sync case.
            // How will the test be deemed successful and why: availablePermits() reads two
            // before and after every joined async call. If the aspect's async dispatch
            // swallowed the whenComplete release callback, the count would drop monotonically.
            // Why is it important: a leaked permit on the async happy path is just as
            // user-impacting as on the sync path; it would silently throttle every caller
            // after the pool drains. ADR-020's release contract requires the callback fires
            // on both success and failure terminations, regardless of the dispatch mechanism.
            OrderService service = new OrderService();
            InqBulkhead<Object, Object> bh = bulkhead();

            // Given: a fully-released bulkhead at the configured limit
            assertThat(bh.availablePermits()).isEqualTo(2);

            // When: the woven async method is invoked multiple times sequentially, joining
            // each stage before the next call
            for (int i = 0; i < 5; i++) {
                service.placeOrderAsync("item-" + i)
                        .toCompletableFuture().join();

                // Then: the permit count returns to two after every joined stage
                assertThat(bh.availablePermits())
                        .as("after async call %d", i)
                        .isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("Saturation")
    class Saturation {

        @Test
        void concurrent_calls_above_the_limit_are_rejected_with_InqBulkheadFullException() throws InterruptedException {
            // What is to be tested: when both permits are held by concurrent in-flight woven
            // calls, a third synchronous call cannot acquire a permit and is rejected with
            // InqBulkheadFullException — the same contract the bulkhead enforces under direct
            // decoration also holds when the bulkhead sits behind the woven aspect.
            // How will the test be deemed successful and why: two virtual-thread holders enter
            // placeOrderHolding through the woven method and decrement their acquired latches;
            // a third woven call from the main thread is rejected synchronously; both holders
            // complete cleanly once the release latch fires.
            // Why is it important: saturation rejection is the bulkhead's reason to exist —
            // a regression here means either the aspect did not actually wire the bulkhead in
            // (no rejection at all) or the aspect re-wrapped the rejection type, breaking the
            // user-facing contract.
            OrderService service = new OrderService();
            InqBulkhead<Object, Object> bh = bulkhead();

            CountDownLatch holderAcquired1 = new CountDownLatch(1);
            CountDownLatch holderAcquired2 = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Throwable> holderErrors = new ArrayList<>();

            // Given: two virtual threads each holding a permit through the woven method
            Thread holder1 = Thread.startVirtualThread(() -> {
                try {
                    service.placeOrderHolding(holderAcquired1, release);
                } catch (Throwable t) {
                    holderErrors.add(t);
                }
            });
            Thread holder2 = Thread.startVirtualThread(() -> {
                try {
                    service.placeOrderHolding(holderAcquired2, release);
                } catch (Throwable t) {
                    holderErrors.add(t);
                }
            });

            assertThat(holderAcquired1.await(5, TimeUnit.SECONDS))
                    .as("holder 1 must enter the body").isTrue();
            assertThat(holderAcquired2.await(5, TimeUnit.SECONDS))
                    .as("holder 2 must enter the body").isTrue();

            try {
                // When / Then: a third sync call through the woven method is rejected
                // synchronously with the bulkhead's own exception
                assertThatThrownBy(() -> service.placeOrder("Saturated"))
                        .isInstanceOf(InqBulkheadFullException.class);
            } finally {
                release.countDown();
                holder1.join();
                holder2.join();
            }

            assertThat(holderErrors)
                    .as("holders must release without errors").isEmpty();
            assertThat(bh.availablePermits())
                    .as("permits return to the configured limit after holders release")
                    .isEqualTo(2);
        }

        @Test
        void concurrent_async_calls_above_the_limit_are_rejected_through_a_failed_stage() {
            // What is to be tested: when both permits are held by in-flight async calls (the
            // permits were acquired synchronously on the calling thread when the woven async
            // method returned its still-pending stage), a third async call cannot acquire a
            // permit and is rejected with InqBulkheadFullException. Channel detail:
            // HybridAspectPipelineTerminal's documented uniform-error-channel policy captures
            // any synchronous throw on the async path into a failed CompletionStage rather
            // than letting it propagate to the caller. The exception is the same; the
            // surface differs from the function-based decoration path, where the throw
            // propagates synchronously, and matches the proxy-based example's behaviour.
            // How will the test be deemed successful and why: two stage holders each consume
            // a permit; the third woven async call returns a CompletionStage that, when
            // joined, throws CompletionException whose cause is InqBulkheadFullException.
            // After releasing the holders, both permits return to the pool.
            // Why is it important: this test pins both halves of the aspect's async-saturation
            // contract — that the bulkhead actually rejects, and that the rejection surfaces
            // through the failed-stage channel. A regression to either half (no rejection, or
            // a synchronous throw escaping the aspect's error normalization) would break the
            // example's documented contract.
            OrderService service = new OrderService();
            InqBulkhead<Object, Object> bh = bulkhead();

            CompletableFuture<Void> release = new CompletableFuture<>();

            // Given: two in-flight async holders, each holding a permit while their stages
            // remain pending
            CompletionStage<String> holder1 = service.placeOrderHoldingAsync(release);
            CompletionStage<String> holder2 = service.placeOrderHoldingAsync(release);

            assertThat(bh.concurrentCalls())
                    .as("both async holders must hold a permit synchronously")
                    .isEqualTo(2);
            assertThat(bh.availablePermits()).isZero();

            try {
                // When: a third async call attempts to acquire
                CompletionStage<String> rejected = service.placeOrderAsync("Saturated");

                // Then: the rejection arrives through the failed-stage channel
                assertThatThrownBy(() -> rejected.toCompletableFuture().join())
                        .isInstanceOf(CompletionException.class)
                        .hasCauseInstanceOf(InqBulkheadFullException.class);
            } finally {
                release.complete(null);
                holder1.toCompletableFuture().join();
                holder2.toCompletableFuture().join();
            }

            assertThat(bh.availablePermits())
                    .as("permits return to the configured limit after holders release")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Shared strategy")
    class SharedStrategy {

        @Test
        void sync_and_async_calls_share_the_same_bulkhead_strategy_through_the_woven_aspect() {
            // What is to be tested: the aspect's sync and async dispatch paths route through
            // the same bulkhead instance and therefore the same permit pool. A sync hold
            // consumes one permit; a concurrent async call (also routed through the woven
            // method) observes one available permit and acquires successfully (since
            // maxConcurrentCalls is two). Both paths read and update the same concurrentCalls
            // count.
            // How will the test be deemed successful and why: while a sync holder is in
            // flight (concurrentCalls == 1), an async call through the woven method is
            // admitted and returns its value; concurrentCalls reads two while both are
            // mid-flight, then drops back to zero after both release. If the aspect ever
            // wired sync and async dispatch to separate bulkheads, the async call would
            // observe two free permits regardless of the sync holder, and the count would
            // never read two simultaneously.
            // Why is it important: the function-based and proxy-based examples' SharedStrategy
            // tests pin the shared-strategy property at their respective surfaces; this test
            // pins the same property at the woven-method dispatch surface. The aspect is a
            // different surface that could regress independently — for example, by composing
            // sync and async chains over a stale lookup of separate bulkheads — even if the
            // underlying decorate APIs continued to share their strategy. ADR-033's
            // one-bulkhead-two-pipeline-shapes property is what HybridAspectPipelineTerminal
            // depends on; pinning it at the dispatch surface is what guarantees the woven
            // aspect honors that property end-to-end.
            OrderService service = new OrderService();
            InqBulkhead<Object, Object> bh = bulkhead();

            CountDownLatch holderAcquired = new CountDownLatch(1);
            CountDownLatch syncRelease = new CountDownLatch(1);
            List<Throwable> holderErrors = new ArrayList<>();

            // Given: one virtual-thread sync holder occupies one permit through the woven
            // method
            Thread holder = Thread.startVirtualThread(() -> {
                try {
                    service.placeOrderHolding(holderAcquired, syncRelease);
                } catch (Throwable t) {
                    holderErrors.add(t);
                }
            });

            try {
                assertThat(holderAcquired.await(5, TimeUnit.SECONDS))
                        .as("sync holder must enter the body").isTrue();
                assertThat(bh.concurrentCalls())
                        .as("sync holder consumed one permit on the shared strategy")
                        .isEqualTo(1);

                // When: an async holding call enters in parallel through the same woven
                // method
                CompletableFuture<Void> asyncRelease = new CompletableFuture<>();
                CompletionStage<String> asyncHolder =
                        service.placeOrderHoldingAsync(asyncRelease);

                // Then: the async permit was acquired against the same pool — both paths
                // mid-flight pushes the count to two
                assertThat(bh.concurrentCalls())
                        .as("sync and async holders share one strategy through the aspect")
                        .isEqualTo(2);
                assertThat(bh.availablePermits()).isZero();

                // When: both paths release
                asyncRelease.complete(null);
                String asyncResult = asyncHolder.toCompletableFuture().join();
                syncRelease.countDown();
                holder.join();

                // Then: the shared pool drains back to the configured limit
                assertThat(asyncResult).isEqualTo("async-released");
                assertThat(holderErrors)
                        .as("sync holder must release without errors").isEmpty();
                assertThat(bh.concurrentCalls()).isZero();
                assertThat(bh.availablePermits()).isEqualTo(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                syncRelease.countDown();
            }
        }
    }

    @Nested
    @DisplayName("Annotation value drives bulkhead resolution")
    class AnnotationValueDrivenResolution {

        @Test
        void aspect_resolves_the_bulkhead_named_in_the_inq_bulkhead_value() {
            // What is to be tested: the aspect's dispatch is driven by the annotation's
            // value(), not by a hardcoded bulkhead name in the aspect itself. The annotation
            // declares @InqBulkhead("orderBh") on every method (via BULKHEAD_NAME); the
            // aspect's pointcut binds the annotation, the advice reads value(), and the
            // resolved bulkhead's name() must equal what the annotation carried.
            // How will the test be deemed successful and why: a woven invocation succeeds
            // and returns its expected value (proving the dispatch reached a real bulkhead),
            // and the bulkhead the aspect publishes via aspectOf().bulkhead() carries the
            // same name that the annotation's value() carries (proving the connection).
            // Why is it important: a future refactor that accidentally short-circuits the
            // value()-read — for example, by hardcoding "orderBh" inside the advice or by
            // matching the annotation only as a marker — would still pass every other test
            // in this class because there is only one bulkhead. This test pins the explicit
            // connection so a regression to a hardcoded path cannot slip through silently.
            // Given — a plain OrderService whose methods are woven by ajc; the aspect
            // singleton has been (or will be) constructed by AspectJ on first use.
            OrderService service = new OrderService();
            OrderBulkheadAspect aspect = Aspects.aspectOf(OrderBulkheadAspect.class);

            // When — invoking a woven method whose @InqBulkhead value is BULKHEAD_NAME
            String result = service.placeOrder("Widget");

            // Then — the woven call returned its expected value, the bulkhead the aspect
            // resolved is named by the annotation's value(), and that value() literal is
            // the project-wide constant "orderBh".
            assertThat(result).isEqualTo("ordered:Widget");
            assertThat(aspect.bulkhead().name())
                    .as("the aspect resolved the bulkhead named in the annotation value()")
                    .isEqualTo(BulkheadConfig.BULKHEAD_NAME);
            assertThat(BulkheadConfig.BULKHEAD_NAME)
                    .as("BULKHEAD_NAME is what InqBulkhead.value() carries on every annotated method")
                    .isEqualTo("orderBh");
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @Disabled("""
                The aspect owns its runtime as a CTW singleton — there is no second runtime to \
                build inside one test class, so a close-and-rebuild lifecycle test does not \
                translate idiomatically to this pattern. The lifecycle property at the \
                runtime level is pinned by the function and proxy example modules, and the \
                runtime-mutation property at the aspect-pipeline level is pinned by \
                BulkheadAspectLifecycleTest in this module.""")
        void the_runtime_can_be_closed_and_a_fresh_one_built_in_the_same_test_class() {
            // Intentionally empty — see @Disabled reason above. The placeholder method exists
            // so the lifecycle group is documented in the test report instead of silently
            // missing; a reader scanning the suite sees the explicit decision rather than a
            // gap.
        }
    }

    @Nested
    @DisplayName("RuntimeConfigChange")
    class RuntimeConfigChange {

        /**
         * Drive the AdminService's promotion cycle through the singleton runtime and ensure
         * the runtime is restored to its baseline (balanced/2) on every exit path. The
         * AspectJ aspect is a JVM-singleton: the runtime survives across every test method
         * in this class, so a runtime-config-change test that left the bulkhead patched at
         * permissive/50 would break every subsequent test that assumes the configured limit
         * is two. The {@code finally} guard is therefore not optional — it is what reconciles
         * the per-test promotion cycle with the singleton-runtime invariant the rest of this
         * suite depends on.
         *
         * <p>This is the structural deviation from the function-based and proxy-based
         * RuntimeConfigChange tests: those modules build a fresh runtime in {@code @BeforeEach}
         * (function) or open a {@code try-with-resources} runtime inside the test body
         * (proxy), so a leak there only affects the failing test. Here a leak would
         * cascade — hence the explicit restore.
         */
        @Test
        void a_full_promotion_cycle_changes_saturation_behavior_live() throws InterruptedException {
            // What is to be tested: the AdminService's promotion cycle observably changes the
            // bulkhead's saturation behaviour live, without rebuilding the aspect, the
            // pipeline, or the terminal. Three sequential phases through the singleton aspect:
            //   1. balanced/2 — two holders saturate, third woven call rejected.
            //   2. permissive/50 (after startSellPromotion) — five concurrent async holders
            //      all succeed without rejection.
            //   3. balanced/2 (after endSellPromotion) — saturation restored, third woven
            //      call rejected again.
            // How will the test be deemed successful and why: the third call in phase 1 and
            // phase 3 throws InqBulkheadFullException; the five holders in phase 2 all
            // produce their result without any exception. The same OrderService instance —
            // and therefore the same woven dispatch path — is used across all three phases,
            // proving the patch works through the aspect, not around it.
            // Why is it important: this is the operational headline of sub-step 6.E — that a
            // runtime patch flows through to the bulkhead's live behaviour without the aspect
            // needing to be rebuilt. A regression here would mean operators who patch a
            // bulkhead through runtime.update(...) silently get no effect at the woven
            // dispatch path. The aspect singleton makes this property load-bearing for every
            // call site in a JVM-wide CTW deployment.
            OrderService service = new OrderService();
            OrderBulkheadAspect aspect = Aspects.aspectOf(OrderBulkheadAspect.class);
            InqBulkhead<Object, Object> bh = aspect.bulkhead();
            AdminService admin = new AdminService(aspect.runtime());

            // Sanity: the singleton runtime starts at balanced/2 (the configured baseline).
            forceHotPhase(service);
            assertThat(bh.availablePermits())
                    .as("singleton baseline before the promotion cycle")
                    .isEqualTo(2);

            try {
                // === Phase 1: balanced/2 — third call rejected ===
                runSaturationCycle(service, bh, 2, /*expectRejection*/ true);

                // === Phase 2: permissive/50 — five holders all succeed ===
                admin.startSellPromotion();
                runFiveAsyncHoldersSuccessfully(service, bh);

                // === Phase 3: balanced/2 again — third call rejected again ===
                admin.endSellPromotion();
                runSaturationCycle(service, bh, 2, /*expectRejection*/ true);
            } finally {
                // Restore the singleton runtime to its baseline so subsequent tests in this
                // class observe balanced/2 even if any assertion above failed mid-flight.
                // Re-issuing balanced/2 from balanced/2 is a no-op patch.
                admin.endSellPromotion();
            }
        }

        @Test
        void available_permits_jump_immediately_when_promotion_starts_and_ends() {
            // What is to be tested: availablePermits() on the bulkhead handle reflects a
            // runtime patch synchronously and without lag, even when the bulkhead sits behind
            // the woven aspect. The three reads (initial, after start, after end) capture the
            // bulkhead's permit ceiling at each phase.
            // How will the test be deemed successful and why: the read at the singleton
            // baseline returns 2 (balanced default); the read after startSellPromotion returns
            // 50 (the permissive patch); the read after endSellPromotion returns 2 again.
            // Why is it important: an operator's observability contract for a runtime patch
            // is "what I see right after the patch is what's true now". If the aspect's
            // pipeline ever held a stale snapshot of the bulkhead's strategy — for example,
            // by caching the live tuner across patches — the permits read would lag the
            // patch and operators would not be able to confirm a successful change from a
            // dashboard or admin endpoint. The aspect adds a layer of cached terminals on
            // top of the bulkhead; this test pins that the cache does not interpose stale
            // strategy state.
            OrderService service = new OrderService();
            OrderBulkheadAspect aspect = Aspects.aspectOf(OrderBulkheadAspect.class);
            InqBulkhead<Object, Object> bh = aspect.bulkhead();
            AdminService admin = new AdminService(aspect.runtime());
            forceHotPhase(service);

            try {
                // Given: the singleton runtime under the balanced/2 default
                assertThat(bh.availablePermits())
                        .as("singleton baseline before the patch")
                        .isEqualTo(2);

                // When: the promotion patch is applied
                admin.startSellPromotion();

                // Then: the new permit ceiling is observable immediately
                assertThat(bh.availablePermits())
                        .as("permit ceiling after startSellPromotion (permissive/50)")
                        .isEqualTo(50);

                // When: the promotion patch is reversed
                admin.endSellPromotion();

                // Then: the original permit ceiling is restored immediately
                assertThat(bh.availablePermits())
                        .as("permit ceiling after endSellPromotion (balanced/2)")
                        .isEqualTo(2);
            } finally {
                // Restore the singleton runtime to its baseline so subsequent tests in this
                // class observe balanced/2 even if the third assertion failed mid-flight.
                admin.endSellPromotion();
            }
        }

        /**
         * Drive {@code holderCount} virtual-thread holders into the bulkhead through the
         * woven service, attempt one extra synchronous woven call, and assert the rejection
         * (or success) of that extra call.
         */
        private void runSaturationCycle(OrderService service, InqBulkhead<?, ?> bh,
                                        int holderCount, boolean expectRejection)
                throws InterruptedException {
            CountDownLatch[] acquired = new CountDownLatch[holderCount];
            CountDownLatch release = new CountDownLatch(1);
            Thread[] holders = new Thread[holderCount];
            List<Throwable> holderErrors = new ArrayList<>();
            for (int i = 0; i < holderCount; i++) {
                acquired[i] = new CountDownLatch(1);
                CountDownLatch acq = acquired[i];
                holders[i] = Thread.startVirtualThread(() -> {
                    try {
                        service.placeOrderHolding(acq, release);
                    } catch (Throwable t) {
                        holderErrors.add(t);
                    }
                });
            }

            try {
                for (CountDownLatch a : acquired) {
                    assertThat(a.await(5, TimeUnit.SECONDS))
                            .as("each holder must enter the body").isTrue();
                }

                if (expectRejection) {
                    assertThatThrownBy(() -> service.placeOrder("Saturated"))
                            .isInstanceOf(InqBulkheadFullException.class);
                } else {
                    assertThat(service.placeOrder("Saturated")).isEqualTo("ordered:Saturated");
                }
            } finally {
                release.countDown();
                for (Thread t : holders) {
                    t.join();
                }
            }

            assertThat(holderErrors).as("holders must release without errors").isEmpty();
            assertThat(bh.availablePermits())
                    .as("permits return to the configured limit after holders release")
                    .isEqualTo(holderCount);
        }

        /**
         * Run five concurrent async holders against the bulkhead through the woven service
         * and confirm none is rejected. Five is well below permissive/50, so the success is
         * structural — none of the calls runs out of permits.
         */
        private void runFiveAsyncHoldersSuccessfully(OrderService service,
                                                     InqBulkhead<Object, Object> bh) {
            CompletableFuture<Void> release = new CompletableFuture<>();
            List<CompletionStage<String>> holders = new ArrayList<>();
            AtomicReference<Throwable> firstError = new AtomicReference<>();
            for (int i = 0; i < 5; i++) {
                try {
                    holders.add(service.placeOrderHoldingAsync(release));
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                }
            }

            assertThat(firstError.get())
                    .as("no holder should be rejected under permissive/50")
                    .isNull();
            assertThat(holders).hasSize(5);
            assertThat(bh.concurrentCalls())
                    .as("five async holders all hold permits at once")
                    .isEqualTo(5);

            release.complete(null);
            for (CompletionStage<String> h : holders) {
                assertThat(h.toCompletableFuture().join()).isEqualTo("async-released");
            }
            assertThat(bh.concurrentCalls()).isZero();
        }

        /**
         * Force the bulkhead into its hot phase by issuing one no-op woven order. The
         * handle's {@code availablePermits()} returns the live strategy's value once hot;
         * before that it returns the cold-state limit from the snapshot. The aspect's
         * singleton runtime may already be hot if a prior test in this class has run, but
         * driving one no-op woven call here removes any cold/hot ambiguity from the
         * subsequent assertions regardless of test ordering.
         */
        private void forceHotPhase(OrderService service) {
            service.placeOrder("warm-up");
        }
    }
}
