/**
 * Proxy-side introspection adapter per ADR-039 (Option-B scope).
 *
 * <p>This package exposes four public types:</p>
 * <ul>
 *   <li>{@link eu.inqudium.proxy.introspection.ProxyStackAdapter} —
 *       static {@code supports} / {@code inspect} methods for
 *       proxies produced by {@code ProxyDispatcher.protect(...)}.</li>
 *   <li>{@link eu.inqudium.proxy.introspection.ProxyStackInfo} —
 *       standalone DTO record carrying stack id, service interface,
 *       elements snapshot, and per-method layer summary.</li>
 *   <li>{@link eu.inqudium.proxy.introspection.MethodLayers} —
 *       per-method layer description record (signature, descriptions,
 *       canonical {@code Method} for disambiguation).</li>
 *   <li>{@link eu.inqudium.proxy.introspection.MethodSignatureFormatter}
 *       — utility producing ADR-039's canonical
 *       {@code Class.method(P1, P2)} format.</li>
 * </ul>
 *
 * <p>Per the Option-B scope decision, the central
 * {@code InqIntrospector} dispatcher, the {@code InqStackInfo} sealed
 * hierarchy, and the library-wide {@code chainId → stackId} rename are
 * deferred to a separate refactor. The DTO records' shapes already
 * match ADR-039 exactly, so a future migration into the sealed
 * hierarchy will not require a contract change.</p>
 *
 * @see inqudium-proxy/docs/ARCHITECTURE.md
 */
package eu.inqudium.proxy.introspection;
