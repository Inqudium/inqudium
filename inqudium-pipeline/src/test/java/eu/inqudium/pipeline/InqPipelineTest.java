package eu.inqudium.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class InqPipelineTest {

    @Test
    void should_return_an_unmodifiable_elements_list() {
        // Given
        InqPipeline pipeline = InqPipeline.builder()
                .shield(new TestElement(InqElementType.CIRCUIT_BREAKER, "cb"))
                .shield(new TestElement(InqElementType.RETRY, "r"))
                .build();
        List<InqElement> elements = pipeline.elements();
        InqElement extra = new TestElement(InqElementType.BULKHEAD, "bh");

        // When / Then — every mutator on the returned list throws
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> elements.add(extra));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> elements.remove(0));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(elements::clear);
    }

    @Test
    void should_return_a_non_null_builder_from_the_static_factory() {
        // When
        InqPipelineBuilder builder = InqPipeline.builder();

        // Then
        assertThat(builder).isNotNull();
    }

    @Test
    void should_return_a_fresh_builder_instance_on_each_factory_call() {
        // When
        InqPipelineBuilder first = InqPipeline.builder();
        InqPipelineBuilder second = InqPipeline.builder();

        // Then
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void should_include_all_element_names_in_toString() {
        // Given
        InqPipeline pipeline = InqPipeline.builder()
                .shield(new TestElement(InqElementType.CIRCUIT_BREAKER, "paymentCb"))
                .shield(new TestElement(InqElementType.RETRY, "paymentRetry"))
                .build();

        // When
        String text = pipeline.toString();

        // Then — loose assertion: we do not pin the exact format, only
        // that the representation mentions both element names.
        assertThat(text)
                .contains("paymentCb")
                .contains("paymentRetry");
    }
}
