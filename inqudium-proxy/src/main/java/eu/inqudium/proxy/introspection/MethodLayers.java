package eu.inqudium.proxy.introspection;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-method layer description, per ADR-039.
 *
 * <p>For the proxy paradigm, {@link #method()} is always
 * {@link Optional#of(Object) Optional.of(method)} — ADR-039's
 * tier-1 method resolution applies (the proxy's invocation handler
 * has a concrete {@link Method} for every dispatched call).</p>
 *
 * <p><strong>Public API.</strong> Standalone record per the Option-B
 * scope decision. When ADR-039's full implementation lands (separate
 * refactor), this record may migrate to {@code inqudium-pipeline} or
 * {@code inqudium-core} and become a component of the
 * {@code InqStackInfo} hierarchy — the record's component shape is
 * already ADR-039-conformant, so no breaking change is required.</p>
 *
 * @param methodSignature   the ADR-039-canonical signature per
 *                          {@link MethodSignatureFormatter}
 * @param layerDescriptions outer-to-inner names of the resilience
 *                          layers wrapping this method; empty for
 *                          pass-through, default-method, and
 *                          Object-method routes
 * @param method            the original {@link Method} for canonical
 *                          identity disambiguation
 */
public record MethodLayers(
        String methodSignature,
        List<String> layerDescriptions,
        Optional<Method> method) {

    public MethodLayers {
        Objects.requireNonNull(methodSignature, "methodSignature");
        Objects.requireNonNull(method, "method");
        layerDescriptions = List.copyOf(layerDescriptions);
    }
}
