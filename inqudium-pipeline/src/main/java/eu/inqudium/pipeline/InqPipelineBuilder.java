package eu.inqudium.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.pipeline.PipelineOrdering;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-use builder for {@link InqPipeline} instances, per ADR-040 §4.
 *
 * <p>Obtain a builder via {@link InqPipeline#builder()}. Add elements
 * with {@link #shield(InqElement)} or one of the {@code shieldAll}
 * overloads, then call {@link #build()} exactly once. After
 * {@code build()} returns, the builder is consumed and any further
 * call (including a second {@code build()}) raises
 * {@link IllegalStateException}.</p>
 *
 * <p>The order of {@code shield(...)} calls is not significant: the
 * builder sorts elements by the standard INQUDIUM pipeline ordering
 * ({@link PipelineOrdering#standard()}) at {@code build()} time, per
 * ADR-041.</p>
 *
 * <p>The builder is not thread-safe.</p>
 */
public final class InqPipelineBuilder {

    private final List<InqElement> elements = new ArrayList<>();
    private boolean built;

    InqPipelineBuilder() {
    }

    /**
     * Adds a resilience element to the pipeline.
     *
     * @param element the element to add, never {@code null}
     * @return this builder
     * @throws IllegalArgumentException if {@code element} is {@code null}
     * @throws IllegalStateException    if {@link #build()} has already
     *                                  been called on this builder
     */
    public InqPipelineBuilder shield(InqElement element) {
        requireNotBuilt();
        if (element == null) {
            throw new IllegalArgumentException("element must not be null");
        }
        elements.add(element);
        return this;
    }

    /**
     * Adds multiple elements at once. Equivalent to calling
     * {@link #shield(InqElement)} once per element in iteration order.
     *
     * @param elements the elements to add, never {@code null}; no
     *                 element inside the array may be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if any element is {@code null}
     * @throws NullPointerException     if {@code elements} itself is
     *                                  {@code null}
     * @throws IllegalStateException    if {@link #build()} has already
     *                                  been called on this builder
     */
    public InqPipelineBuilder shieldAll(InqElement... elements) {
        requireNotBuilt();
        for (InqElement element : elements) {
            shield(element);
        }
        return this;
    }

    /**
     * Adds multiple elements from an iterable. Equivalent to calling
     * {@link #shield(InqElement)} once per element in iteration order.
     *
     * @param elements the elements to add, never {@code null}; no
     *                 element inside the iterable may be {@code null}
     * @return this builder
     * @throws IllegalArgumentException if any element is {@code null}
     * @throws NullPointerException     if {@code elements} itself is
     *                                  {@code null}
     * @throws IllegalStateException    if {@link #build()} has already
     *                                  been called on this builder
     */
    public InqPipelineBuilder shieldAll(Iterable<? extends InqElement> elements) {
        requireNotBuilt();
        for (InqElement element : elements) {
            shield(element);
        }
        return this;
    }

    /**
     * Builds the immutable pipeline. May be called at most once per
     * builder instance: a second call raises
     * {@link IllegalStateException}. The builder is considered
     * consumed even when validation fails, so a {@code build()} call
     * that throws cannot be retried.
     *
     * <p>Validates that the pipeline contains at least one element and
     * that no two elements share the same
     * {@code (InqElementType, name)} pair, then sorts the elements
     * using {@link PipelineOrdering#standard()} (outermost first) and
     * returns a fresh, structurally immutable pipeline.</p>
     *
     * <p>Uniqueness validation is performed on the pair
     * {@code (InqElementType, name)}. The paradigm coordinate ADR-040
     * §3 mentions in passing is a method-level property (determined
     * from a service method's return type, e.g.
     * {@code CompletableFuture} → async) and is therefore enforced
     * downstream by the paradigm validator in {@code inqudium-proxy},
     * not at builder time. The builder has no service interface to
     * consult.</p>
     *
     * @return the built pipeline
     * @throws IllegalStateException if {@code build()} has already
     *                               been called, if the pipeline
     *                               contains no elements, or if two
     *                               elements share the same
     *                               {@code (InqElementType, name)}
     *                               pair
     */
    public InqPipeline build() {
        requireNotBuilt();
        built = true;

        if (elements.isEmpty()) {
            throw new IllegalStateException("pipeline must contain at least one element");
        }

        Map<TypeNameKey, InqElement> seen = new HashMap<>();
        for (InqElement element : elements) {
            TypeNameKey key = new TypeNameKey(element.elementType(), element.name());
            InqElement previous = seen.putIfAbsent(key, element);
            if (previous != null) {
                throw new IllegalStateException(
                        "duplicate element in pipeline: type=" + element.elementType()
                                + ", name=\"" + element.name() + "\"");
            }
        }

        PipelineOrdering ordering = PipelineOrdering.standard();
        List<InqElement> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparingInt(e -> ordering.orderFor(e.elementType())));

        return new DefaultInqPipeline(Collections.unmodifiableList(sorted));
    }

    private void requireNotBuilt() {
        if (built) {
            throw new IllegalStateException(
                    "this InqPipelineBuilder has already been consumed by build(); "
                            + "create a new builder via InqPipeline.builder()");
        }
    }

    private record TypeNameKey(InqElementType type, String name) {
    }
}
