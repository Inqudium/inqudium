package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.folding.FoldedAsyncChain;
import eu.inqudium.proxy.handler.InqInvocationHandler;

import java.util.List;
import java.util.Objects;

/**
 * Dispatches an asynchronous service method through its pre-folded
 * resilience chain. Used when the annotation evaluator's plan is
 * {@code MethodPlan.Decorated} and the method's return type is a
 * {@link java.util.concurrent.CompletionStage} (or subtype).
 *
 * <p>Per-call work: pull {@code callId} from the handler, call
 * {@link FoldedAsyncChain#run}. All folding work happened once at
 * proxy-construction time.</p>
 *
 * <p>The returned object is a {@link java.util.concurrent.CompletionStage};
 * RuntimeExceptions raised synchronously by layers (e.g. a
 * permit-acquire failure before the async op starts) propagate as
 * plain exceptions, reach {@code InqInvocationHandler.invoke}'s
 * catch-block, and are classified by
 * {@link eu.inqudium.proxy.exception.ExceptionClassifier} per
 * ADR-035 §10. Failures inside the returned stage stay there and
 * follow the JDK conventions.</p>
 *
 * <p>Package-private — proxy code constructs these via the
 * {@link MethodDispatchEntry#asyncCache(FoldedAsyncChain, List)}
 * static factory.</p>
 *
 * <p><strong>Class-loading discipline</strong> (ADR-037 §6 /
 * ARCHITECTURE.md §13): this record references
 * {@link FoldedAsyncChain} from {@code eu.inqudium.proxy.folding},
 * which in turn references async types from
 * {@code inqudium-imperative}. It is reachable only via the async
 * branch of {@code MethodDispatchEntryFactory}.</p>
 */
record AsyncCacheEntry(FoldedAsyncChain chain, List<String> layerDescriptions)
        implements MethodDispatchEntry {

    AsyncCacheEntry {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(layerDescriptions, "layerDescriptions");
        layerDescriptions = List.copyOf(layerDescriptions);
    }

    @Override
    public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args) {
        return chain.run(handler.stackId(), handler.nextCallId(), args);
    }
}
