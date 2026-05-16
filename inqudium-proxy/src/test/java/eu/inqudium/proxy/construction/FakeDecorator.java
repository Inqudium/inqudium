package eu.inqudium.proxy.construction;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.LayerTerminal;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sync-element fixture: implements both {@link InqDecorator} and
 * (transitively) {@link eu.inqudium.core.element.InqElement}. The
 * decorator's {@code execute} method is a pure pass-through that
 * also bumps a call counter — useful for asserting that a fold
 * actually threaded through the layer.
 */
final class FakeDecorator implements InqDecorator<Object, Object> {

    private final String name;
    private final InqElementType type;
    private final AtomicInteger callCount = new AtomicInteger();

    FakeDecorator(String name, InqElementType type) {
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
    public Object execute(long chainId, long callId, Object argument,
                          LayerTerminal<Object, Object> next) {
        callCount.incrementAndGet();
        return next.execute(chainId, callId, argument);
    }

    int callCount() {
        return callCount.get();
    }

    @Override
    public String toString() {
        return "FakeDecorator(" + name + ")";
    }
}
