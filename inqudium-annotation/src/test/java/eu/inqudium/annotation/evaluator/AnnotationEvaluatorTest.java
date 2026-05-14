package eu.inqudium.annotation.evaluator;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.annotation.InqShield;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqPipeline;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests for {@link AnnotationEvaluator}. The fixtures pin the
 * full ADR-036 algorithm: per-method dispatch via the inheritance walk,
 * annotation-to-name projection, pipeline-existence validation, and the
 * propagation of validation failures from the underlying resolvers.
 */
class AnnotationEvaluatorTest {

    /**
     * The pipeline used in every positive test. It contains stub elements
     * for every name that any fixture annotation references; this keeps
     * the pipeline-existence check away from accidental failures in tests
     * that focus on other behaviour.
     */
    private static final InqPipeline FULL_PIPELINE = pipelineWithElements(
            stubElement("cb", InqElementType.CIRCUIT_BREAKER),
            stubElement("rt", InqElementType.RETRY),
            stubElement("bh", InqElementType.BULKHEAD),
            stubElement("tl", InqElementType.TIME_LIMITER),
            stubElement("classCb", InqElementType.CIRCUIT_BREAKER),
            stubElement("classBh", InqElementType.BULKHEAD),
            stubElement("methodRt", InqElementType.RETRY),
            stubElement("bridge", InqElementType.RETRY));

    private final AnnotationEvaluator evaluator = AnnotationEvaluator.forPipeline(FULL_PIPELINE);

    // ---------------------------------------------------------------------
    // MethodLevel plans
    // ---------------------------------------------------------------------

    @Nested
    class MethodLevelPlans {

        @Test
        void should_produce_decorated_plan_with_single_element_when_method_carries_one_annotation() {
            // Given
            Method interfaceMethod = declared(SingleAnnotationApi.class, "perform", String.class);

            // When
            EvaluationResult result = evaluator.evaluate(
                    SingleAnnotationApi.class, SingleAnnotationImpl.class);

            // Then
            assertThat(result.plans()).containsKey(interfaceMethod);
            assertThat(result.plans().get(interfaceMethod))
                    .isEqualTo(new MethodPlan.Decorated(List.of("rt")));
        }

        @Test
        void should_order_multiple_annotations_in_inqudium_default_order_when_no_shield_overrides() {
            // What is to be tested?
            //   The impl method carries @InqRetry and @InqCircuitBreaker without @InqShield. The
            //   default INQUDIUM ordering must place CIRCUIT_BREAKER before RETRY (CB=500, RT=600;
            //   lower order = outermost first).
            // How will the test case be deemed successful and why?
            //   The Decorated list is exactly [cb, rt]; this proves the evaluator fed the impl method
            //   to OrderingResolver and projected the resulting type list onto the annotation names
            //   in the same order.
            // Why is it important to test this test case?
            //   This is the canonical multi-element annotation case from ADR-036 §1, the one most
            //   user code will hit. A bug that returned the wrong order or the wrong names would be
            //   directly visible to every user.

            // Given
            Method interfaceMethod = declared(MultipleAnnotationApi.class, "process", int.class);

            // When
            EvaluationResult result = evaluator.evaluate(
                    MultipleAnnotationApi.class, MultipleAnnotationImpl.class);

            // Then
            assertThat(result.plans().get(interfaceMethod))
                    .isEqualTo(new MethodPlan.Decorated(List.of("cb", "rt")));
        }

        @Test
        void should_resolve_bridge_to_typed_method_for_generic_interface_and_cache_against_interface_method() {
            // What is to be tested?
            //   GenericApi<T> declares accept(T). BridgedImpl implements GenericApi<String> with
            //   @InqRetry on accept(String). The compiler emits a bridge accept(Object). The
            //   evaluator must follow the bridge to the typed method to discover the annotation, and
            //   the resulting MethodPlan must be cached against the interface's accept(Object).
            // How will the test case be deemed successful and why?
            //   The plan map's key is the erased interface method (accept(Object)) and the plan is
            //   Decorated(["bridge"]). This proves both that the bridge was resolved and that the
            //   ADR-036 §5 step-5 caching contract holds.
            // Why is it important to test this test case?
            //   Every Spring service that exposes a generic interface produces this constellation.
            //   Caching against the typed method instead of the interface method would miss every
            //   future lookup keyed by the interface's reflective method handle.

            // Given
            Method interfaceMethod = declared(GenericApi.class, "accept", Object.class);

            // When
            EvaluationResult result = evaluator.evaluate(GenericApi.class, BridgedImpl.class);

            // Then
            assertThat(result.plans()).containsOnlyKeys(interfaceMethod);
            assertThat(result.plans().get(interfaceMethod))
                    .isEqualTo(new MethodPlan.Decorated(List.of("bridge")));
        }
    }

