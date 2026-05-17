package eu.inqudium.proxy.folding;

import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.proxy.invocation.MethodInvoker;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncChainFolderTest {

    /**
     * Service-style target used by the folder tests. Records how many
     * times the method was invoked and what argument it last received,
     * which lets us pin the retry-semantics and arg-threading tests.
     *
     * <p>The {@code call} variant returns a completed stage; the
     * {@code boom} variant throws synchronously before constructing
     * a stage; the {@code failingStage} variant returns an
     * exceptionally-completed stage. Three target shapes cover the
     * three exception-handling axes the folder must support.</p>
     */
    public static final class CountingTarget {

        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicReference<String> lastArg = new AtomicReference<>();

        public CompletionStage<Object> call(String arg) {
            callCount.incrementAndGet();
            lastArg.set(arg);
            return CompletableFuture.completedFuture("result for " + arg);
        }

        public CompletionStage<Object> boom(String arg) {
            throw new IllegalStateException("sync boom for " + arg);
        }

        public CompletionStage<Object> failingStage(String arg) {
            callCount.incrementAndGet();
            lastArg.set(arg);
            CompletableFuture<Object> fut = new CompletableFuture<>();
            fut.completeExceptionally(new IOException("stage failure for " + arg));
            return fut;
        }

        public int callCount() {
            return callCount.get();
        }

        public String lastArg() {
            return lastArg.get();
        }
    }

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return CountingTarget.class.getDeclaredMethod(name, params);
    }

    /**
     * Recasts a list of {@link AsyncLayerAction} parameterised over
     * {@code <Object[], Object>} to the storage typing
     * {@code <Void, Object>} the folder accepts. Mirrors — at test
     * boundary only — the single unchecked cast that lives inside
     * {@link AsyncChainFolder#fold}, but the other direction.
     */
    @SuppressWarnings("unchecked")
    private static List<AsyncLayerAction<Void, Object>> asStorage(
            List<AsyncLayerAction<Object[], Object>> layers) {
        return (List<AsyncLayerAction<Void, Object>>) (List<?>) layers;
    }

    @Nested
    class EmptyChain {

        @Test
        void should_invoke_the_target_directly_when_layer_list_is_empty() throws Exception {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));

            // When
            FoldedAsyncChain chain = AsyncChainFolder.fold(List.of(), invoker);
            CompletionStage<Object> stage = chain.run(1L, 1L, new Object[]{"world"});

            // Then
            assertThat(stage.toCompletableFuture().get()).isEqualTo("result for world");
            assertThat(target.callCount()).isEqualTo(1);
        }

        @Test
        void should_return_a_completed_stage_for_a_target_that_returns_a_completed_stage() throws Exception {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));

            // When
            FoldedAsyncChain chain = AsyncChainFolder.fold(List.of(), invoker);
            CompletionStage<Object> stage = chain.run(1L, 1L, new Object[]{"x"});

            // Then
            assertThat(stage.toCompletableFuture().isDone()).isTrue();
            assertThat(stage.toCompletableFuture().get()).isEqualTo("result for x");
        }
    }

    @Nested
    class SingleLayer {

        @Test
        void should_route_the_call_through_the_single_layer_to_the_target() throws Exception {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));
            AtomicInteger layerHits = new AtomicInteger();

            AsyncLayerAction<Object[], Object> single =
                    (stackId, callId, args, next) -> {
                        layerHits.incrementAndGet();
                        return next.executeAsync(stackId, callId, args);
                    };

            // When
            FoldedAsyncChain chain = AsyncChainFolder.fold(
                    asStorage(List.of(single)), invoker);
            CompletionStage<Object> stage = chain.run(1L, 1L, new Object[]{"alice"});

            // Then
            assertThat(layerHits).hasValue(1);
            assertThat(stage.toCompletableFuture().get()).isEqualTo("result for alice");
        }

        @Test
        void should_propagate_the_target_s_async_result_through_the_layer() throws Exception {
            // What is to be tested?
            //   A layer that simply forwards to next must produce the
            //   target's completion result unchanged. The chain must
            //   not swallow or transform the value.
            // How will the test case be deemed successful and why?
            //   The completion value equals what the target's stage
            //   carried. Any in-place mapping by the folder would
            //   surface as a value mismatch.
            // Why is it important to test this test case?
            //   The folder must be transparent for transparent layers;
            //   a regression that injected a default value or remapped
            //   results would break every async caller.

            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));

            AsyncLayerAction<Object[], Object> passThrough =
                    (stackId, callId, args, next) -> next.executeAsync(stackId, callId, args);

            // When
            FoldedAsyncChain chain = AsyncChainFolder.fold(
                    asStorage(List.of(passThrough)), invoker);
            CompletionStage<Object> stage = chain.run(1L, 1L, new Object[]{"verbatim"});

            // Then
            assertThat(stage.toCompletableFuture().get()).isEqualTo("result for verbatim");
        }
    }

    @Nested
    class MultiLayer {

        @Test
        void should_route_the_call_through_layers_in_outer_to_inner_order() throws Exception {
            // What is to be tested?
            //   Two layers are entered in outer-to-inner order. This
            //   is the async analogue of SyncChainFolderTest's
            //   composition-order test.
            // How will the test case be deemed successful and why?
            //   The recorded entry order is [outer-pre, inner-pre,
            //   inner-post, outer-post]. The post markers run on the
            //   stage's completion via whenComplete.
            // Why is it important to test this test case?
            //   ADR-035 §4 fixes outer-to-inner storage order;
            //   reversing it changes layer semantics (e.g. retry
            //   inside circuit-breaker becomes circuit-breaker inside
            //   retry).

            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));
            List<String> order = new ArrayList<>();

            AsyncLayerAction<Object[], Object> outer =
                    (stackId, callId, args, next) -> {
                        order.add("outer-pre");
                        CompletionStage<Object> stage = next.executeAsync(stackId, callId, args);
                        return stage.whenComplete((r, e) -> order.add("outer-post"));
                    };

            AsyncLayerAction<Object[], Object> inner =
                    (stackId, callId, args, next) -> {
                        order.add("inner-pre");
                        CompletionStage<Object> stage = next.executeAsync(stackId, callId, args);
                        return stage.whenComplete((r, e) -> order.add("inner-post"));
                    };

            // When
            FoldedAsyncChain chain = AsyncChainFolder.fold(
                    asStorage(List.of(outer, inner)), invoker);
            chain.run(1L, 1L, new Object[]{"x"}).toCompletableFuture().get();

            // Then
            assertThat(order).containsExactly(
                    "outer-pre", "inner-pre", "inner-post", "outer-post");
        }

        @Test
        void should_propagate_arguments_through_all_layers() throws Exception {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));

            AsyncLayerAction<Object[], Object> passThrough =
                    (stackId, callId, args, next) -> next.executeAsync(stackId, callId, args);

            // When
            FoldedAsyncChain chain = AsyncChainFolder.fold(
                    asStorage(List.of(passThrough, passThrough)), invoker);
            chain.run(1L, 1L, new Object[]{"threaded"}).toCompletableFuture().get();

            // Then — the value reaches the target unchanged through both layers
            assertThat(target.lastArg()).isEqualTo("threaded");
        }
    }

    @Nested
    class StackAndCallIds {

        @Test
        void should_propagate_the_stack_id_to_every_layer() throws Exception {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));
            AtomicLong outerSeenStackId = new AtomicLong(-1);
            AtomicLong innerSeenStackId = new AtomicLong(-1);

            AsyncLayerAction<Object[], Object> outer =
                    (stackId, callId, args, next) -> {
                        outerSeenStackId.set(stackId);
                        return next.executeAsync(stackId, callId, args);
                    };

            AsyncLayerAction<Object[], Object> inner =
                    (stackId, callId, args, next) -> {
                        innerSeenStackId.set(stackId);
                        return next.executeAsync(stackId, callId, args);
                    };

            // When
            FoldedAsyncChain chain = AsyncChainFolder.fold(
                    asStorage(List.of(outer, inner)), invoker);
            chain.run(7777L, 1L, new Object[]{"x"}).toCompletableFuture().get();

            // Then
            assertThat(outerSeenStackId).hasValue(7777L);
            assertThat(innerSeenStackId).hasValue(7777L);
        }

        @Test
        void should_propagate_the_call_id_to_every_layer() throws Exception {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));
            AtomicLong outerSeenCallId = new AtomicLong(-1);
            AtomicLong innerSeenCallId = new AtomicLong(-1);

            AsyncLayerAction<Object[], Object> outer =
                    (stackId, callId, args, next) -> {
                        outerSeenCallId.set(callId);
                        return next.executeAsync(stackId, callId, args);
                    };

            AsyncLayerAction<Object[], Object> inner =
                    (stackId, callId, args, next) -> {
                        innerSeenCallId.set(callId);
                        return next.executeAsync(stackId, callId, args);
                    };

            // When
            FoldedAsyncChain chain = AsyncChainFolder.fold(
                    asStorage(List.of(outer, inner)), invoker);
            chain.run(1L, 4242L, new Object[]{"x"}).toCompletableFuture().get();

            // Then
            assertThat(outerSeenCallId).hasValue(4242L);
            assertThat(innerSeenCallId).hasValue(4242L);
        }
    }

    @Nested
    class RetryReentry {

        @Test
        void should_re_enter_the_target_correctly_when_layer_calls_next_multiple_times() throws Exception {
            // What is to be tested?
            //   A layer which invokes next.executeAsync(...) more than
            //   once actually causes the target to be invoked that
            //   many times. The async retry pattern composes via
            //   .thenCompose(...) so the second call sees the first's
            //   result before re-entering.
            // How will the test case be deemed successful and why?
            //   The target's call counter equals the number of
            //   next.executeAsync invocations (3).
            // Why is it important to test this test case?
            //   Primary retry-semantics correctness invariant on the
            //   async side. ADR-035's resilience guarantees depend on
            //   retry layers being able to re-enter the inner chain
            //   freely.

            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));

            AsyncLayerAction<Object[], Object> retryThrice =
                    (stackId, callId, args, next) ->
                            next.executeAsync(stackId, callId, args)
                                    .thenCompose(r -> next.executeAsync(stackId, callId, args))
                                    .thenCompose(r -> next.executeAsync(stackId, callId, args));

            // When
            FoldedAsyncChain chain = AsyncChainFolder.fold(
                    asStorage(List.of(retryThrice)), invoker);
            chain.run(1L, 1L, new Object[]{"retry"}).toCompletableFuture().get();

            // Then
            assertThat(target.callCount()).isEqualTo(3);
        }

        @Test
        void should_traverse_the_full_inner_chain_on_each_retry_iteration() throws Exception {
            // What is to be tested?
            //   Every inner layer is re-entered on each retry pass.
            //   Strict version of the previous test: not only the
            //   target, but every layer between the retry layer and
            //   the target must run on every retry iteration.
            //
            //   The fixture's retry layer waits for the first stage to
            //   complete exceptionally, then via .exceptionallyCompose
            //   issues a fresh next.executeAsync — exactly how a real
            //   async retry decorator composes.
            // How will the test case be deemed successful and why?
            //   The inner layer's hit-counter is 2 (one per retry
            //   pass), and the target's call counter is 2. A stateful
            //   walker that advanced past the inner layer on the
            //   second call would surface as innerLayerCallCount==1.
            // Why is it important to test this test case?
            //   Correctness-pinning test for the closures-per-depth
            //   fold on the async side (ARCHITECTURE.md §7.3). It
            //   must fail if anyone replaces the recursive fold with
            //   a walker that mutates an index.

            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("failingStage", String.class));
            AtomicInteger innerLayerCallCount = new AtomicInteger();
            AtomicInteger attempts = new AtomicInteger();

            AsyncLayerAction<Object[], Object> retryLayer =
                    (stackId, callId, args, next) -> {
                        attempts.incrementAndGet();
                        CompletionStage<Object> first = next.executeAsync(stackId, callId, args);
                        return first.handle((r, e) -> {
                            if (e == null) {
                                return CompletableFuture.<Object>completedFuture(r);
                            }
                            attempts.incrementAndGet();
                            // retry: call next.executeAsync again, recover the
                            // failure into a sentinel value so the final stage
                            // completes normally for the assertion below.
                            return next.executeAsync(stackId, callId, args)
                                    .exceptionally(t -> "recovered")
                                    .toCompletableFuture();
                        }).thenCompose(s -> s);
                    };

            AsyncLayerAction<Object[], Object> innerLayer =
                    (stackId, callId, args, next) -> {
                        innerLayerCallCount.incrementAndGet();
                        return next.executeAsync(stackId, callId, args);
                    };

            // When
            FoldedAsyncChain chain = AsyncChainFolder.fold(
                    asStorage(List.of(retryLayer, innerLayer)), invoker);
            Object value = chain.run(1L, 1L, new Object[]{"hello"})
                    .toCompletableFuture().get();

            // Then — the inner layer must be re-entered for the retry iteration.
            assertThat(innerLayerCallCount).hasValue(2);
            assertThat(target.callCount()).isEqualTo(2);
            assertThat(attempts).hasValue(2);
            assertThat(value).isEqualTo("recovered");
        }
    }

    @Nested
    class ExceptionHandling {

        @Test
        void should_wrap_a_sync_target_throw_in_a_failed_future() throws NoSuchMethodException {
            // What is to be tested?
            //   When the target throws synchronously (before producing
            //   a stage), the folder catches that throwable and
            //   produces a failed CompletionStage. This makes the
            //   async caller's error channel uniform: always a stage,
            //   never a sync throw from a method that promised a
            //   CompletionStage return type.
            // How will the test case be deemed successful and why?
            //   The returned stage is completed exceptionally with the
            //   target's exception. The folder does not propagate the
            //   throw synchronously to its caller.
            // Why is it important to test this test case?
            //   Without this guard, an async caller would need two
            //   error-handling branches (try/catch around the call
            //   itself, plus .exceptionally on the stage). Pins the
            //   single-channel contract.

            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("boom", String.class));

            // When
            FoldedAsyncChain chain = AsyncChainFolder.fold(List.of(), invoker);
            CompletionStage<Object> stage = chain.run(1L, 1L, new Object[]{"x"});

            // Then
            CompletableFuture<Object> fut = stage.toCompletableFuture();
            assertThat(fut.isCompletedExceptionally()).isTrue();
            assertThatThrownBy(fut::get)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sync boom for x");
        }

        @Test
        void should_propagate_a_runtime_exception_from_a_layer_synchronously() throws NoSuchMethodException {
            // What is to be tested?
            //   A RuntimeException raised by a layer BEFORE calling
            //   next.executeAsync propagates synchronously to the
            //   caller of run(...). It does NOT get wrapped in a
            //   failed stage — sync layer faults (e.g. permit-acquire
            //   failure) reach InqInvocationHandler's catch block and
            //   are classified by ExceptionClassifier per ADR-035 §10.
            // How will the test case be deemed successful and why?
            //   chain.run throws the layer's RuntimeException directly,
            //   not a wrapped variant.
            // Why is it important to test this test case?
            //   Pins the two-channel separation between sync-layer
            //   faults and async-stage failures (ARCHITECTURE.md §7.4).

            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));

            AsyncLayerAction<Object[], Object> throwsPre =
                    (stackId, callId, args, next) -> {
                        throw new IllegalStateException("pre boom");
                    };

            FoldedAsyncChain chain = AsyncChainFolder.fold(
                    asStorage(List.of(throwsPre)), invoker);

            // When / Then
            assertThatThrownBy(() -> chain.run(1L, 1L, new Object[]{"x"}))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("pre boom");

            // And the target must not have run.
            assertThat(target.callCount()).isZero();
        }

        @Test
        void should_let_async_failures_in_the_returned_stage_propagate_naturally() throws NoSuchMethodException {
            // What is to be tested?
            //   When the target returns an exceptionally-completed
            //   stage, the folder must NOT swallow or re-wrap it. The
            //   caller observes the same exception via stage.get()
            //   wrapped in ExecutionException per JDK conventions.
            // How will the test case be deemed successful and why?
            //   stage.get() throws ExecutionException whose cause is
            //   the IOException the target's stage carried.
            // Why is it important to test this test case?
            //   Pins the "async failures propagate as JDK conventions
            //   dictate" contract (ARCHITECTURE.md §9, §10): the
            //   classifier does not touch in-stage failures.

            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("failingStage", String.class));

            // When
            FoldedAsyncChain chain = AsyncChainFolder.fold(List.of(), invoker);
            CompletionStage<Object> stage = chain.run(1L, 1L, new Object[]{"x"});

            // Then
            CompletableFuture<Object> fut = stage.toCompletableFuture();
            assertThat(fut.isCompletedExceptionally()).isTrue();
            assertThatThrownBy(fut::get)
                    .hasCauseInstanceOf(IOException.class)
                    .hasMessageContaining("stage failure for x");
        }
    }
}
