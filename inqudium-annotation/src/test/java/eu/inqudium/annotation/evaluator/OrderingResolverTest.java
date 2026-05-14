package eu.inqudium.annotation.evaluator;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRateLimiter;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.annotation.InqShield;
import eu.inqudium.annotation.InqTimeLimiter;
import eu.inqudium.annotation.InqTrafficShaper;
import eu.inqudium.core.element.InqElementType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link DefaultOrderingResolver}. The fixtures pin down the
 * three ordering modes from ADR-036 §3 (INQUDIUM default, RESILIENCE4J,
 * customOrder) and each negative branch of §9 that is decidable at the
 * {@code @InqShield} layer.
 */
class OrderingResolverTest {

    private final OrderingResolver resolver = new DefaultOrderingResolver();

    // ---------------------------------------------------------------------
    // INQUDIUM default ordering
    // ---------------------------------------------------------------------

    @Nested
    class InqudiumDefault {

        @Test
        void should_return_single_element_type_when_only_one_element_annotation_is_present_without_shield() {
            // Given — a method with @InqRetry only, no @InqShield
            Method method = methodOf(SingleRetry.class, "perform");

            // When
            List<InqElementType> order = resolver.resolveOrder(method);

            // Then — INQUDIUM default applies trivially
            assertThat(order).containsExactly(InqElementType.RETRY);
        }

        @Test
        void should_sort_multiple_annotations_into_default_pipeline_order_when_shield_is_absent() {
            // What is to be tested?
            //   AllSixWithoutShield carries all six element annotations and no @InqShield. With no
            //   @InqShield, the resolver must default to INQUDIUM (canonical) ordering.
            // How will the test case be deemed successful and why?
            //   The returned list is sorted by InqElementType.defaultPipelineOrder() ascending —
            //   TIME_LIMITER (100) → TRAFFIC_SHAPER (200) → RATE_LIMITER (300) → BULKHEAD (400) →
            //   CIRCUIT_BREAKER (500) → RETRY (600), outermost-first. This pins the absent-shield
            //   default behaviour.
            // Why is it important to test this test case?
            //   The "no @InqShield = INQUDIUM" rule is the default behaviour every user relies on,
            //   and silently swapping it for any other order would cascade into mis-composed pipelines.

            // Given
            Method method = methodOf(AllSixWithoutShield.class, "perform");

            // When
            List<InqElementType> order = resolver.resolveOrder(method);

            // Then
            assertThat(order).containsExactly(
                    InqElementType.TIME_LIMITER,
                    InqElementType.TRAFFIC_SHAPER,
                    InqElementType.RATE_LIMITER,
                    InqElementType.BULKHEAD,
                    InqElementType.CIRCUIT_BREAKER,
                    InqElementType.RETRY);
        }

        @Test
        void should_sort_multiple_annotations_into_default_pipeline_order_when_shield_explicitly_selects_inqudium() {
            // Given — same fixture set as above but @InqShield(order = "INQUDIUM") is explicit
            Method method = methodOf(AllSixWithInqudiumShield.class, "perform");

            // When
            List<InqElementType> order = resolver.resolveOrder(method);

            // Then — explicit "INQUDIUM" is equivalent to omitting the shield
            assertThat(order).containsExactly(
                    InqElementType.TIME_LIMITER,
                    InqElementType.TRAFFIC_SHAPER,
                    InqElementType.RATE_LIMITER,
                    InqElementType.BULKHEAD,
                    InqElementType.CIRCUIT_BREAKER,
                    InqElementType.RETRY);
        }

        @Test
        void should_return_empty_list_when_no_element_annotation_is_present_and_no_shield_is_present() {
            // Given — a method with neither element annotations nor @InqShield
            Method method = methodOf(Bare.class, "perform");

            // When
            List<InqElementType> order = resolver.resolveOrder(method);

            // Then — the resolver does not throw; the empty set sorts to the empty list
            assertThat(order).isEmpty();
        }
    }

    // ---------------------------------------------------------------------
    // RESILIENCE4J ordering
    // ---------------------------------------------------------------------

    @Nested
    class Resilience4J {

