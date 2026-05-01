package eu.inqudium.aspect.pipeline;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression pin for audit finding F-2.18-2.
 *
 * <p>Before {@code InqBulkhead} implemented {@link
 * eu.inqudium.imperative.core.pipeline.InqAsyncDecorator}, the intersection-typed
 * constructor of {@link AsyncElementLayerProvider} ({@code <E extends InqElement &
 * InqAsyncDecorator<Void, Object>>}) rejected {@code InqBulkhead} at compile time.
 * Async aspect setups using bulkhead were pinned to the deprecated
 * {@code Bulkhead} / {@code ImperativeBulkhead} pair.
 *
 * <p>The fact that this test compiles is the structural pin: the constructor
 * accepts a runtime-built {@code InqBulkhead} without any adapter, intersection
 * cast, or wrapper. The runtime assertion exercises the dispatch path through
 * the provider's {@link AsyncLayerAction} — proving the layer-action method
 * reference captured at construction time invokes the bulkhead's async around
 * advice and propagates the downstream stage's result.
 *
 * <p>The test deliberately keeps the threading discipline of an async pin:
 * the downstream stage is a manually-completed {@link CompletableFuture} on
 * the calling thread, so no fork/join executor is involved and the assertion
 * does not depend on scheduling.
 */
@DisplayName("F-2.18-2 — AsyncElementLayerProvider accepts InqBulkhead")
class AsyncElementLayerProviderBulkheadAcceptanceTest {

    @Test
    @DisplayName("F-2.18-2 — async layer provider accepts InqBulkhead")
    void should_dispatch_through_AsyncElementLayerProvider_when_layer_is_an_InqBulkhead() {
        // What is to be tested: that AsyncElementLayerProvider's intersection-typed constructor
        // accepts a runtime-built InqBulkhead and that the layer-action method reference it
        // captures dispatches end-to-end through the bulkhead's async around-advice. Before
        // InqBulkhead implemented InqAsyncDecorator, the constructor produced a
        // "type parameter E is not within bound" compile error, which is what audit finding
        // F-2.18-2 captured.
        // Why successful: the test compiles (structural pin), the provider's identity
        // accessors match the bulkhead, and a single executeAsync invocation through the
        // provider's layer action reaches the downstream stub exactly once and propagates
        // the manually-completed CompletableFuture's value.
        // Why important: a future refactor that drops "implements InqAsyncDecorator" from
        // InqBulkhead would silently re-introduce the audit finding, since the constructor
        // call would no longer compile. This test fails to compile in that case, which is
        // exactly when the original bug surfaced.

        // Given — a real InqBulkhead built through the standard runtime DSL
        try (InqRuntime runtime = Inqudium.configure()
                .imperative(im -> im.bulkhead("payments", b -> b.balanced()))
                .build()) {
            @SuppressWarnings("unchecked")
            InqBulkhead<Void, Object> bulkhead =
                    (InqBulkhead<Void, Object>) runtime.imperative().bulkhead("payments");

            // When — wrap in the async provider; this is the structural pin:
            // the constructor call must compile and accept the bulkhead instance.
            AsyncElementLayerProvider provider = new AsyncElementLayerProvider(bulkhead, 100);

            // Then — provider identity reflects the wrapped bulkhead
            assertThat(provider.element()).isSameAs(bulkhead);
            assertThat(provider.layerName()).isEqualTo("BULKHEAD(payments)");
            assertThat(provider.order()).isEqualTo(100);

            // When — exercise the layer-action method reference end-to-end. The downstream
            // stub is a manually-completed CompletableFuture on the calling thread (no
            // commonPool, no Thread.sleep), so the assertion does not depend on scheduling.
            AsyncLayerAction<Void, Object> action = provider.asyncLayerAction();
            AtomicInteger downstreamInvocations = new AtomicInteger();
            InternalAsyncExecutor<Void, Object> downstream = (chainId, callId, argument) -> {
                downstreamInvocations.incrementAndGet();
                return CompletableFuture.completedFuture("ok");
            };

            CompletionStage<Object> stage = action.executeAsync(1L, 1L, null, downstream);
            Object result = stage.toCompletableFuture().join();

            // Then — bulkhead's async around-advice acquired sync, ran the downstream once,
            // released on stage completion, and propagated the value.
            assertThat(result).isEqualTo("ok");
            assertThat(downstreamInvocations).hasValue(1);
            assertThat(bulkhead.concurrentCalls())
                    .as("permit released on stage completion")
                    .isZero();
        }
    }
}
