package eu.inqudium.proxy.discipline;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.LayerTerminal;

/**
 * Sync-only test element for {@link ModuleLoadingDisciplineTest}.
 *
 * <p>Implements <strong>only</strong> {@link InqDecorator}; does
 * not implement
 * {@code eu.inqudium.imperative.core.pipeline.InqAsyncDecorator}.
 * This is essential: the discipline test must not pull async types
 * into the {@code URLClassLoader} via the bulkhead fixture&rsquo;s
 * class hierarchy. A dual-paradigm bulkhead (sync + async) would
 * defeat the test&rsquo;s purpose.</p>
 */
public final class SyncOnlyFakeBulkhead implements InqDecorator<Object, Object> {

    private final String name;

    public SyncOnlyFakeBulkhead(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public InqElementType elementType() {
        return InqElementType.BULKHEAD;
    }

    @Override
    public InqEventPublisher eventPublisher() {
        return null;
    }

    @Override
    public Object execute(long chainId, long callId, Object argument,
                          LayerTerminal<Object, Object> next) {
        return next.execute(chainId, callId, argument);
    }
}