        @Test
        void should_sort_multiple_annotations_into_resilience4j_order_when_shield_selects_it() {
            // What is to be tested?
            //   AllSixWithResilience4jShield carries all six element annotations plus
            //   @InqShield(order = "RESILIENCE4J"). The resolver must use the R4J profile
            //   (RETRY → CIRCUIT_BREAKER → TRAFFIC_SHAPER → RATE_LIMITER → TIME_LIMITER → BULKHEAD).
            // How will the test case be deemed successful and why?
            //   The returned list is in the exact R4J outermost-to-innermost sequence. This pins
            //   the named-strategy lookup against PipelineOrdering.resilience4j().
            // Why is it important to test this test case?
            //   The two named strategies are the only thing that bridges @InqShield(order = "X")
            //   to an actual nesting; if the lookup mis-resolves to INQUDIUM here, R4J users would
            //   silently get canonical nesting instead.

            // Given
            Method method = methodOf(AllSixWithResilience4jShield.class, "perform");

            // When
            List<InqElementType> order = resolver.resolveOrder(method);

            // Then — R4J profile, outermost first
            assertThat(order).containsExactly(
                    InqElementType.RETRY,
                    InqElementType.CIRCUIT_BREAKER,
                    InqElementType.TRAFFIC_SHAPER,
                    InqElementType.RATE_LIMITER,
                    InqElementType.TIME_LIMITER,
                    InqElementType.BULKHEAD);
        }
    }

    // ---------------------------------------------------------------------
    // customOrder ordering
    // ---------------------------------------------------------------------

    @Nested
    class CustomOrder {

        @Test
        void should_return_single_type_list_when_custom_order_has_one_entry_matching_the_only_annotation() {
            // Given — @InqShield(customOrder = {RETRY}) on a method carrying only @InqRetry
            Method method = methodOf(CustomSingleRetry.class, "perform");

            // When
            List<InqElementType> order = resolver.resolveOrder(method);

            // Then
            assertThat(order).containsExactly(InqElementType.RETRY);
        }

        @Test
        void should_apply_the_same_custom_order_to_methods_with_different_annotation_subsets() {
            // What is to be tested?
            //   Two methods declare identical customOrder = {BULKHEAD, CIRCUIT_BREAKER, RETRY} but
            //   carry different subsets of those annotations — one method carries @InqBulkhead and
            //   @InqRetry, the other carries all three. The resolver must project the customOrder
            //   onto each method's actual annotation set, preserving the customOrder's sequence.
            // How will the test case be deemed successful and why?
            //   Method 1 (BH + RT) resolves to [BULKHEAD, RETRY]; method 2 (BH + CB + RT) resolves to
            //   [BULKHEAD, CIRCUIT_BREAKER, RETRY]. Both follow the customOrder's sequence (BH
            //   before CB before RT), neither resorts to INQUDIUM or RESILIENCE4J order, and
            //   neither leaks absent types into the result.
            // Why is it important to test this test case?
            //   This is the source-level reuse pattern that the §3 adjustment (PR #58,
            //   superset-with-filter) enables: identical customOrder arrays can be repeated at each
            //   call site without forcing every site to align its annotation subset with the
            //   array. A regression that re-introduced set-equality validation would surface here
            //   as an exception on either method.

            // Given
            Method twoAnnotations = methodOf(SharedConstantReuseFixture.class, "two");
            Method threeAnnotations = methodOf(SharedConstantReuseFixture.class, "three");

            // When
            List<InqElementType> orderTwo = resolver.resolveOrder(twoAnnotations);
            List<InqElementType> orderThree = resolver.resolveOrder(threeAnnotations);

            // Then
            assertThat(orderTwo).containsExactly(InqElementType.BULKHEAD, InqElementType.RETRY);
            assertThat(orderThree).containsExactly(
                    InqElementType.BULKHEAD,
                    InqElementType.CIRCUIT_BREAKER,
                    InqElementType.RETRY);
        }

        @Test
        void should_return_custom_order_as_is_when_set_equals_the_present_annotations() {
            // What is to be tested?
            //   @InqShield(customOrder = {BULKHEAD, RETRY}) declared in that exact order on a method
            //   carrying @InqBulkhead and @InqRetry. The resolver must take customOrder verbatim,
            //   regardless of whether the sequence matches INQUDIUM or RESILIENCE4J.
            // How will the test case be deemed successful and why?
            //   The returned list is [BULKHEAD, RETRY] — neither of the named profiles would yield
            //   that sequence on its own. This pins the "customOrder taken as-is" contract.
            // Why is it important to test this test case?
            //   The whole point of customOrder is operator override; if the resolver re-sorted it,
            //   the override would be silently lost.

            // Given
            Method method = methodOf(CustomBulkheadThenRetry.class, "perform");

            // When
            List<InqElementType> order = resolver.resolveOrder(method);

            // Then — verbatim from the annotation
            assertThat(order).containsExactly(InqElementType.BULKHEAD, InqElementType.RETRY);
        }
    }

