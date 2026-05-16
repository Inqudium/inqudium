package eu.inqudium.proxy.folding;

import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.core.pipeline.LayerTerminal;
import eu.inqudium.core.pipeline.Throws;
import eu.inqudium.proxy.invocation.MethodInvoker;

import java.util.List;
import java.util.Objects;

/**
 * Folds a list of resilience layer actions plus a terminal method
 * invoker into a single {@link FoldedSyncChain} closure. The fold
 * is performed once at proxy-construction time and the resulting
 * closure is reused for every invocation of the method.
 *
 * <h2>Storage typing vs. call-time typing</h2>
 * <p>Per ADR-035 §4, the per-method cache stores layer actions as
 * {@code LayerAction<Void, Object>}. This is the storage-side
 * contract: a uniform type that accepts any element regardless of
 * its declared {@code <A, R>}.</p>
 *
 * <p>The dispatcher's hot-path code, however, threads
 * {@code Object[] args} through the chain via the {@code A}
 * parameter of {@link LayerAction#execute}. So at fold time we
 * re-parameterise the storage list as
 * {@code List<LayerAction<Object[], Object>>}.</p>
 *
 * <p>Because Java generics are erased at runtime, the two
 * parameterisations are the same runtime type. The unchecked cast
 * is safe and happens exactly once per chain at fold time
 * (never per call). The cast is contained in {@link #fold} and is
 * the only unchecked-suppression site in {@code inqudium-proxy}'s
 * main sources; no other file performs an unchecked cast between
 * {@code LayerAction} parameterisations. See ARCHITECTURE.md §7.3.</p>
 *
 * <h2>Closures per depth, not a stateful walker</h2>
 * <p>The fold uses recursive composition: each call to
 * {@link #foldRecursive} returns a new closure that captures the
 * appropriate {@code tail}. This is essential for retry semantics
 * — a layer that calls {@code next.execute(...)} multiple times
 * must re-enter the entire inner chain on each call. A stateful
 * walker that advances an index per call would skip layers on
 * retry. See ARCHITECTURE.md §7.3 "Why not a stateful walker".</p>
 *
 * <h2>Per-call allocations</h2>
 * <p>N intermediate {@code LayerTerminal} closures per call, one
 * per chain transition. Each closure captures only the {@code tail}
 * reference; the {@code args} flow through the function parameter.
 * The JIT's escape analysis can often eliminate these allocations.</p>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from {@code construction/} and {@code entries/}; not
 * part of the stable public surface. Mirrors the visibility of
 * {@link FoldedSyncChain} and {@link MethodInvoker}.</p>
 */
public final class SyncChainFolder {

    private SyncChainFolder() {
        // utility class
    }

    /**
     * Folds the layer actions plus the terminal invoker into one
     * {@link FoldedSyncChain}.
     *
     * @param storageLayers layers in outer-to-inner order, typed
     *                      with the storage contract
     *                      {@code LayerAction<Void, Object>}
     * @param invoker       the terminal invoker that calls the real
     *                      target
     */
    public static FoldedSyncChain fold(
            List<LayerAction<Void, Object>> storageLayers,
            MethodInvoker invoker) {

        Objects.requireNonNull(storageLayers, "storageLayers");
        Objects.requireNonNull(invoker, "invoker");

        @SuppressWarnings("unchecked")
        List<LayerAction<Object[], Object>> layers =
                (List<LayerAction<Object[], Object>>) (List<?>) storageLayers;

        return foldRecursive(layers, 0, invoker);
    }

    private static FoldedSyncChain foldRecursive(
            List<LayerAction<Object[], Object>> layers,
            int idx,
            MethodInvoker invoker) {

        if (idx == layers.size()) {
            return (stackId, callId, args) -> invoker.invoke(args);
        }

        LayerAction<Object[], Object> head = layers.get(idx);
        FoldedSyncChain tail = foldRecursive(layers, idx + 1, invoker);

        return (stackId, callId, args) -> {
            LayerTerminal<Object[], Object> nextForHead =
                    (s, c, a) -> {
                        try {
                            return tail.run(s, c, a);
                        } catch (Throwable t) {
                            throw Throws.rethrow(t);
                        }
                    };
            return head.execute(stackId, callId, args, nextForHead);
        };
    }
}
