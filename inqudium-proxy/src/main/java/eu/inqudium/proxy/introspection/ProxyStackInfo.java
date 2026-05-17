package eu.inqudium.proxy.introspection;

import eu.inqudium.core.element.InqElement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Introspection DTO for a proxy stack, per ADR-039.
 *
 * <p><strong>Public API. Standalone record</strong> (Option-B
 * scope): does not yet implement an {@code InqStackInfo} sealed
 * interface because that interface and the full library-wide
 * introspection plumbing (including the {@code chainId → stackId}
 * rename) are deferred to a separate refactor. The record's shape
 * matches ADR-039 exactly so future migration into the sealed
 * hierarchy needs no contract change.</p>
 *
 * @param stackId      the per-proxy stack ID allocated by
 *                     {@code PipelineIds.nextChainId()} (per ADR-034)
 * @param targetType   the service interface the proxy implements;
 *                     always present for the proxy paradigm but
 *                     declared as {@link Optional} per ADR-039
 *                     so the type matches the future sealed-interface contract
 * @param elements     the pipeline's elements at construction time
 *                     (immutable snapshot)
 * @param methodLayers one {@link MethodLayers} per service method
 *                     (Object methods, default methods, and pass-through
 *                     methods produce empty layer lists)
 */
public record ProxyStackInfo(
        long stackId,
        Optional<Class<?>> targetType,
        List<InqElement> elements,
        List<MethodLayers> methodLayers) {

    public ProxyStackInfo {
        Objects.requireNonNull(targetType, "targetType");
        elements = List.copyOf(elements);
        methodLayers = List.copyOf(methodLayers);
    }
}