    // ---------------------------------------------------------------------
    // Class-level input
    // ---------------------------------------------------------------------

    @Nested
    class ClassLevelInput {

        @Test
        void should_resolve_order_uniformly_when_annotation_source_is_a_class_rather_than_a_method() {
            // What is to be tested?
            //   ClassLevelWithResilience4jShield carries class-level @InqBulkhead, @InqRetry plus
            //   @InqShield(order = "RESILIENCE4J"). The resolver is called with the Class object
            //   (not a Method). This pins the AnnotatedElement-not-Method API choice from sub-step 4.
            // How will the test case be deemed successful and why?
            //   The returned list is [RETRY, BULKHEAD] in R4J order — the same result a method
            //   carrying the same annotations would produce. This proves that AnnotatedElement
            //   subtypes are handled uniformly without code-path branches inside the resolver.
            // Why is it important to test this test case?
            //   Sub-step 5 will call the resolver from the ClassLevelOnly path with a Class<?> as
            //   input; a regression that broke class-level reads would silently strip resilience
            //   from every class-level-annotated implementation.

            // When
            List<InqElementType> order = resolver.resolveOrder(ClassLevelWithResilience4jShield.class);

            // Then
            assertThat(order).containsExactly(InqElementType.RETRY, InqElementType.BULKHEAD);
        }
    }

    // ---------------------------------------------------------------------
    // Validation failures (§9 negative branches)
    // ---------------------------------------------------------------------

    @Nested
    class ValidationFailures {

        @Test
        void should_throw_when_shield_sets_both_order_and_custom_order_simultaneously() {
            // Given — @InqShield(order = "RESILIENCE4J", customOrder = {RETRY}) is mutually exclusive
            Method method = methodOf(BothOrderAndCustomOrder.class, "perform");

            // When / Then
            assertThatThrownBy(() -> resolver.resolveOrder(method))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining(BothOrderAndCustomOrder.class.getName())
                    .hasMessageContaining("order")
                    .hasMessageContaining("customOrder")
                    .hasMessageContaining("RESILIENCE4J");
        }

        @Test
        void should_filter_extra_type_out_of_custom_order_when_custom_order_includes_an_absent_type() {
            // What is to be tested?
            //   @InqShield(customOrder = {RETRY, BULKHEAD}) on a method that carries @InqRetry only.
            //   Per the relaxed ADR-036 §3 rule, BULKHEAD is filtered out silently; the resolver
            //   returns the projected, customOrder-sequenced subset rather than throwing.
            // How will the test case be deemed successful and why?
            //   resolveOrder returns [RETRY] and does not throw. A regression that re-introduced the
            //   set-equality check would surface as an exception; a regression that broke the
            //   projection would either drop RETRY or include BULKHEAD.
            // Why is it important to test this test case?
            //   ADR-036 §3 calls out this case as the reason for the relaxed customOrder semantics:
            //   a shared constant must remain usable across methods that select different subsets
            //   of the constant's entries. Pinning the filter behaviour prevents a silent regression
            //   of the constant-reuse pattern.

            // Given
            Method method = methodOf(CustomOrderReferencesAbsentType.class, "perform");

            // When
            List<InqElementType> order = resolver.resolveOrder(method);

            // Then
            assertThat(order).containsExactly(InqElementType.RETRY);
        }

        @Test
        void should_throw_when_custom_order_is_missing_a_type_that_is_present_on_the_source() {
            // What is to be tested?
            //   The method carries both @InqBulkhead and @InqRetry but @InqShield(customOrder = {RETRY})
            //   only mentions RETRY. The set-equality check in the other direction must fail.
            // How will the test case be deemed successful and why?
            //   The resolver throws InqAnnotationConfigurationException whose message names BULKHEAD —
            //   the type that is present on the source but missing from customOrder.
            // Why is it important to test this test case?
            //   This is the symmetrical complement to the "customOrder references absent type" case.
            //   Both directions of the set-equality rule from §9 need pinning so that neither
            //   misconfiguration silently slips through.

            // Given
            Method method = methodOf(CustomOrderMissingPresentType.class, "perform");

            // When / Then
            assertThatThrownBy(() -> resolver.resolveOrder(method))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining(CustomOrderMissingPresentType.class.getName())
                    .hasMessageContaining("customOrder")
                    .hasMessageContaining("BULKHEAD");
        }

