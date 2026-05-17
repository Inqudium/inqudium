package eu.inqudium.proxy.folding;

import java.util.concurrent.CompletionStage;

/**
 * The functional-interface counterpart to {@link FoldedSyncChain}
 * for async dispatch. Returns a {@link CompletionStage} carrying
 * the final result instead of returning the value directly.
 *
 * <p><strong>No declared throws.</strong> The underlying
 * {@code AsyncLayerAction.executeAsync(...)} in
 * {@code inqudium-imperative} does not declare {@code throws};
 * checked exceptions from the target method (which
 * {@link eu.inqudium.proxy.invocation.MethodInvoker#invoke(Object[])
 * MethodInvoker.invoke(...)} can throw) are caught inside the
 * folder's terminal and wrapped in
 * {@link java.util.concurrent.CompletableFuture#failedFuture(Throwable)}.
 * RuntimeExceptions from layers propagate naturally without needing
 * declarations.</p>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from {@code entries/}; not part of the stable public
 * surface.</p>
 */
@FunctionalInterface
public interface FoldedAsyncChain {

    /**
     * Runs the folded async chain with the given correlation IDs and
     * method arguments. Returns the {@link CompletionStage} produced
     * by the target (with all layers applied).
     */
    CompletionStage<Object> run(long stackId, long callId, Object[] args);
}
