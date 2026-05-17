package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.folding.FoldedAsyncChain;
import eu.inqudium.proxy.handler.InqInvocationHandler;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncCacheEntryTest {

    private static LongSupplier startingFrom(long startInclusive) {
        AtomicLong counter = new AtomicLong(startInclusive - 1);
        return counter::incrementAndGet;
    }

    /**
     * Test-only recording chain that captures every invocation and
     * returns a configurable stage. Keeps the test free of mocks.
     */
    private static final class RecordingChain implements FoldedAsyncChain {

        record Invocation(long stackId, long callId, Object[] args) {
        }

        private final List<Invocation> invocations = new ArrayList<>();
        private final CompletionStage<Object> returnStage;

        RecordingChain(CompletionStage<Object> returnStage) {
            this.returnStage = returnStage;
        }

        @Override
        public CompletionStage<Object> run(long stackId, long callId, Object[] args) {
            invocations.add(new Invocation(stackId, callId, args));
            return returnStage;
        }

        List<Invocation> invocations() {
            return invocations;
        }
    }

    @Nested
    class HappyPath {

        @Test
        void should_pull_stack_id_and_call_id_from_the_handler_on_each_dispatch() {
            // Given
            RecordingChain chain = new RecordingChain(CompletableFuture.completedFuture("ok"));
            InqInvocationHandler handler = new InqInvocationHandler(
                    99L, startingFrom(10L), new Object(), java.util.Map.of());
            AsyncCacheEntry entry = new AsyncCacheEntry(chain, List.of());

            // When
            entry.dispatch(new Object(), handler, new Object[0]);
            entry.dispatch(new Object(), handler, new Object[0]);

            // Then — same stackId on both invocations, fresh callIds
            assertThat(chain.invocations()).hasSize(2);
            assertThat(chain.invocations().get(0).stackId()).isEqualTo(99L);
            assertThat(chain.invocations().get(0).callId()).isEqualTo(10L);
            assertThat(chain.invocations().get(1).stackId()).isEqualTo(99L);
            assertThat(chain.invocations().get(1).callId()).isEqualTo(11L);
        }

        @Test
        void should_pass_args_through_to_the_folded_chain() {
            // Given
            RecordingChain chain = new RecordingChain(CompletableFuture.completedFuture("ok"));
            InqInvocationHandler handler = new InqInvocationHandler(
                    1L, startingFrom(1L), new Object(), java.util.Map.of());
            AsyncCacheEntry entry = new AsyncCacheEntry(chain, List.of());

            Object[] args = new Object[]{"a", 42, null};

            // When
            entry.dispatch(new Object(), handler, args);

            // Then — the entry hands the exact array through to the chain
            assertThat(chain.invocations()).hasSize(1);
            assertThat(chain.invocations().get(0).args()).isSameAs(args);
        }

        @Test
        void should_return_the_chain_s_completion_stage_unchanged() throws Exception {
            // What is to be tested?
            //   The entry returns the CompletionStage produced by the
            //   chain verbatim. The JDK proxy hands it through to the
            //   service caller as the method's return value. Any
            //   wrapping would change the observed type and break
            //   chained .thenApply / .exceptionally calls on the
            //   caller's side.
            // How will the test case be deemed successful and why?
            //   The dispatched result is the same stage the chain
            //   produced; calling get() on it returns the chain's
            //   completion value.
            // Why is it important to test this test case?
            //   Pins the verbatim-pass-through contract — the entry
            //   has no per-call work to do beyond pulling correlation
            //   IDs.

            // Given
            CompletableFuture<Object> stage = CompletableFuture.completedFuture("verbatim");
            RecordingChain chain = new RecordingChain(stage);
            InqInvocationHandler handler = new InqInvocationHandler(
                    1L, startingFrom(1L), new Object(), java.util.Map.of());
            AsyncCacheEntry entry = new AsyncCacheEntry(chain, List.of());

            // When
            Object result = entry.dispatch(new Object(), handler, new Object[0]);

            // Then
            assertThat(result).isSameAs(stage);
            assertThat(((CompletionStage<?>) result).toCompletableFuture().get())
                    .isEqualTo("verbatim");
        }

        @Test
        void should_expose_layer_descriptions_immutably() {
            // Given
            RecordingChain chain = new RecordingChain(CompletableFuture.completedFuture("ok"));
            AsyncCacheEntry entry = new AsyncCacheEntry(
                    chain, List.of("CIRCUIT_BREAKER(orderCb)", "RETRY(orderRetry)"));

            // When
            List<String> descriptions = entry.layerDescriptions();

            // Then
            assertThat(descriptions)
                    .containsExactly("CIRCUIT_BREAKER(orderCb)", "RETRY(orderRetry)");
            assertThatThrownBy(() -> descriptions.add("RATE_LIMITER(orderRl)"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Construction {

        @Test
        void should_reject_null_chain_with_npe() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new AsyncCacheEntry(null, List.of()))
                    .withMessage("chain");
        }

        @Test
        void should_reject_null_layer_descriptions_with_npe() {
            // Given
            RecordingChain chain = new RecordingChain(CompletableFuture.completedFuture("ok"));

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new AsyncCacheEntry(chain, null))
                    .withMessage("layerDescriptions");
        }

        @Test
        void should_defensively_copy_layer_descriptions() {
            // What is to be tested?
            //   Mutating the list passed to the constructor after
            //   construction does not affect the entry's view of the
            //   layer descriptions. Mirrors SyncCacheEntry's defensive
            //   copy.
            // How will the test case be deemed successful and why?
            //   The entry's accessor still returns the original
            //   two-element snapshot after the caller modifies the
            //   source list.
            // Why is it important to test this test case?
            //   Layer descriptions are exposed through ADR-039
            //   introspection; sharing a mutable reference would make
            //   the proxy's reported topology change behind the
            //   consumer's back.

            // Given
            RecordingChain chain = new RecordingChain(CompletableFuture.completedFuture("ok"));
            List<String> source = new ArrayList<>(Arrays.asList("A", "B"));
            AsyncCacheEntry entry = new AsyncCacheEntry(chain, source);

            // When — mutate the source after the entry was built
            source.add("C");
            source.set(0, "MUTATED");

            // Then
            assertThat(entry.layerDescriptions()).containsExactly("A", "B");
        }
    }
}