        @Test
        void should_filter_multiple_extra_types_out_of_custom_order_preserving_the_remaining_sequence() {
            // What is to be tested?
            //   @InqShield(customOrder = {TIME_LIMITER, BULKHEAD, CIRCUIT_BREAKER, RETRY}) on a
            //   method that carries @InqBulkhead and @InqRetry only. TIME_LIMITER and
            //   CIRCUIT_BREAKER are extras; the resolver must filter both out while preserving the
            //   customOrder sequence of the remaining types.
            // How will the test case be deemed successful and why?
            //   resolveOrder returns [BULKHEAD, RETRY] — both extras filtered out, BULKHEAD before
            //   RETRY exactly as written in customOrder. A bug that broke order preservation would
            //   yield [RETRY, BULKHEAD]; a bug that lost the filter would yield all four entries.
            // Why is it important to test this test case?
            //   This pins the multi-entry projection separately from the single-extra case above:
            //   it proves the filter operates per-entry across a non-trivial customOrder length and
            //   does not, for instance, accidentally use the present-set's natural iteration order.

            // Given
            Method method = methodOf(CustomOrderTwoEntriesReferencesAbsentType.class, "perform");

            // When
            List<InqElementType> order = resolver.resolveOrder(method);

            // Then
            assertThat(order).containsExactly(InqElementType.BULKHEAD, InqElementType.RETRY);
        }

        @Test
        void should_throw_when_shield_order_is_not_a_recognised_value() {
            // Given — @InqShield(order = "BOGUS"); neither INQUDIUM nor RESILIENCE4J
            Method method = methodOf(BogusOrder.class, "perform");

            // When / Then
            assertThatThrownBy(() -> resolver.resolveOrder(method))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining(BogusOrder.class.getName())
                    .hasMessageContaining("BOGUS")
                    .hasMessageContaining("INQUDIUM")
                    .hasMessageContaining("RESILIENCE4J");
        }
    }

    // ---------------------------------------------------------------------
    // Defensive checks
    // ---------------------------------------------------------------------

    @Nested
    class DefensiveChecks {

