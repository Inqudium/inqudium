package eu.inqudium.proxy.construction;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.pipeline.InqPipeline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElementResolverTest {

    @Test
    void should_resolve_a_single_name_to_the_matching_element() {
        // Given
        FakeDecorator bulkhead = new FakeDecorator("bh", InqElementType.BULKHEAD);
        InqPipeline pipeline = InqPipeline.builder().shield(bulkhead).build();

        // When
        List<InqElement> resolved = ElementResolver.resolveNames(List.of("bh"), pipeline);

        // Then
        assertThat(resolved).containsExactly(bulkhead);
    }

    @Test
    void should_resolve_multiple_names_preserving_order() {
        // Given — names list order ("bh", then "cb") must be preserved
        // even though the pipeline's canonical order places "cb"
        // (CIRCUIT_BREAKER=500) before "bh" (BULKHEAD=400 outer — wait
        // BULKHEAD=400 is outer and CB=500 is inner, so canonical
        // order is bh, cb). We provide names in the reverse order to
        // pin the resolver's preservation contract.
        FakeDecorator bulkhead = new FakeDecorator("bh", InqElementType.BULKHEAD);
        FakeDecorator breaker = new FakeDecorator("cb", InqElementType.CIRCUIT_BREAKER);
        InqPipeline pipeline = InqPipeline.builder()
                .shield(bulkhead)
                .shield(breaker)
                .build();

        // When
        List<InqElement> resolved = ElementResolver.resolveNames(
                List.of("cb", "bh"), pipeline);

        // Then
        assertThat(resolved).containsExactly(breaker, bulkhead);
    }

    @Test
    void should_return_an_empty_list_when_names_is_empty() {
        // Given
        InqPipeline pipeline = InqPipeline.builder()
                .shield(new FakeDecorator("bh", InqElementType.BULKHEAD))
                .build();

        // When
        List<InqElement> resolved = ElementResolver.resolveNames(List.of(), pipeline);

        // Then
        assertThat(resolved).isEmpty();
    }

    @Test
    void should_throw_illegal_state_exception_when_a_name_is_unknown() {
        // What is to be tested?
        //   ElementResolver is the proxy module's defensive guard
        //   against evaluator/pipeline drift. The evaluator validates
        //   that every annotation-referenced name exists in the
        //   pipeline, so the resolver should normally never miss; if
        //   it does, the misconfiguration must surface loudly rather
        //   than be tolerated.
        // How will the test case be deemed successful and why?
        //   An IllegalStateException is raised and its message names
        //   both the offending element name and ADR-036, pointing the
        //   investigator at the evaluator as the upstream contract.
        // Why is it important to test this test case?
        //   This is the one explicit error path the resolver carries.
        //   Without a test, a regression that silently dropped names
        //   would only surface as an obscure NullPointerException
        //   later during fold.

        // Given
        InqPipeline pipeline = InqPipeline.builder()
                .shield(new FakeDecorator("bh", InqElementType.BULKHEAD))
                .build();

        // When / Then
        assertThatThrownBy(() -> ElementResolver.resolveNames(
                List.of("missing"), pipeline))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing")
                .hasMessageContaining("ADR-036");
    }

    @Test
    void should_reject_null_names() {
        // Given
        InqPipeline pipeline = InqPipeline.builder()
                .shield(new FakeDecorator("bh", InqElementType.BULKHEAD))
                .build();

        // When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> ElementResolver.resolveNames(null, pipeline))
                .withMessage("names");
    }

    @Test
    void should_reject_null_pipeline() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> ElementResolver.resolveNames(List.of("bh"), null))
                .withMessage("pipeline");
    }
}