    // ---------------------------------------------------------------------
    // ClassLevelOnly plans
    // ---------------------------------------------------------------------

    @Nested
    class ClassLevelPlans {

        @Test
        void should_use_class_level_annotations_when_method_has_no_method_level_annotation() {
            // Given — ClassLevelImpl carries @InqCircuitBreaker("classCb") at class level; the
            // method itself has none.
            Method interfaceMethod = declared(SingleAnnotationApi.class, "perform", String.class);

            // When
            EvaluationResult result = evaluator.evaluate(
                    SingleAnnotationApi.class, ClassLevelImpl.class);

            // Then
            assertThat(result.plans().get(interfaceMethod))
                    .isEqualTo(new MethodPlan.Decorated(List.of("classCb")));
        }
    }

    // ---------------------------------------------------------------------
    // Override semantics: method-level wins, class-level is ignored entirely
    // ---------------------------------------------------------------------

    @Nested
    class OverrideSemantics {

        @Test
        void should_ignore_class_level_annotation_entirely_when_method_level_annotation_is_present() {
            // What is to be tested?
            //   MethodOverridesClassImpl carries class-level @InqBulkhead("classBh") and method-level
            //   @InqRetry("methodRt"). Per ADR-036 §6, method-level wins completely — the class-level
            //   bulkhead must NOT contribute to the plan.
            // How will the test case be deemed successful and why?
            //   The Decorated list contains only "methodRt" and never "classBh". A merged plan would
            //   include both; a swapped plan would carry only "classBh". Either bug is directly
            //   distinguishable from the correct outcome.
            // Why is it important to test this test case?
            //   The "method overrides class" rule is the central protection against accidentally
            //   composing unrelated resilience elements. Pinning it end-to-end prevents the rule
            //   from regressing in any future change to the inheritance walk.

            // Given
            Method interfaceMethod = declared(SingleAnnotationApi.class, "perform", String.class);

            // When
            EvaluationResult result = evaluator.evaluate(
                    SingleAnnotationApi.class, MethodOverridesClassImpl.class);

            // Then
            assertThat(result.plans().get(interfaceMethod))
                    .isEqualTo(new MethodPlan.Decorated(List.of("methodRt")));
        }
    }

    // ---------------------------------------------------------------------
    // PassThrough plans
    // ---------------------------------------------------------------------

    @Nested
    class PassThroughPlans {

        @Test
        void should_produce_pass_through_for_unoverridden_default_interface_method() {
            // Given
            Method interfaceMethod = declared(DefaultMethodApi.class, "greet");

            // When
            EvaluationResult result = evaluator.evaluate(
                    DefaultMethodApi.class, NoOverrideImpl.class);

            // Then
            assertThat(result.plans().get(interfaceMethod))
                    .isEqualTo(new MethodPlan.PassThrough());
        }

        @Test
        void should_produce_pass_through_when_no_resilience_annotation_exists_anywhere() {
            // Given — UnannotatedImpl has neither method-level nor class-level resilience annotations
            Method interfaceMethod = declared(SingleAnnotationApi.class, "perform", String.class);

            // When
            EvaluationResult result = evaluator.evaluate(
                    SingleAnnotationApi.class, UnannotatedImpl.class);

            // Then
            assertThat(result.plans().get(interfaceMethod))
                    .isEqualTo(new MethodPlan.PassThrough());
        }
    }

    // ---------------------------------------------------------------------
    // Multiple methods on the same interface
    // ---------------------------------------------------------------------

    @Nested
    class MultipleMethods {

        @Test
        void should_produce_independent_plans_for_each_method_of_a_multi_method_interface() {
            // What is to be tested?
            //   MixedApi declares three methods: a method-level decorated one, a default that the impl
            //   does not override, and a class-level fallback. The evaluator must populate one entry
            //   per interface method with the correct variant for each.
            // How will the test case be deemed successful and why?
            //   The plan map has exactly the three interface methods as keys; each one carries the
            //   plan its fixture predicts. No method's plan leaks into another's slot.
            // Why is it important to test this test case?
            //   Real services have many methods. A bug that mutated shared state across iterations
            //   (e.g. a static buffer in the evaluator) would surface here and not in a
            //   single-method test.

            // Given
            Method decorated = declared(MixedApi.class, "decorated", String.class);
            Method passThrough = declared(MixedApi.class, "passThrough");
            Method classLevel = declared(MixedApi.class, "classLevel", int.class);

            // When
            EvaluationResult result = evaluator.evaluate(MixedApi.class, MixedImpl.class);

            // Then — three keys, three independent plans
            assertThat(result.plans()).containsOnlyKeys(decorated, passThrough, classLevel);
            assertThat(result.plans().get(decorated))
                    .isEqualTo(new MethodPlan.Decorated(List.of("methodRt")));
            assertThat(result.plans().get(passThrough))
                    .isEqualTo(new MethodPlan.PassThrough());
            assertThat(result.plans().get(classLevel))
                    .isEqualTo(new MethodPlan.Decorated(List.of("classCb")));
        }
    }

