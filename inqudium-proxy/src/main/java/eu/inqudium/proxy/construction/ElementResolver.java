package eu.inqudium.proxy.construction;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.pipeline.InqPipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves layer-element names (from
 * {@code MethodPlan.Decorated.elementNamesOuterToInner()}) to the
 * matching {@link InqElement} instances in the pipeline.
 *
 * <p>The annotation evaluator (ADR-036) has already validated that
 * every name referenced from the service interface exists in the
 * pipeline. This resolver therefore assumes lookup never misses;
 * a miss indicates evaluator/pipeline drift and raises
 * {@link IllegalStateException}.</p>
 *
 * <p>Per ARCHITECTURE.md §7.1: pipelines are small (typically &le; 6
 * elements), so constructing a name &rarr; element map per resolution
 * call is acceptable. The resolver runs on the cold construction
 * path, not per method invocation.</p>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from sibling subpackages within {@code inqudium-proxy};
 * not part of the stable public surface.</p>
 */
public final class ElementResolver {

    private ElementResolver() {
        // utility class
    }

    /**
     * Maps each name in {@code names} to the {@link InqElement}
     * with that {@code name()} in the pipeline. Order in the
     * returned list matches the order in {@code names}.
     *
     * @throws IllegalStateException if a name is not present in the
     *                               pipeline (defensive guard against
     *                               evaluator/pipeline drift)
     */
    public static List<InqElement> resolveNames(
            List<String> names, InqPipeline pipeline) {

        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(pipeline, "pipeline");

        Map<String, InqElement> byName = pipeline.elements().stream()
                .collect(Collectors.toMap(InqElement::name, Function.identity()));

        return names.stream()
                .map(name -> {
                    InqElement element = byName.get(name);
                    if (element == null) {
                        throw new IllegalStateException(
                                "Element '" + name + "' was referenced by an "
                                        + "annotation but is not present in the "
                                        + "pipeline. This should have been caught "
                                        + "by the annotation evaluator (ADR-036) "
                                        + "before construction.");
                    }
                    return element;
                })
                .toList();
    }
}
