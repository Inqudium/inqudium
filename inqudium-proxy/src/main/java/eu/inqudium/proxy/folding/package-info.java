/**
 * Chain-folding primitives: {@code SyncChainFolder} and
 * {@code AsyncChainFolder} materialise an ordered list of resilience
 * layers into a {@code FoldedSyncChain} / {@code FoldedAsyncChain}
 * closure that threads arguments through the layers around a target
 * call. The single unchecked cast that bridges storage typing
 * (per-element {@code LayerAction<Void, Object>}) to call-time typing
 * (per-method {@code LayerAction<Object[], Object>}) is contained in
 * {@code SyncChainFolder}.
 *
 * @see inqudium-proxy/docs/ARCHITECTURE.md
 */
package eu.inqudium.proxy.folding;
