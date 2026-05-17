package eu.inqudium.proxy.introspection;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyStackInfoTest {

    interface SomeService {
        String anything();
    }

    /**
     * Test-only no-op element fixture. Used to populate the DTO's
     * elements list without depending on the live pipeline machinery.
     */
    static final class FakeElement implements InqElement {
        private final String name;

        FakeElement(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public InqElementType elementType() {
            return InqElementType.BULKHEAD;
        }

        @Override
        public InqEventPublisher eventPublisher() {
            return null;
        }
    }

    @Test
    void should_construct_with_all_four_components() {
        // Given
        FakeElement element = new FakeElement("orderBh");

        // When
        ProxyStackInfo info = new ProxyStackInfo(
                42L,
                Optional.of(SomeService.class),
                List.of(element),
                List.of());

        // Then
        assertThat(info.stackId()).isEqualTo(42L);
        assertThat(info.targetType()).contains(SomeService.class);
        assertThat(info.elements()).containsExactly(element);
        assertThat(info.methodLayers()).isEmpty();
    }

    @Test
    void should_make_elements_immutable_via_copy() {
        // Given
        List<InqElement> source = new ArrayList<>();
        source.add(new FakeElement("a"));
        ProxyStackInfo info = new ProxyStackInfo(
                1L, Optional.of(SomeService.class), source, List.of());

        // When — mutate the source after construction
        source.add(new FakeElement("b"));

        // Then
        assertThat(info.elements()).hasSize(1);
        assertThatThrownBy(() -> info.elements().add(new FakeElement("c")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_make_method_layers_immutable_via_copy() {
        // What is to be tested?
        //   The DTO defensively copies methodLayers; consumers can
        //   retain the DTO and rely on its content stability even if
        //   the builder mutates the source list afterward.
        // How will the test case be deemed successful and why?
        //   After mutating the source list, the DTO's view is
        //   unchanged. The returned list rejects mutation.
        // Why is it important to test this test case?
        //   ADR-039 introspection promises immutable DTOs; without
        //   the copy, diagnostic consumers could observe topology
        //   drift behind their backs.

        // Given
        List<MethodLayers> source = new ArrayList<>();
        source.add(new MethodLayers("Sig.foo()", List.of(), Optional.empty()));
        ProxyStackInfo info = new ProxyStackInfo(
                1L, Optional.of(SomeService.class), List.of(), source);

        // When — mutate the source after construction
        source.add(new MethodLayers("Sig.bar()", List.of(), Optional.empty()));

        // Then
        assertThat(info.methodLayers()).hasSize(1);
        assertThatThrownBy(() -> info.methodLayers().add(
                new MethodLayers("Sig.baz()", List.of(), Optional.empty())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_reject_null_target_type_with_npe() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new ProxyStackInfo(1L, null, List.of(), List.of()))
                .withMessage("targetType");
    }

    @Test
    void should_accept_target_type_as_empty_optional() {
        // What is to be tested?
        //   The DTO's targetType is declared Optional<Class<?>> per
        //   ADR-039 so that future paradigms (functional decoration,
        //   AspectJ class-based proxies) can carry an absent target
        //   type. The DTO accepts Optional.empty() even though the
        //   proxy paradigm always populates it.
        // How will the test case be deemed successful and why?
        //   The constructor accepts Optional.empty() and the accessor
        //   returns it unchanged.
        // Why is it important to test this test case?
        //   Pins the future-paradigm extensibility contract so a
        //   regression that required Optional.of(...) would be
        //   caught immediately.

        // Given / When
        ProxyStackInfo info = new ProxyStackInfo(
                1L, Optional.empty(), List.of(), List.of());

        // Then
        assertThat(info.targetType()).isEmpty();
    }
}
