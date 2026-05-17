package eu.inqudium.proxy.construction;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.imperative.core.pipeline.AsyncLayerTerminal;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async-element fixture: implements both {@link InqAsyncDecorator}
 * and (transitively) {@link eu.inqudium.core.element.InqElement}.
 * The decorator's {@code executeAsync} method is a pure
 * pass-through that also bumps a call counter — useful for
 * asserting that a fold actually threaded through the layer.
 *
 * <p>Package-private. Used by the construction-package tests that
 * exercise the async path: {@link AsyncParadigmValidatorTest} and
 * the async assertions in {@link MethodDispatchEntryFactoryTest}
 * and {@link ProxyBuilderTest}.</p>
 */
final class FakeAsyncDecorator implements InqAsyncDecorator<Object, Object> {

    private final String name;
    private final InqElementType type;
    private final AtomicInteger callCount = new AtomicInteger();

    FakeAsyncDecorator(String name, InqElementType type) {
        this.name = name;
        this.type = type;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public InqElementType elementType() {
        return type;
    }

    @Override
    public InqEventPublisher eventPublisher() {
        return null;
    }

    @Override
    public CompletionStage<Object> executeAsync(long chainId, long callId, Object argument,
                                                AsyncLayerTerminal<Object, Object> next) {
        callCount.incrementAndGet();
        return next.executeAsync(chainId, callId, argument);
    }

    int callCount() {
        return callCount.get();
    }

    @Override
    public String toString() {
        return "FakeAsyncDecorator(" + name + ")";
    }
}
