package eu.inqudium.pipeline;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.annotation.evaluator.EvaluationResult;
import eu.inqudium.annotation.evaluator.InqAnnotationConfigurationException;
import eu.inqudium.annotation.evaluator.MethodPlan;
import eu.inqudium.core.element.InqElementType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InqPipelineAnnotationEvaluatorTest {

    @Nested
    class HappyPath {

        @Test
        void should_evaluate_an_unannotated_service_method_as_passthrough() throws NoSuchMethodException {
            // Given — UnannotatedImpl has no resilience annotations on the
            // implementation class, so the per-method plan must come back
            // as PassThrough.
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.BULKHEAD, "bh"))
                    .build();
            Method interfaceMethod = UnannotatedApi.class.getDeclaredMethod("perform", String.class);

            // When
            EvaluationResult result = InqPipelineAnnotationEvaluator.evaluate(
                    pipeline, UnannotatedApi.class, UnannotatedImpl.class);

            // Then
            assertThat(result.plans()).containsKey(interfaceMethod);
            assertThat(result.plans().get(interfaceMethod))
                    .isEqualTo(new MethodPlan.PassThrough());
        }

        @Test
        void should_evaluate_an_annotated_method_referring_to_a_known_element_as_decorated()
                throws NoSuchMethodException {
            // Given — the impl method carries @InqBulkhead("bh"); the
            // pipeline carries exactly that element. The bridge must
            // produce a Decorated plan that names "bh".
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.BULKHEAD, "bh"))
                    .build();
            Method interfaceMethod = BulkheadApi.class.getDeclaredMethod("perform", String.class);

            // When
            EvaluationResult result = InqPipelineAnnotationEvaluator.evaluate(
                    pipeline, BulkheadApi.class, BulkheadImpl.class);

            // Then
            assertThat(result.plans().get(interfaceMethod))
                    .isEqualTo(new MethodPlan.Decorated(List.of("bh")));
        }
    }

    @Nested
    class ErrorPropagation {

        @Test
        void should_propagate_inq_annotation_configuration_exception_for_unknown_element() {
            // What is to be tested?
            //   The bridge must not swallow or translate the
            //   InqAnnotationConfigurationException raised by the
            //   underlying evaluator when an annotation references an
            //   element name that is not present in the pipeline.
            // How will the test case be deemed successful and why?
            //   The exception thrown by the bridge is exactly the
            //   evaluator's InqAnnotationConfigurationException, with no
            //   wrapping layer in between. Asserting on the concrete
            //   subtype pins the contract.
            // Why is it important to test this test case?
            //   3.8 (ProxyBuilder) will need to either propagate or catch
            //   this exception explicitly. If the bridge wrapped it, every
            //   downstream catch block would silently miss the failure.

            // Given — pipeline carries elements with different names than
            // the @InqRetry("missing") annotation references.
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.RETRY, "rt"))
                    .build();

            // When / Then
            assertThatThrownBy(() -> InqPipelineAnnotationEvaluator.evaluate(
                    pipeline, MissingNameApi.class, MissingNameImpl.class))
                    .isInstanceOf(InqAnnotationConfigurationException.class);
        }
    }

    @Nested
    class LegacyBridgeMechanics {

        @Test
        void should_construct_an_equivalent_legacy_pipeline_for_the_evaluator_call()
                throws NoSuchMethodException {
            // What is to be tested?
            //   The bridge feeds every element of the new pipeline into the
            //   legacy pipeline it builds for the evaluator. A bug that
            //   dropped, reordered, or duplicated elements would surface as
            //   an unknown-name error from the underlying evaluator (the
            //   annotation references both names; either name missing in
            //   the legacy pipeline triggers
            //   InqAnnotationConfigurationException).
            // How will the test case be deemed successful and why?
            //   The Decorated plan carries both element names in canonical
            //   INQUDIUM order (CB=500 outermost before RT=600 innermost).
            //   Two names present and correctly ordered is positive
            //   evidence that the bridge fed both elements to the
            //   evaluator.
            // Why is it important to test this test case?
            //   This pins the bridge's element-forwarding contract — the
            //   only externally observable promise the bridge makes.

            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.CIRCUIT_BREAKER, "cb"))
                    .shield(new TestElement(InqElementType.RETRY, "rt"))
                    .build();
            Method interfaceMethod = TwoElementApi.class.getDeclaredMethod("perform", String.class);

            // When
            EvaluationResult result = InqPipelineAnnotationEvaluator.evaluate(
                    pipeline, TwoElementApi.class, TwoElementImpl.class);

            // Then — both names known, canonical order preserved
            assertThat(result.plans().get(interfaceMethod))
                    .isEqualTo(new MethodPlan.Decorated(List.of("cb", "rt")));
        }
    }

    // =====================================================================
    // Fixtures
    // =====================================================================

    interface UnannotatedApi {
        String perform(String input);
    }

    static class UnannotatedImpl implements UnannotatedApi {
        @Override
        public String perform(String input) {
            return input;
        }
    }

    interface BulkheadApi {
        String perform(String input);
    }

    static class BulkheadImpl implements BulkheadApi {
        @Override
        @InqBulkhead("bh")
        public String perform(String input) {
            return input;
        }
    }

    interface MissingNameApi {
        String perform(String input);
    }

    static class MissingNameImpl implements MissingNameApi {
        @Override
        @InqRetry("missing")
        public String perform(String input) {
            return input;
        }
    }

    interface TwoElementApi {
        String perform(String input);
    }

    static class TwoElementImpl implements TwoElementApi {
        @Override
        @InqCircuitBreaker("cb")
        @InqRetry("rt")
        public String perform(String input) {
            return input;
        }
    }
}
