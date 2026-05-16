package eu.inqudium.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

/**
 * Minimal {@link InqElement} fixture used by the pipeline tests in this
 * module. Deliberately lives in the test source set so we do not pull
 * in any paradigm module (e.g. {@code inqudium-imperative}).
 *
 * <p>{@link InqPipelineBuilder} only consults {@link #elementType()}
 * and {@link #name()}; {@link #eventPublisher()} is never invoked, so
 * this fixture returns {@code null} from it.</p>
 */
final class TestElement implements InqElement {

    private final InqElementType type;
    private final String name;

    TestElement(InqElementType type, String name) {
        this.type = type;
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public InqElementType elementType() {
        return type;
    }

    @Override
    public InqEventPublisher eventPublisher() {
        return null;
    }

    @Override
    public String toString() {
        return type + "(" + name + ")";
    }
}
