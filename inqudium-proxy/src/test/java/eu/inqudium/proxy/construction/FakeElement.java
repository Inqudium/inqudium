package eu.inqudium.proxy.construction;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;

/**
 * Minimal {@link InqElement} fixture for construction tests that
 * must verify the sync paradigm rejection — the element implements
 * {@link InqElement} but <em>not</em>
 * {@link eu.inqudium.core.pipeline.InqDecorator}.
 *
 * <p>The construction code under test never consults
 * {@link #eventPublisher()}, so the fixture returns {@code null}.</p>
 */
final class FakeElement implements InqElement {

    private final String name;
    private final InqElementType type;

    FakeElement(String name, InqElementType type) {
        this.name = name;
        this.type = type;
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
        return "FakeElement(" + name + ")";
    }
}
