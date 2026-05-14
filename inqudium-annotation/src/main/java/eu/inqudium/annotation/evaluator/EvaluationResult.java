package eu.inqudium.annotation.evaluator;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Result of evaluating annotations on a service interface against its
 * implementation class. Maps each interface method to its
 * {@link MethodPlan}.
 *
 * <p>The map is immutable; mutating operations raise
 * {@link UnsupportedOperationException}. The compact constructor uses
 * {@link Map#copyOf(Map)} which both rejects {@code null} keys and values
 * and produces an unmodifiable copy detached from the caller's reference.</p>
 *
 * @param plans the per-method protection plans, keyed by interface method
 * @since 0.8.0
 */
public record EvaluationResult(Map<Method, MethodPlan> plans) {

    /**
     * Defensively copies the input into an immutable map so callers cannot
     * mutate the evaluation result after construction.
     */
    public EvaluationResult {
        plans = Map.copyOf(plans);
    }
}
