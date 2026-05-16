package eu.inqudium.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class InqPipelineBuilderTest {

    @Nested
    class HappyPath {

        @Test
        void should_build_a_pipeline_containing_a_single_element() {
            // Given
            InqElement only = new TestElement(InqElementType.CIRCUIT_BREAKER, "only");

            // When
            InqPipeline pipeline = InqPipeline.builder().shield(only).build();

            // Then
            assertThat(pipeline.elements()).containsExactly(only);
        }

        @Test
        void should_return_elements_in_canonical_composition_order_regardless_of_shield_call_order() {
            // Given — insert in reverse of the canonical INQUDIUM order
            InqElement retry = new TestElement(InqElementType.RETRY, "r");
            InqElement circuitBreaker = new TestElement(InqElementType.CIRCUIT_BREAKER, "cb");
            InqElement bulkhead = new TestElement(InqElementType.BULKHEAD, "bh");
            InqElement rateLimiter = new TestElement(InqElementType.RATE_LIMITER, "rl");
            InqElement trafficShaper = new TestElement(InqElementType.TRAFFIC_SHAPER, "ts");
            InqElement timeLimiter = new TestElement(InqElementType.TIME_LIMITER, "tl");

            // When
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(retry)
                    .shield(circuitBreaker)
                    .shield(bulkhead)
                    .shield(rateLimiter)
                    .shield(trafficShaper)
                    .shield(timeLimiter)
                    .build();

            // Then — outermost (lowest order) first
            assertThat(pipeline.elements()).containsExactly(
                    timeLimiter, trafficShaper, rateLimiter, bulkhead, circuitBreaker, retry);
        }

        @Test
        void should_produce_the_same_result_for_varargs_shieldAll_as_for_repeated_shield_calls() {
            // Given
            InqElement cb = new TestElement(InqElementType.CIRCUIT_BREAKER, "cb");
            InqElement retry = new TestElement(InqElementType.RETRY, "r");

            // When
            InqPipeline viaShield = InqPipeline.builder().shield(cb).shield(retry).build();
            InqPipeline viaVarargs = InqPipeline.builder().shieldAll(cb, retry).build();

            // Then
            assertThat(viaVarargs.elements()).containsExactlyElementsOf(viaShield.elements());
        }

        @Test
        void should_produce_the_same_result_for_iterable_shieldAll_as_for_repeated_shield_calls() {
            // Given
            InqElement cb = new TestElement(InqElementType.CIRCUIT_BREAKER, "cb");
            InqElement retry = new TestElement(InqElementType.RETRY, "r");

            // When
            InqPipeline viaShield = InqPipeline.builder().shield(cb).shield(retry).build();
            InqPipeline viaIterable = InqPipeline.builder()
                    .shieldAll(List.of(cb, retry))
                    .build();

            // Then
            assertThat(viaIterable.elements()).containsExactlyElementsOf(viaShield.elements());
        }
    }

    @Nested
    class SingleUseEnforcement {

        @Test
        void should_reject_a_second_build_call_with_illegal_state_exception() {
            // Given
            InqPipelineBuilder builder = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.CIRCUIT_BREAKER, "cb"));
            builder.build();

            // When / Then
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(builder::build)
                    .withMessageContaining("InqPipeline.builder()");
        }

        @Test
        void should_reject_shield_after_build_with_illegal_state_exception() {
            // Given
            InqPipelineBuilder builder = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.CIRCUIT_BREAKER, "cb"));
            builder.build();
            InqElement extra = new TestElement(InqElementType.RETRY, "r");

            // When / Then
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> builder.shield(extra))
                    .withMessageContaining("InqPipeline.builder()");
        }

        @Test
        void should_consume_the_builder_even_when_build_fails_validation() {
            // What is to be tested: ADR-040 §4 "single-use" — a failed build()
            // call (e.g. on an empty builder) must still flip the built flag,
            // so the same builder cannot be retried by adding elements afterwards.
            // How will this be deemed successful: a subsequent shield(...) call
            // raises IllegalStateException (single-use), not IllegalArgumentException.
            // Why important: protects against accidental reuse patterns where a
            // caller catches the validation failure and tries to recover by
            // adding more elements to the same builder.

            // Given
            InqPipelineBuilder builder = InqPipeline.builder();
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(builder::build);

            // When / Then
            InqElement element = new TestElement(InqElementType.CIRCUIT_BREAKER, "cb");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> builder.shield(element))
                    .withMessageContaining("InqPipeline.builder()");
        }
    }

    @Nested
    class Validation {

        @Test
        void should_reject_a_build_call_on_an_empty_builder() {
            // Given
            InqPipelineBuilder builder = InqPipeline.builder();

            // When / Then
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(builder::build)
                    .withMessageContaining("must contain at least one element");
        }

        @Test
        void should_reject_two_elements_sharing_the_same_type_and_name() {
            // Given
            InqElement first = new TestElement(InqElementType.CIRCUIT_BREAKER, "paymentCb");
            InqElement second = new TestElement(InqElementType.CIRCUIT_BREAKER, "paymentCb");

            // When / Then
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> InqPipeline.builder()
                            .shield(first)
                            .shield(second)
                            .build())
                    .withMessageContaining("CIRCUIT_BREAKER")
                    .withMessageContaining("paymentCb");
        }

        @Test
        void should_allow_two_elements_with_the_same_name_but_different_types() {
            // What is to be tested: the uniqueness invariant is on the
            // (type, name) pair, not on the name alone. Two elements of
            // different types but the same name must coexist in the same
            // pipeline.
            // How will this be deemed successful: build() returns a pipeline
            // containing both elements, with no exception thrown.
            // Why important: name reuse across types is a legitimate
            // configuration pattern (e.g. a "payment" circuit-breaker and a
            // "payment" retry).

            // Given
            InqElement cb = new TestElement(InqElementType.CIRCUIT_BREAKER, "payment");
            InqElement retry = new TestElement(InqElementType.RETRY, "payment");

            // When
            InqPipeline pipeline = InqPipeline.builder().shield(cb).shield(retry).build();

            // Then
            assertThat(pipeline.elements()).containsExactlyInAnyOrder(cb, retry);
        }
    }

    @Nested
    class NullHandling {

        @Test
        void should_reject_a_null_element_on_shield() {
            // When / Then
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> InqPipeline.builder().shield(null))
                    .withMessageContaining("element must not be null");
        }

        @Test
        void should_reject_a_null_varargs_array_on_shieldAll() {
            // Note: passing (InqElement[]) null causes the for-each loop's
            // iterator setup to dereference null, producing a
            // NullPointerException.
            assertThatNullPointerException()
                    .isThrownBy(() -> InqPipeline.builder().shieldAll((InqElement[]) null));
        }

        @Test
        void should_reject_a_null_element_inside_the_varargs_array() {
            // Given
            InqElement valid = new TestElement(InqElementType.CIRCUIT_BREAKER, "cb");

            // When / Then
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> InqPipeline.builder().shieldAll(valid, null))
                    .withMessageContaining("element must not be null");
        }

        @Test
        void should_reject_a_null_element_inside_the_iterable() {
            // Given
            InqElement valid = new TestElement(InqElementType.CIRCUIT_BREAKER, "cb");

            // When / Then
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> InqPipeline.builder()
                            .shieldAll(java.util.Arrays.asList(valid, null)))
                    .withMessageContaining("element must not be null");
        }
    }
}