    // ---------------------------------------------------------------------
    // Validation failures
    // ---------------------------------------------------------------------

    @Nested
    class ValidationFailures {

        @Test
        void should_throw_with_descriptive_message_when_annotation_references_unknown_element_name() {
            // What is to be tested?
            //   UnknownNameImpl annotates perform with @InqRetry("nonexistent"); the pipeline does
            //   not contain an element with that name. The evaluator must abort with
            //   InqAnnotationConfigurationException whose message names the service interface, the
            //   method, and the missing element name so the diagnostic points squarely at the
            //   offending site.
            // How will the test case be deemed successful and why?
            //   The exception is the configuration exception, and its message contains all three
            //   identifiers. Asserting all three in one go pins the contract that diagnostic output
            //   carries enough context to locate the bug.
            // Why is it important to test this test case?
            //   This is the user-facing failure path for the most common misconfiguration: an
            //   annotation that references a name that has not been registered. A vague exception
            //   message would force users to grep through reflection output to find the offender.

            // Given
            Method interfaceMethod = declared(SingleAnnotationApi.class, "perform", String.class);

            // When / Then
            assertThatThrownBy(() -> evaluator.evaluate(
                    SingleAnnotationApi.class, UnknownNameImpl.class))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining(SingleAnnotationApi.class.getName())
                    .hasMessageContaining(interfaceMethod.getName())
                    .hasMessageContaining("nonexistent");
        }

        @Test
        void should_propagate_ordering_resolver_failure_and_abort_the_entire_evaluation() {
            // What is to be tested?
            //   ConflictingShieldImpl carries @InqShield(order = "RESILIENCE4J", customOrder = {RETRY}),
            //   which is the §9 mutual-exclusion violation that DefaultOrderingResolver rejects.
            //   The exception must propagate unchanged from the OrderingResolver call inside
            //   evaluate(), aborting the whole evaluation rather than producing a partial map.
            // How will the test case be deemed successful and why?
            //   InqAnnotationConfigurationException is thrown; no EvaluationResult is returned. One
            //   such test is sufficient — the detailed §9 cases are covered by OrderingResolverTest;
            //   here we only pin the propagation contract.
            // Why is it important to test this test case?
            //   The fail-fast contract from the prompt and from ADR-036 §9 hinges on the evaluator
            //   not swallowing or accumulating downstream errors. A bug that wrapped the cause in a
            //   different exception type would make catch blocks at higher layers miss it.

            // Given / When / Then
            assertThatThrownBy(() -> evaluator.evaluate(
                    SingleAnnotationApi.class, ConflictingShieldImpl.class))
                    .isInstanceOf(InqAnnotationConfigurationException.class);
        }
    }

    // ---------------------------------------------------------------------
    // Defensive checks
    // ---------------------------------------------------------------------

    @Nested
    class DefensiveChecks {

