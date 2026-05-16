package eu.inqudium.proxy.folding;

import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.proxy.invocation.MethodInvoker;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncChainFolderTest {

    /**
     * Service-style target used by the folder tests. Records how many
     * times the method was invoked and what argument it last received,
     * which lets us pin the retry-semantics and arg-threading tests.
     */
    public static final class CountingTarget {

        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicReference<String> lastArg = new AtomicReference<>();

        public Object call(String arg) {
            callCount.incrementAndGet();
            lastArg.set(arg);
            return "result for " + arg;
        }

        public Object boom(String arg) throws IOException {
            throw new IOException("target boom for " + arg);
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
     * Recasts a list of {@link LayerAction} parameterised over
     * {@code <Object[], Object>} to the storage typing
     * {@code <Void, Object>} the folder accepts. This mirrors — at
     * test boundary only — the single unchecked cast that lives
     * inside {@link SyncChainFolder#fold}, but the other direction.
     */
    @SuppressWarnings("unchecked")
    private static List<LayerAction<Void, Object>> asStorage(
            List<LayerAction<Object[], Object>> layers) {
        return (List<LayerAction<Void, Object>>) (List<?>) layers;
    }

    @Nested
    class HappyPath {

        @Test
        void should_invoke_the_target_directly_when_layer_list_is_empty() throws Throwable {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));

            // When
            FoldedSyncChain chain = SyncChainFolder.fold(List.of(), invoker);
            Object result = chain.run(1L, 1L, new Object[]{"world"});

            // Then
            assertThat(result).isEqualTo("result for world");
            assertThat(target.callCount()).isEqualTo(1);
        }

        @Test
        void should_wrap_the_target_call_in_a_single_layer() throws Throwable {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));
            AtomicInteger layerHits = new AtomicInteger();

            LayerAction<Object[], Object> single =
                    (stackId, callId, args, next) -> {
                        layerHits.incrementAndGet();
                        return next.execute(stackId, callId, args);
                    };

            // When
            FoldedSyncChain chain = SyncChainFolder.fold(
                    asStorage(List.of(single)), invoker);
            Object result = chain.run(1L, 1L, new Object[]{"alice"});

            // Then
            assertThat(layerHits).hasValue(1);
            assertThat(result).isEqualTo("result for alice");
        }

        @Test
        void should_compose_two_layers_outer_first() throws Throwable {
            // What is to be tested?
            //   That two layers are entered in outer-to-inner order — i.e. the
            //   first element of the list runs first, then delegates to the
            //   second, which delegates to the target.
            // How will the test case be deemed successful and why?
            //   The recorded entry order is [outer, inner], confirming the
            //   folder threads layer 0 outermost.
            // Why is it important to test this test case?
            //   ADR-035 §4 fixes outer-to-inner storage order; reversing it
            //   would silently swap layer semantics (e.g. retry inside CB
            //   becomes CB inside retry, which has very different behaviour).

            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));
            List<String> order = new ArrayList<>();

            LayerAction<Object[], Object> outer =
                    (stackId, callId, args, next) -> {
                        order.add("outer-pre");
                        Object r = next.execute(stackId, callId, args);
                        order.add("outer-post");
                        return r;
                    };

            LayerAction<Object[], Object> inner =
                    (stackId, callId, args, next) -> {
                        order.add("inner-pre");
                        Object r = next.execute(stackId, callId, args);
                        order.add("inner-post");
                        return r;
                    };

            // When
            FoldedSyncChain chain = SyncChainFolder.fold(
                    asStorage(List.of(outer, inner)), invoker);
            chain.run(1L, 1L, new Object[]{"x"});

            // Then
            assertThat(order).containsExactly(
                    "outer-pre", "inner-pre", "inner-post", "outer-post");
        }

        @Test
        void should_thread_args_through_the_layer_chain() throws Throwable {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));

            LayerAction<Object[], Object> passThrough =
                    (stackId, callId, args, next) -> next.execute(stackId, callId, args);

            // When
            FoldedSyncChain chain = SyncChainFolder.fold(
                    asStorage(List.of(passThrough, passThrough)), invoker);
            chain.run(1L, 1L, new Object[]{"threaded"});

            // Then — the value reaches the target unchanged through both layers
            assertThat(target.lastArg()).isEqualTo("threaded");
        }

        @Test
        void should_return_the_target_s_value_through_the_full_chain() throws Throwable {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));

            LayerAction<Object[], Object> passThrough =
                    (stackId, callId, args, next) -> next.execute(stackId, callId, args);

            // When
            FoldedSyncChain chain = SyncChainFolder.fold(
                    asStorage(List.of(passThrough, passThrough, passThrough)), invoker);
            Object result = chain.run(1L, 1L, new Object[]{"value"});

            // Then
            assertThat(result).isEqualTo("result for value");
        }
    }

    @Nested
    class RetryReentry {

        @Test
        void should_re_enter_the_target_correctly_when_layer_calls_next_multiple_times() throws Throwable {
            // What is to be tested?
            //   That a layer which invokes next.execute(...) more than once
            //   actually causes the target to be invoked that many times.
            // How will the test case be deemed successful and why?
            //   The target's call counter equals the number of next.execute
            //   invocations (3). A stateful walker that advances an index
            //   per call would call the target only once.
            // Why is it important to test this test case?
            //   This is the primary retry-semantics correctness invariant.
            //   ADR-035's resilience guarantees depend on retry layers being
            //   able to re-enter the inner chain freely; this is the test
            //   that pins the closures-per-depth choice.

            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));

            LayerAction<Object[], Object> retryThrice =
                    (stackId, callId, args, next) -> {
                        next.execute(stackId, callId, args);   // attempt 1
                        next.execute(stackId, callId, args);   // attempt 2
                        return next.execute(stackId, callId, args);   // attempt 3
                    };

            // When
            FoldedSyncChain chain = SyncChainFolder.fold(
                    asStorage(List.of(retryThrice)), invoker);
            chain.run(1L, 1L, new Object[]{"retry"});

            // Then
            assertThat(target.callCount()).isEqualTo(3);
        }

        @Test
        void should_traverse_the_full_inner_chain_on_each_retry_iteration() throws Throwable {
            // What is to be tested?
            //   That every inner layer is re-entered on each retry pass.
            //   This is the strict version of the previous test: not only the
            //   target, but every layer between the retry layer and the target
            //   must run on every retry iteration.
            // How will the test case be deemed successful and why?
            //   The inner layer's hit-counter is 2 (one per retry pass), and
            //   the target's call counter is 2. A stateful walker would have
            //   advanced past the inner layer on the second call to
            //   next.execute, skipping it entirely; this is the failure mode
            //   the test is designed to catch.
            // Why is it important to test this test case?
            //   This is the correctness-pinning test for the closures-per-depth
            //   fold (ARCHITECTURE.md §7.3 "Why not a stateful walker"). It
            //   must fail if anyone replaces the recursive fold with a walker
            //   that mutates an index.

            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));
            AtomicInteger innerLayerCallCount = new AtomicInteger();

            LayerAction<Object[], Object> retryLayer =
                    (stackId, callId, args, next) -> {
                        next.execute(stackId, callId, args);   // first try
                        return next.execute(stackId, callId, args);   // retry
                    };

            LayerAction<Object[], Object> innerLayer =
                    (stackId, callId, args, next) -> {
                        innerLayerCallCount.incrementAndGet();
                        return next.execute(stackId, callId, args);
                    };

            // When
            FoldedSyncChain chain = SyncChainFolder.fold(
                    asStorage(List.of(retryLayer, innerLayer)), invoker);
            chain.run(1L, 1L, new Object[]{"hello"});

            // Then — the inner layer must be re-entered for the retry iteration.
            assertThat(innerLayerCallCount).hasValue(2);
            assertThat(target.callCount()).isEqualTo(2);
        }
    }

    @Nested
    class StackAndCallIds {

        @Test
        void should_propagate_the_stack_id_to_every_layer() throws Throwable {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));
            AtomicLong outerSeenStackId = new AtomicLong(-1);
            AtomicLong innerSeenStackId = new AtomicLong(-1);

            LayerAction<Object[], Object> outer =
                    (stackId, callId, args, next) -> {
                        outerSeenStackId.set(stackId);
                        return next.execute(stackId, callId, args);
                    };

            LayerAction<Object[], Object> inner =
                    (stackId, callId, args, next) -> {
                        innerSeenStackId.set(stackId);
                        return next.execute(stackId, callId, args);
                    };

            // When
            FoldedSyncChain chain = SyncChainFolder.fold(
                    asStorage(List.of(outer, inner)), invoker);
            chain.run(7777L, 1L, new Object[]{"x"});

            // Then
            assertThat(outerSeenStackId).hasValue(7777L);
            assertThat(innerSeenStackId).hasValue(7777L);
        }

        @Test
        void should_propagate_the_call_id_to_every_layer() throws Throwable {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));
            AtomicLong outerSeenCallId = new AtomicLong(-1);
            AtomicLong innerSeenCallId = new AtomicLong(-1);

            LayerAction<Object[], Object> outer =
                    (stackId, callId, args, next) -> {
                        outerSeenCallId.set(callId);
                        return next.execute(stackId, callId, args);
                    };

            LayerAction<Object[], Object> inner =
                    (stackId, callId, args, next) -> {
                        innerSeenCallId.set(callId);
                        return next.execute(stackId, callId, args);
                    };

            // When
            FoldedSyncChain chain = SyncChainFolder.fold(
                    asStorage(List.of(outer, inner)), invoker);
            chain.run(1L, 4242L, new Object[]{"x"});

            // Then
            assertThat(outerSeenCallId).hasValue(4242L);
            assertThat(innerSeenCallId).hasValue(4242L);
        }
    }

    @Nested
    class ExceptionPropagation {

        @Test
        void should_propagate_an_exception_from_the_target() throws NoSuchMethodException {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("boom", String.class));
            FoldedSyncChain chain = SyncChainFolder.fold(List.of(), invoker);

            // When / Then
            assertThatThrownBy(() -> chain.run(1L, 1L, new Object[]{"x"}))
                    .isExactlyInstanceOf(IOException.class)
                    .hasMessage("target boom for x");
        }

        @Test
        void should_propagate_an_exception_from_a_layer_pre_call() throws NoSuchMethodException {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));

            LayerAction<Object[], Object> throwsPre =
                    (stackId, callId, args, next) -> {
                        throw new IllegalStateException("pre boom");
                    };

            FoldedSyncChain chain = SyncChainFolder.fold(
                    asStorage(List.of(throwsPre)), invoker);

            // When / Then
            assertThatThrownBy(() -> chain.run(1L, 1L, new Object[]{"x"}))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("pre boom");

            // And the target must not have run, since the layer threw before delegating.
            assertThat(target.callCount()).isZero();
        }

        @Test
        void should_propagate_an_exception_from_a_layer_post_call() throws NoSuchMethodException {
            // Given
            CountingTarget target = new CountingTarget();
            MethodInvoker invoker = MethodInvoker.create(target, method("call", String.class));

            LayerAction<Object[], Object> throwsPost =
                    (stackId, callId, args, next) -> {
                        next.execute(stackId, callId, args);
                        throw new IllegalStateException("post boom");
                    };

            FoldedSyncChain chain = SyncChainFolder.fold(
                    asStorage(List.of(throwsPost)), invoker);

            // When / Then
            assertThatThrownBy(() -> chain.run(1L, 1L, new Object[]{"x"}))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("post boom");

            // And the target must have run before the post-call throw.
            assertThat(target.callCount()).isEqualTo(1);
        }
    }
}
