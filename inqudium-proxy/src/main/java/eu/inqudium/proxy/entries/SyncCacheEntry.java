package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.folding.FoldedSyncChain;
import eu.inqudium.proxy.handler.InqInvocationHandler;

import java.util.List;
import java.util.Objects;

/**
 * Dispatches a synchronous service method through its pre-folded
 * resilience chain. Used when the annotation evaluator's plan is
 * {@code MethodPlan.Decorated} and the method's return type is
 * synchronous (i.e. not {@code CompletionStage}, not {@code Mono}).
 *
 * <p>Per-call work: pull {@code callId} from the handler, call
 * {@link FoldedSyncChain#run}. All folding work happened once at
 * proxy-construction time.</p>
 */
final class SyncCacheEntry implements MethodDispatchEntry {

    private final FoldedSyncChain chain;
    private final List<String> layerDescriptions;

    SyncCacheEntry(FoldedSyncChain chain, List<String> layerDescriptions) {
        this.chain = Objects.requireNonNull(chain, "chain");
        this.layerDescriptions = List.copyOf(
                Objects.requireNonNull(layerDescriptions, "layerDescriptions"));
    }

    @Override
    public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args)
            throws Throwable {
        long stackId = handler.stackId();
        long callId = handler.nextCallId();
        return chain.run(stackId, callId, args);
    }

    /**
     * Returns an immutable snapshot of the layer descriptions
     * (outermost-first), for introspection (ADR-039) and toString.
     */
    List<String> layerDescriptions() {
        return layerDescriptions;
    }
}
