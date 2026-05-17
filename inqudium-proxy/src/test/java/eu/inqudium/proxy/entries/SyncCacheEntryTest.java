package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.folding.FoldedSyncChain;
import eu.inqudium.proxy.handler.InqInvocationHandler;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncCacheEntryTest {

    private static LongSupplier startingFrom(long startInclusive) {
        AtomicLong counter = new AtomicLong(startInclusive - 1);
        return counter::incrementAndGet;
    }

    /**
     * Test-only recording chain that captures every invocation and
     * returns a configurable value. Keeps the test free of mocks.
     */
    private static final class RecordingChain implements FoldedSyncChain {

        record Invocation(long stackId, long callId, Object[] args) {
        }

        private final List<Invocation> invocations = new ArrayList<>();
        private final Object returnValue;

        RecordingChain(Object returnValue) {
            this.returnValue = returnValue;
        }

        @Override
        public Object run(long stackId, long callId, Object[] args) {
            invocations.add(new Invocation(stackId, callId, args));
            return returnValue;
        }

        List<Invocation> invocations() {
            return invocations;
        }
    }

    @Nested
    class HappyPath {

        @Test
        void should_pull_stack_id_and_call_id_from_the_handler_on_each_dispatch() throws Throwable {
            // Given
            RecordingChain chain = new RecordingChain("ok");
            InqInvocationHandler handler = new InqInvocationHandler(99L, startingFrom(10L), java.util.Map.of());
            SyncCacheEntry entry = new SyncCacheEntry(chain, List.of());

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
        void should_pass_args_through_to_the_folded_chain() throws Throwable {
            // Given
            RecordingChain chain = new RecordingChain("ok");
            InqInvocationHandler handler = new InqInvocationHandler(1L, startingFrom(1L), java.util.Map.of());
            SyncCacheEntry entry = new SyncCacheEntry(chain, List.of());

            Object[] args = new Object[]{"a", 42, null};

            // When
            entry.dispatch(new Object(), handler, args);

            // Then — the entry hands the exact array through to the chain
            assertThat(chain.invocations()).hasSize(1);
            assertThat(chain.invocations().get(0).args()).isSameAs(args);
        }

        @Test
        void should_return_the_chain_s_result_unchanged() throws Throwable {
            // Given
            RecordingChain chain = new RecordingChain("verbatim result");
            InqInvocationHandler handler = new InqInvocationHandler(1L, startingFrom(1L), java.util.Map.of());
            SyncCacheEntry entry = new SyncCacheEntry(chain, List.of());

            // When
            Object result = entry.dispatch(new Object(), handler, new Object[0]);

            // Then
            assertThat(result).isEqualTo("verbatim result");
        }

        @Test
        void should_expose_layer_descriptions_immutably() {
            // Given
            RecordingChain chain = new RecordingChain("ok");
            SyncCacheEntry entry = new SyncCacheEntry(
                    chain, List.of("CIRCUIT_BREAKER(orderCb)", "RETRY(orderRetry)"));

            // When
            List<String> descriptions = entry.layerDescriptions();

            // Then — the snapshot is content-equal to the input and unmodifiable
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
                    .isThrownBy(() -> new SyncCacheEntry(null, List.of()))
                    .withMessage("chain");
        }

        @Test
        void should_reject_null_layer_descriptions_with_npe() {
            // Given
            RecordingChain chain = new RecordingChain("ok");

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new SyncCacheEntry(chain, null))
                    .withMessage("layerDescriptions");
        }

        @Test
        void should_defensively_copy_layer_descriptions() {
            // What is to be tested?
            //   That mutating the list passed to the constructor after
            //   construction does not affect the entry's view of the
            //   layer descriptions.
            // How will the test case be deemed successful and why?
            //   The entry's accessor still returns the original two-element
            //   snapshot after the caller modifies the source list. A
            //   shallow copy of the reference would expose the mutation;
            //   the defensive List.copyOf prevents it.
            // Why is it important to test this test case?
            //   Layer descriptions are exposed through ADR-039 introspection
            //   and may be retained by external diagnostic code. Sharing a
            //   mutable reference would make the proxy's reported topology
            //   change behind the consumer's back.

            // Given
            RecordingChain chain = new RecordingChain("ok");
            List<String> source = new ArrayList<>(Arrays.asList("A", "B"));
            SyncCacheEntry entry = new SyncCacheEntry(chain, source);

            // When — mutate the source after the entry was built
            source.add("C");
            source.set(0, "MUTATED");

            // Then — the entry's view is unaffected
            assertThat(entry.layerDescriptions()).containsExactly("A", "B");
        }
    }
}
