package eu.inqudium.annotation.evaluator;

import java.util.List;

/**
 * Per-method protection plan produced by {@link AnnotationEvaluator}.
 *
 * <p>One of two variants applies to each method on a service interface:
 * {@link PassThrough} when no resilience annotations are in effect, or
 * {@link Decorated} when the method should be wrapped in the listed
 * pipeline elements, outermost first.</p>
 *
 * @since 0.8.0
 */
public sealed interface MethodPlan {

    /**
     * The method receives no resilience protection — neither a method-level
     * nor a class-level resilience annotation applies, or the method is an
     * unoverridden interface default method (ADR-036 §7).
     */
    record PassThrough() implements MethodPlan {
    }

    /**
     * The method is wrapped by the named pipeline elements, in the given
     * order. The first entry is the outermost wrapping element; the last
     * entry is closest to the method itself.
     *
     * <p>The list is non-empty whenever the record is produced by
     * {@link AnnotationEvaluator}. An empty list is possible only when the
     * record is constructed directly by application code; the
     * {@code AnnotationEvaluator} itself produces an empty {@code Decorated}
     * for no annotated source — it produces {@link PassThrough} instead.</p>
     *
     * @param elementNamesOuterToInner the ordered element names, outermost
     *                                 first; defensively copied into an
     *                                 immutable list
     */
    record Decorated(List<String> elementNamesOuterToInner) implements MethodPlan {

        /**
         * Defensively copies the input into an immutable list so callers
         * cannot mutate the plan after construction.
         */
        public Decorated {
            elementNamesOuterToInner = List.copyOf(elementNamesOuterToInner);
        }
    }
}
