package eu.inqudium.pipeline;

import eu.inqudium.core.element.InqElement;

import java.util.List;

/**
 * Composition primitive: a finite, ordered list of resilience elements
 * shared across all paradigms (sync, async, future reactive). The
 * pipeline is the unit of composition that integrations (proxy,
 * functional decoration, AspectJ, Spring) consume to apply resilience
 * around target code.
 *
 * <p>An {@code InqPipeline} is constructed exclusively via
 * {@link #builder()} and the {@link InqPipelineBuilder} it returns.
 * The pipeline is structurally immutable once built — no element can
 * be added, replaced, or reordered after {@link InqPipelineBuilder#build()}
 * returns.</p>
 *
 * <p>Per ADR-040, the interface intentionally allows multiple
 * implementations. The default builder produces one
 * {@code DefaultInqPipeline}; integration modules may wrap a pipeline
 * in additional behaviour (e.g. a diagnostic wrapper that records all
 * applied elements) by implementing this interface.</p>
 *
 * @see InqPipelineBuilder
 */
public interface InqPipeline {

    /**
     * Returns the pipeline's elements in canonical composition order
     * (outermost first). The list is unmodifiable; modification
     * attempts throw {@link UnsupportedOperationException}.
     *
     * <p>Ordering follows ADR-041 — the builder reorders elements at
     * {@code build()} time according to the configured ordering
     * strategy. The {@code shield(...)} call order in the builder is
     * not the composition order.</p>
     *
     * @return the ordered, unmodifiable element list
     */
    List<InqElement> elements();

    /**
     * Creates a new, single-use {@link InqPipelineBuilder}. Each call
     * returns a fresh builder; builders are not reusable after their
     * {@code build()} method returns.
     *
     * @return a fresh builder
     */
    static InqPipelineBuilder builder() {
        return new InqPipelineBuilder();
    }
}