        @Test
        void should_reject_null_annotation_source_with_illegal_argument_exception() {
            // When / Then
            assertThatThrownBy(() -> resolver.resolveOrder(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("annotationSource");
        }
    }

    // ---------------------------------------------------------------------
    // Reflection helpers
    // ---------------------------------------------------------------------

    private static Method methodOf(Class<?> declaringClass, String name, Class<?>... parameterTypes) {
        try {
            return declaringClass.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("missing declared method " + declaringClass.getName() + "#"
                    + name + Arrays.toString(parameterTypes), e);
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — INQUDIUM default
    // ---------------------------------------------------------------------

    static class SingleRetry {
        @InqRetry("only-retry")
        void perform() {
            // single element annotation, no @InqShield
        }
    }

    static class AllSixWithoutShield {
        @InqCircuitBreaker("cb")
        @InqRetry("rt")
        @InqBulkhead("bh")
        @InqRateLimiter("rl")
        @InqTimeLimiter("tl")
        @InqTrafficShaper("ts")
        void perform() {
            // all six element annotations, no @InqShield → INQUDIUM default
        }
    }

    static class AllSixWithInqudiumShield {
        @InqShield(order = "INQUDIUM")
        @InqCircuitBreaker("cb")
        @InqRetry("rt")
        @InqBulkhead("bh")
        @InqRateLimiter("rl")
        @InqTimeLimiter("tl")
        @InqTrafficShaper("ts")
        void perform() {
            // explicit "INQUDIUM" must equal the no-shield default
        }
    }

    static class Bare {
        void perform() {
            // no element annotations, no @InqShield → empty list, no exception
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — RESILIENCE4J
    // ---------------------------------------------------------------------

    static class AllSixWithResilience4jShield {
        @InqShield(order = "RESILIENCE4J")
        @InqCircuitBreaker("cb")
        @InqRetry("rt")
        @InqBulkhead("bh")
        @InqRateLimiter("rl")
        @InqTimeLimiter("tl")
        @InqTrafficShaper("ts")
        void perform() {
            // all six element annotations, R4J ordering selected
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — customOrder
    // ---------------------------------------------------------------------

    static class CustomSingleRetry {
        @InqShield(customOrder = {InqElementType.RETRY})
        @InqRetry("only-retry")
        void perform() {
            // single-entry customOrder matching the only annotation
        }
    }

    static class CustomBulkheadThenRetry {
        @InqShield(customOrder = {InqElementType.BULKHEAD, InqElementType.RETRY})
        @InqBulkhead("bh")
        @InqRetry("rt")
        void perform() {
            // customOrder declares BULKHEAD before RETRY; the resolver must keep that order verbatim
        }
    }

    static class SharedConstantReuseFixture {

        // Two methods that declare the same customOrder = {BH, CB, RT} verbatim and then select
        // different subsets of those entries. Java's annotation grammar does not allow an
        // InqElementType[] static-final reference inside @InqShield(customOrder = ...), so the
        // array literal is repeated at each call site. The reuse the §3 adjustment enables is
        // therefore source-level (identical {...} per site) rather than constant-reference-level.
        @InqShield(customOrder = {
                InqElementType.BULKHEAD,
                InqElementType.CIRCUIT_BREAKER,
                InqElementType.RETRY})
        @InqBulkhead("bh")
        @InqRetry("rt")
        void two() {
            // selects two of the three customOrder entries
        }

        @InqShield(customOrder = {
                InqElementType.BULKHEAD,
                InqElementType.CIRCUIT_BREAKER,
                InqElementType.RETRY})
        @InqBulkhead("bh")
        @InqCircuitBreaker("cb")
        @InqRetry("rt")
        void three() {
            // selects all three customOrder entries
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — class-level input
    // ---------------------------------------------------------------------

    @InqShield(order = "RESILIENCE4J")
    @InqBulkhead("bh")
    @InqRetry("rt")
    static class ClassLevelWithResilience4jShield {
        // Annotations live on the class itself; the resolver receives Class<?> as input.
    }

    // ---------------------------------------------------------------------
    // Fixtures — validation failures
    // ---------------------------------------------------------------------

    static class BothOrderAndCustomOrder {
        @InqShield(order = "RESILIENCE4J", customOrder = {InqElementType.RETRY})
        @InqRetry("rt")
        void perform() {
            // mutually exclusive attributes both set → must fail
        }
    }

    static class CustomOrderReferencesAbsentType {
        @InqShield(customOrder = {InqElementType.RETRY, InqElementType.BULKHEAD})
        @InqRetry("rt")
        void perform() {
            // customOrder lists RETRY and BULKHEAD, but only @InqRetry is on the source;
            // BULKHEAD is silently filtered out per ADR-036 §3.
        }
    }

    static class CustomOrderMissingPresentType {
        @InqShield(customOrder = {InqElementType.RETRY})
        @InqBulkhead("bh")
        @InqRetry("rt")
        void perform() {
            // BULKHEAD is annotated but absent from customOrder
        }
    }

    static class CustomOrderTwoEntriesReferencesAbsentType {
        @InqShield(customOrder = {
                InqElementType.TIME_LIMITER,
                InqElementType.BULKHEAD,
                InqElementType.CIRCUIT_BREAKER,
                InqElementType.RETRY})
        @InqBulkhead("bh")
        @InqRetry("rt")
        void perform() {
            // customOrder lists four types but only BULKHEAD and RETRY are annotated on the
            // source; TIME_LIMITER and CIRCUIT_BREAKER are silently filtered out per ADR-036 §3.
        }
    }

    static class BogusOrder {
        @InqShield(order = "BOGUS")
        @InqRetry("rt")
        void perform() {
            // unknown order value
        }
    }

    // Suppress unused warning for fixture-only types referenced exclusively via reflection.
    @SuppressWarnings("unused")
    private static final List<Class<?>> KEEP_ALIVE = List.of(
            SingleRetry.class,
            AllSixWithoutShield.class,
            AllSixWithInqudiumShield.class,
            Bare.class,
            AllSixWithResilience4jShield.class,
            CustomSingleRetry.class,
            CustomBulkheadThenRetry.class,
            SharedConstantReuseFixture.class,
            ClassLevelWithResilience4jShield.class,
            BothOrderAndCustomOrder.class,
            CustomOrderReferencesAbsentType.class,
            CustomOrderMissingPresentType.class,
            CustomOrderTwoEntriesReferencesAbsentType.class,
            BogusOrder.class);
}
