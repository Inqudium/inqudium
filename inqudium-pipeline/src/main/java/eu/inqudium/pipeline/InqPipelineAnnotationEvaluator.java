package eu.inqudium.pipeline;

import eu.inqudium.annotation.evaluator.AnnotationEvaluator;
import eu.inqudium.annotation.evaluator.EvaluationResult;

/**
 * Transitional bridge between the new {@link InqPipeline} interface
 * (this module) and the existing annotation evaluator (ADR-036, in
 * the {@code inqudium-annotation} module). The existing evaluator
 * accepts the legacy {@code eu.inqudium.core.pipeline.InqPipeline}
 * class as its input; this bridge builds one from the new pipeline's
 * elements and forwards the call.
 *
 * <p>This class is the <strong>only</strong> point of contact
 * between {@code inqudium-pipeline} and the legacy
 * {@code eu.inqudium.core.pipeline} package. All other code in the
 * new pipeline and proxy modules deals exclusively with the new
 * {@link InqPipeline} interface.</p>
 *
 * <p><strong>Transitional class.</strong> Removed when the legacy
 * {@code InqPipeline} class is dropped and the annotation evaluator
 * migrates to the new pipeline interface. Do not extend its API
 * surface; do not add new callers outside the proxy construction
 * path.</p>
 *
 * @see eu.inqudium.annotation.evaluator.AnnotationEvaluator
 * @see eu.inqudium.core.pipeline.InqPipeline
 */
public final class InqPipelineAnnotationEvaluator {

    private InqPipelineAnnotationEvaluator() {
        // utility class
    }

    /**
     * Evaluates the annotation-derived per-method plan for the given
     * service interface against the given pipeline, by delegating to
     * the existing evaluator (ADR-036) with a legacy pipeline built
     * from the new pipeline's elements.
     *
     * @param pipeline           the new pipeline whose elements drive
     *                           the evaluation
     * @param serviceInterface   the service interface under
     *                           consideration
     * @param implementationClass the concrete implementation class
     *                           (used for inheritance resolution per
     *                           ADR-036)
     * @param <T>                the service interface type
     * @return                   the evaluation result, identical to
     *                           what the legacy evaluator would
     *                           produce for an equivalent legacy
     *                           pipeline
     */
    public static <T> EvaluationResult evaluate(
            InqPipeline pipeline,
            Class<T> serviceInterface,
            Class<? extends T> implementationClass) {

        eu.inqudium.core.pipeline.InqPipeline legacy =
                eu.inqudium.core.pipeline.InqPipeline.builder()
                        .shieldAll(pipeline.elements())
                        .build();

        return AnnotationEvaluator
                .forPipeline(legacy)
                .evaluate(serviceInterface, implementationClass);
    }
}