        @Test
        void should_reject_null_pipeline_in_factory_with_illegal_argument_exception() {
            // When / Then
            assertThatThrownBy(() -> AnnotationEvaluator.forPipeline(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pipeline");
        }

        @Test
        void should_reject_null_service_interface_with_illegal_argument_exception() {
            // When / Then
            assertThatThrownBy(() -> evaluator.evaluate(null, SingleAnnotationImpl.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("serviceInterface");
        }

        @Test
        void should_reject_null_implementation_class_with_illegal_argument_exception() {
            // When / Then
            assertThatThrownBy(() -> evaluator.evaluate(SingleAnnotationApi.class, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("implementationClass");
        }

        @Test
        void should_reject_concrete_class_passed_as_service_interface_with_illegal_argument_exception() {
            // What is to be tested?
            //   The evaluator's contract is interface-driven: passing a concrete class as
            //   serviceInterface is a programmer error. The defensive check must reject it before
            //   any annotation processing runs.
            // How will the test case be deemed successful and why?
            //   IllegalArgumentException is thrown and the message names the offending class so the
            //   programmer can locate the call site. The exception must not be the
            //   InqAnnotationConfigurationException — that one signals annotation configuration
            //   issues, not API misuse.
            // Why is it important to test this test case?
            //   Without this guard, getMethods() on a concrete class would return Object's public
            //   methods plus the impl's own — yielding a confusing, half-correct EvaluationResult
            //   instead of an immediate failure.

            // When / Then
            assertThatThrownBy(() -> evaluator.evaluate(
                    SingleAnnotationImpl.class, SingleAnnotationImpl.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(SingleAnnotationImpl.class.getName());
        }
    }

    // ---------------------------------------------------------------------
    // Map immutability — the EvaluationResult contract
    // ---------------------------------------------------------------------

    @Nested
    class ResultMapImmutability {

        @Test
        void should_return_an_unmodifiable_plan_map_so_callers_cannot_mutate_evaluation_results() {
            // Given
            EvaluationResult result = evaluator.evaluate(
                    SingleAnnotationApi.class, SingleAnnotationImpl.class);

            // When / Then
            assertThatThrownBy(() -> result.plans().put(null, new MethodPlan.PassThrough()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =====================================================================
    // Reflection helpers
    // =====================================================================

    private static Method declared(Class<?> declaringClass, String name, Class<?>... parameterTypes) {
        try {
            return declaringClass.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("missing declared method " + declaringClass.getName() + "#"
                    + name + Arrays.toString(parameterTypes), e);
        }
    }

    // =====================================================================
    // Pipeline scaffolding — local stub element implementation, no public utility
    // =====================================================================

    private static InqPipeline pipelineWithElements(InqElement... elements) {
        InqPipeline.Builder builder = InqPipeline.builder();
        for (InqElement element : elements) {
            builder.shield(element);
        }
        return builder.build();
    }

    private static InqElement stubElement(String name, InqElementType type) {
        return new StubElement(name, type);
    }

    /**
     * Minimal {@link InqElement} for tests. The evaluator only ever reads
     * {@link #name()} from the pipeline, so the other accessors return
     * harmless defaults.
     */
    private record StubElement(String name, InqElementType elementType) implements InqElement {
        @Override
        public InqEventPublisher eventPublisher() {
            return null;
        }
    }

    // =====================================================================
    // Fixture interfaces
    // =====================================================================

    interface SingleAnnotationApi {
        void perform(String input);
    }

    interface MultipleAnnotationApi {
        int process(int input);
    }

    interface DefaultMethodApi {
        default String greet() {
            return "default";
        }
    }

    interface GenericApi<T> {
        void accept(T value);
    }

    interface MixedApi {
        void decorated(String input);

        default String passThrough() {
            return "default";
        }

        int classLevel(int input);
    }

    // =====================================================================
    // Fixture implementations
    // =====================================================================

    static class SingleAnnotationImpl implements SingleAnnotationApi {
        @Override
        @InqRetry("rt")
        public void perform(String input) {
            // single annotation, default INQUDIUM order
        }
    }

    static class MultipleAnnotationImpl implements MultipleAnnotationApi {
        @Override
        @InqCircuitBreaker("cb")
        @InqRetry("rt")
        public int process(int input) {
            return input;
        }
    }

    @InqCircuitBreaker("classCb")
    static class ClassLevelImpl implements SingleAnnotationApi {
        @Override
        public void perform(String input) {
            // class-level annotation drives the plan
        }
    }

    @InqBulkhead("classBh")
    static class MethodOverridesClassImpl implements SingleAnnotationApi {
        @Override
        @InqRetry("methodRt")
        public void perform(String input) {
            // method-level annotation must shadow class-level @InqBulkhead entirely
        }
    }

    static class NoOverrideImpl implements DefaultMethodApi {
        // Intentionally empty: greet() is the unoverridden interface default.
    }

    static class UnannotatedImpl implements SingleAnnotationApi {
        @Override
        public void perform(String input) {
            // no annotations on the impl, no class-level fallback either
        }
    }

    static class BridgedImpl implements GenericApi<String> {
        @Override
        @InqRetry("bridge")
        public void accept(String value) {
            // typed method carries the annotation; the compiler emits a bridge accept(Object)
        }
    }

    @InqCircuitBreaker("classCb")
    static class MixedImpl implements MixedApi {
        @Override
        @InqRetry("methodRt")
        public void decorated(String input) {
            // method-level overrides the class-level @InqCircuitBreaker for this method
        }

        @Override
        public int classLevel(int input) {
            // No method-level annotation: the class-level @InqCircuitBreaker drives the plan.
            return input;
        }
        // passThrough() is intentionally not overridden; the interface default applies.
    }

    static class UnknownNameImpl implements SingleAnnotationApi {
        @Override
        @InqRetry("nonexistent")
        public void perform(String input) {
            // references an element name that the test pipeline does not declare
        }
    }

    static class ConflictingShieldImpl implements SingleAnnotationApi {
        @Override
        @InqShield(order = "RESILIENCE4J", customOrder = {InqElementType.RETRY})
        @InqRetry("rt")
        public void perform(String input) {
            // §9 mutual-exclusion violation: order != INQUDIUM and customOrder is non-empty
        }
    }
}
