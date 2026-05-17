package eu.inqudium.proxy.folding;

import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.AsyncLayerTerminal;
import eu.inqudium.proxy.invocation.MethodInvoker;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Builds a {@link FoldedAsyncChain} from a list of
 * {@link AsyncLayerAction}s and a terminal {@link MethodInvoker}.
 * Structurally analogous to {@link SyncChainFolder}: the
 * closures-per-depth recursion preserves retry semantics across
 * stages composed via {@code .thenCompose(...)} or
 * {@code .exceptionally(...)}.
 *
 * <h2>Storage typing vs. call-time typing</h2>
 * <p>Per ADR-035 §4 the input layers are stored as
 * {@code AsyncLayerAction<Void, Object>}; this folder casts the
 * list to {@code AsyncLayerAction<Object[], Object>} once so that
 * the proxy's {@code args:Object[]} flows through the {@code A}
 * parameter of {@code executeAsync(...)}. The cast is unchecked but
 * safe — generics erase to the same runtime type.</p>
 *
 * <h2>Sync-throws from the target</h2>
 * <p>The terminal calls {@link MethodInvoker#invoke(Object[])},
 * which is the same synchronous invoker used by the sync folder.
 * For an async service method the runtime return value is a
 * {@link CompletionStage}, but {@code invoke} may also throw
 * synchronously (e.g. if the target's method body computes
 * arguments before returning the stage). Such sync-throws are
 * wrapped in {@link CompletableFuture#failedFuture(Throwable)} so
 * that the async caller always observes a stage and never has to
 * handle two error channels.</p>
 *
 * <p>RuntimeExceptions raised synchronously by layers (e.g. a
 * permit-acquire failure before {@code next.executeAsync} is
 * called) propagate as plain exceptions out of {@code run(...)};
 * they reach {@code AsyncCacheEntry.dispatch}, then
 * {@code InqInvocationHandler.invoke}'s catch-block, then
 * {@link eu.inqudium.proxy.exception.ExceptionClassifier} per
 * ADR-035 §10. Failures already inside the returned stage stay
 * there and follow the JDK conventions.</p>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from {@code construction/}; not part of the stable
 * public surface.</p>
 *
 * <p><strong>Class-loading discipline</strong> (ADR-037 §6 /
 * ARCHITECTURE.md §13): this class references
 * {@link AsyncLayerAction} and {@link AsyncLayerTerminal} from
 * {@code inqudium-imperative}. It is reachable only via the async
 * branch of {@code MethodDispatchEntryFactory}, which is itself
 * guarded by {@link eu.inqudium.proxy.dispatch.DetectionAsync#isPresent()}.
 * </p>
 */
public final class AsyncChainFolder {

    private AsyncChainFolder() {
        // utility class
    }

    /**
     * Folds the layer actions plus the terminal invoker into one
     * {@link FoldedAsyncChain}.
     *
     * @param storageLayers layers in outer-to-inner order, typed
     *                      with the storage contract
     *                      {@code AsyncLayerAction<Void, Object>}
     * @param invoker       the terminal invoker that calls the real
     *                      target
     */
    public static FoldedAsyncChain fold(
            List<AsyncLayerAction<Void, Object>> storageLayers,
            MethodInvoker invoker) {

        Objects.requireNonNull(storageLayers, "storageLayers");
        Objects.requireNonNull(invoker, "invoker");

        @SuppressWarnings("unchecked")
        List<AsyncLayerAction<Object[], Object>> layers =
                (List<AsyncLayerAction<Object[], Object>>) (List<?>) storageLayers;

        return foldRecursive(layers, 0, invoker);
    }

    private static FoldedAsyncChain foldRecursive(
            List<AsyncLayerAction<Object[], Object>> layers,
            int idx,
            MethodInvoker invoker) {

        if (idx == layers.size()) {
            return (stackId, callId, args) -> {
                try {
                    @SuppressWarnings("unchecked")
                    CompletionStage<Object> stage =
                            (CompletionStage<Object>) invoker.invoke(args);
                    return stage;
                } catch (Throwable t) {
                    return CompletableFuture.failedFuture(t);
                }
            };
        }

        AsyncLayerAction<Object[], Object> head = layers.get(idx);
        FoldedAsyncChain tail = foldRecursive(layers, idx + 1, invoker);
        return (stackId, callId, args) -> {
            AsyncLayerTerminal<Object[], Object> nextForHead =
                    (s, c, a) -> tail.run(s, c, a);
            return head.executeAsync(stackId, callId, args, nextForHead);
        };
    }
}
