package eu.inqudium.pipeline;

import eu.inqudium.core.element.InqElement;

import java.util.List;

/**
 * Default implementation of {@link InqPipeline}. Package-private;
 * external code obtains instances only via
 * {@link InqPipeline#builder()}.
 */
final class DefaultInqPipeline implements InqPipeline {

    private final List<InqElement> elements;

    DefaultInqPipeline(List<InqElement> elements) {
        // Builder passes an already-unmodifiable list; no defensive copy needed.
        this.elements = elements;
    }

    @Override
    public List<InqElement> elements() {
        return elements;
    }

    @Override
    public String toString() {
        return "InqPipeline" + elements;
    }
}
