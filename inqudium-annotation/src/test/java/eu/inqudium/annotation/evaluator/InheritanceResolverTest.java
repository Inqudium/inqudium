package eu.inqudium.annotation.evaluator;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqRetry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link DefaultInheritanceResolver}. The fixtures pin down the
 * three {@link AnnotationSource} variants from ADR-036 §6 and the bridge
 * integration path from §5.
 */
class InheritanceResolverTest {

    private final InheritanceResolver resolver = new DefaultInheritanceResolver(new DefaultMethodResolver());

    // ---------------------------------------------------------------------
    // PassThrough scenarios
    // ---------------------------------------------------------------------

    @Nested
    class PassThroughCases {

        @Test
        void should_return_pass_through_when_interface_default_method_is_not_overridden_anywhere() {
            // Given — InterfaceWithDefault.greet is a default method; NoOverrideAnywhereImpl does not
            // override it and carries no class-level annotation
            Method interfaceMethod = declared(InterfaceWithDefault.class, "greet");

            // When
            AnnotationSource result = resolver.resolve(interfaceMethod, NoOverrideAnywhereImpl.class);

            // Then — §7 pass-through: dispatch goes to the interface default; no annotations apply
            assertThat(result).isEqualTo(new AnnotationSource.PassThrough());
        }

        @Test
        void should_return_pass_through_when_no_resilience_annotation_is_present_anywhere_in_the_chain() {
            // Given — UnannotatedChild extends UnannotatedParent and implements InterfaceWithAbstract.
            // Neither class declares a method-level or class-level resilience annotation.
            Method interfaceMethod = declared(InterfaceWithAbstract.class, "perform", String.class);

            // When
            AnnotationSource result = resolver.resolve(interfaceMethod, UnannotatedChild.class);

            // Then
            assertThat(result).isEqualTo(new AnnotationSource.PassThrough());
        }
    }

    // ---------------------------------------------------------------------
    // MethodLevel scenarios
    // ---------------------------------------------------------------------

    @Nested
    class MethodLevelCases {

        @Test
        void should_return_method_level_when_implementation_method_carries_a_resilience_annotation_directly() {
            // Given — DirectlyAnnotatedImpl.perform carries @InqRetry
            Method interfaceMethod = declared(InterfaceWithAbstract.class, "perform", String.class);
            Method expected = declared(DirectlyAnnotatedImpl.class, "perform", String.class);

            // When
            AnnotationSource result = resolver.resolve(interfaceMethod, DirectlyAnnotatedImpl.class);

            // Then — the walk stops at the impl itself
            assertThat(result).isEqualTo(new AnnotationSource.MethodLevel(expected));
        }

        @Test
        void should_return_method_level_pointing_at_parent_method_when_only_parent_carries_the_annotation() {
            // Given — ChildWithoutAnnotation inherits perform from ParentWithRetry; only the parent's method is annotated
            Method interfaceMethod = declared(InterfaceWithAbstract.class, "perform", String.class);
            Method expectedParentMethod = declared(ParentWithRetry.class, "perform", String.class);

            // When
            AnnotationSource result = resolver.resolve(interfaceMethod, ChildWithoutAnnotation.class);

            // Then — the walk passes the leaf (which does not declare perform) and finds the annotated parent
            assertThat(result).isEqualTo(new AnnotationSource.MethodLevel(expectedParentMethod));
        }

        @Test
        void should_return_method_level_pointing_at_deep_ancestor_when_only_the_grandparent_carries_the_annotation() {
            // What is to be tested?
            //   Three-level hierarchy: GrandparentWithRetry (annotated method), MiddleWithoutAnnotation (no
            //   declaration), LeafWithoutAnnotation (no declaration). The InheritanceResolver must walk past
            //   both the leaf and the middle class before finding the annotated method on the grandparent.
            // How will the test case be deemed successful and why?
            //   The result is MethodLevel pointing at GrandparentWithRetry#perform. This confirms the walk
            //   does not stop at the first class that simply lacks a declaration; it keeps going until it
            //   either finds an annotated method or runs out of classes.
            // Why is it important to test this test case?
            //   ADR-036 §6 mandates the Spring-strict "first method-level annotation in the walk wins" rule.
            //   A bug that stopped the walk at the first non-declaring class (or that mis-attributed the
            //   annotation to the leaf) would break it.

            // Given
            Method interfaceMethod = declared(InterfaceWithAbstract.class, "perform", String.class);
            Method expectedGrandparentMethod = declared(GrandparentWithRetry.class, "perform", String.class);

            // When
            AnnotationSource result = resolver.resolve(interfaceMethod, LeafWithoutAnnotation.class);

            // Then
            assertThat(result).isEqualTo(new AnnotationSource.MethodLevel(expectedGrandparentMethod));
        }

        @Test
        void should_return_method_level_pointing_at_typed_method_when_implementation_has_a_bridge_with_annotation_on_the_typed_method() {
            // What is to be tested?
            //   GenericConsumer<T> declares accept(T). AnnotatedStringConsumer implements GenericConsumer<String>
            //   and annotates accept(String) with @InqRetry. The compiler emits a bridge "void accept(Object)".
            //   When the inheritance resolver walks and queries MethodResolver with the interface's erased
            //   method, the bridge must be resolved to the typed method and only then is the annotation
            //   discovered.
            // How will the test case be deemed successful and why?
            //   The result is MethodLevel(typedMethod) where typedMethod has parameter type String. This
            //   verifies that bridge resolution (sub-step 2) plays correctly into the inheritance walk
            //   (sub-step 3) rather than missing the typed method.
            // Why is it important to test this test case?
            //   In practice, every generic service interface produces this constellation. If the resolver
            //   only inspected the bridge, the annotation would silently never be applied.

            // Given
            Method interfaceMethod = declared(GenericConsumer.class, "accept", Object.class);
            Method expectedTyped = declared(AnnotatedStringConsumer.class, "accept", String.class);

            // When
            AnnotationSource result = resolver.resolve(interfaceMethod, AnnotatedStringConsumer.class);

            // Then — the typed method (not the bridge) is reported, with the annotation visible on it
            assertThat(result).isInstanceOfSatisfying(AnnotationSource.MethodLevel.class, methodLevel -> {
                assertThat(methodLevel.method()).isEqualTo(expectedTyped);
                assertThat(methodLevel.method().isBridge()).isFalse();
                assertThat(methodLevel.method().getParameterTypes()).containsExactly(String.class);
                assertThat(methodLevel.method().isAnnotationPresent(InqRetry.class)).isTrue();
            });
        }

        @Test
        void should_return_method_level_when_method_level_annotation_overrides_a_class_level_annotation_of_a_different_type() {
            // What is to be tested?
            //   MethodOverridesClassImpl carries class-level @InqBulkhead and method-level @InqRetry on
            //   perform(). Per ADR-036 §6 (Spring-strict), method-level wins completely: class-level is
            //   ignored entirely, not merged.
            // How will the test case be deemed successful and why?
            //   The result is MethodLevel(impl.perform). The test asserts that the resolver returns a
            //   MethodLevel variant (not ClassLevelOnly) and that the method it points at is the impl's
            //   own perform — so the downstream evaluator will read annotations off that method only and
            //   never see the class-level @InqBulkhead.
            // Why is it important to test this test case?
            //   This is the "method overrides class" rule from ADR-036 §6, which is the central contract
            //   that protects against accidental composition of unrelated resilience elements.

            // Given
            Method interfaceMethod = declared(InterfaceWithAbstract.class, "perform", String.class);
            Method expectedMethod = declared(MethodOverridesClassImpl.class, "perform", String.class);

            // When
            AnnotationSource result = resolver.resolve(interfaceMethod, MethodOverridesClassImpl.class);

            // Then — MethodLevel wins; the class-level @InqBulkhead does not contribute
            assertThat(result).isInstanceOfSatisfying(AnnotationSource.MethodLevel.class, methodLevel -> {
                assertThat(methodLevel.method()).isEqualTo(expectedMethod);
                assertThat(methodLevel.method().isAnnotationPresent(InqRetry.class)).isTrue();
            });
            assertThat(result).isNotInstanceOf(AnnotationSource.ClassLevelOnly.class);
        }
    }

    // ---------------------------------------------------------------------
    // ClassLevelOnly scenarios
    // ---------------------------------------------------------------------

    @Nested
    class ClassLevelOnlyCases {

        @Test
        void should_return_class_level_only_when_class_level_annotation_lives_directly_on_implementation() {
            // Given — DirectClassLevelImpl carries class-level @InqBulkhead; perform has no method-level annotation
            Method interfaceMethod = declared(InterfaceWithAbstract.class, "perform", String.class);
            Method expectedSignature = declared(DirectClassLevelImpl.class, "perform", String.class);

            // When
            AnnotationSource result = resolver.resolve(interfaceMethod, DirectClassLevelImpl.class);

            // Then
            assertThat(result).isEqualTo(new AnnotationSource.ClassLevelOnly(
                    expectedSignature, DirectClassLevelImpl.class));
        }

        @Test
        void should_return_class_level_only_with_impl_as_source_when_class_level_annotation_is_inherited_from_superclass() {
            // What is to be tested?
            //   ClassLevelAnnotatedParent carries class-level @InqBulkhead (which has @Inherited). ChildOfClassLevelParent
            //   extends it and adds no annotations of its own, but declares its own perform method (no method-level
            //   annotation). Per ADR-036 §6, class-level inheritance is transparent: the child is treated as if it
            //   carried the annotation directly.
            // How will the test case be deemed successful and why?
            //   The result is ClassLevelOnly(child.perform, ChildOfClassLevelParent.class). The annotationSourceClass
            //   is the leaf, not the parent — the AnnotationSource is rooted at the implementation class so the
            //   downstream evaluator reads class-level annotations via isAnnotationPresent on the leaf.
            // Why is it important to test this test case?
            //   This pins the @Inherited contract end-to-end. A bug that bypassed @Inherited or that returned the
            //   parent as the annotation source would point downstream consumers at the wrong class for class-level
            //   reads.

            // Given
            Method interfaceMethod = declared(InterfaceWithAbstract.class, "perform", String.class);
            Method expectedSignature = declared(ChildOfClassLevelParent.class, "perform", String.class);

            // When
            AnnotationSource result = resolver.resolve(interfaceMethod, ChildOfClassLevelParent.class);

            // Then
            assertThat(result).isInstanceOfSatisfying(AnnotationSource.ClassLevelOnly.class, classLevel -> {
                assertThat(classLevel.signatureMethod()).isEqualTo(expectedSignature);
                assertThat(classLevel.annotationSourceClass()).isEqualTo(ChildOfClassLevelParent.class);
                // Sanity check: the @Inherited contract makes the annotation visible on the leaf.
                assertThat(classLevel.annotationSourceClass().isAnnotationPresent(InqBulkhead.class)).isTrue();
            });
        }
    }

    // ---------------------------------------------------------------------
    // Defensive checks
    // ---------------------------------------------------------------------

    @Nested
    class DefensiveChecks {

        @Test
        void should_reject_null_interface_method_with_illegal_argument_exception() {
            // When / Then
            assertThatThrownBy(() -> resolver.resolve(null, DirectlyAnnotatedImpl.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("interfaceMethod");
        }

        @Test
        void should_reject_null_implementation_class_with_illegal_argument_exception() {
            // Given
            Method interfaceMethod = declared(InterfaceWithAbstract.class, "perform", String.class);

            // When / Then
            assertThatThrownBy(() -> resolver.resolve(interfaceMethod, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("implementationClass");
        }
    }

    // ---------------------------------------------------------------------
    // Reflection helpers
    // ---------------------------------------------------------------------

    private static Method declared(Class<?> declaringClass, String name, Class<?>... parameterTypes) {
        try {
            return declaringClass.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("missing declared method " + declaringClass.getName() + "#"
                    + name + Arrays.toString(parameterTypes), e);
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — interfaces shared across scenarios
    // ---------------------------------------------------------------------

    interface InterfaceWithDefault {
        default String greet() {
            return "default";
        }
    }

    interface InterfaceWithAbstract {
        void perform(String input);
    }

    interface GenericConsumer<T> {
        void accept(T value);
    }

    // ---------------------------------------------------------------------
    // Fixtures — PassThrough scenarios
    // ---------------------------------------------------------------------

    static class NoOverrideAnywhereImpl implements InterfaceWithDefault {
        // Intentionally empty: greet is the unoverridden interface default.
    }

    static class UnannotatedParent implements InterfaceWithAbstract {
        @Override
        public void perform(String input) {
            // no annotations anywhere on the chain
        }
    }

    static class UnannotatedChild extends UnannotatedParent {
        // Intentionally empty: inherits perform, no annotations declared.
    }

    // ---------------------------------------------------------------------
    // Fixtures — MethodLevel: direct on impl
    // ---------------------------------------------------------------------

    static class DirectlyAnnotatedImpl implements InterfaceWithAbstract {
        @Override
        @InqRetry("direct")
        public void perform(String input) {
            // no-op
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — MethodLevel: on direct parent
    // ---------------------------------------------------------------------

    static class ParentWithRetry implements InterfaceWithAbstract {
        @Override
        @InqRetry("parent")
        public void perform(String input) {
            // annotation lives here, the child below inherits dispatch
        }
    }

    static class ChildWithoutAnnotation extends ParentWithRetry {
        // Intentionally empty: dispatch hits the parent's annotated perform.
    }

    // ---------------------------------------------------------------------
    // Fixtures — MethodLevel: on deep ancestor
    // ---------------------------------------------------------------------

    static class GrandparentWithRetry implements InterfaceWithAbstract {
        @Override
        @InqRetry("grandparent")
        public void perform(String input) {
            // annotation lives here
        }
    }

    static class MiddleWithoutAnnotation extends GrandparentWithRetry {
        // Intentionally empty: passes perform through.
    }

    static class LeafWithoutAnnotation extends MiddleWithoutAnnotation {
        // Intentionally empty: passes perform through.
    }

    // ---------------------------------------------------------------------
    // Fixtures — MethodLevel: via bridge
    // ---------------------------------------------------------------------

    static class AnnotatedStringConsumer implements GenericConsumer<String> {
        @Override
        @InqRetry("bridge")
        public void accept(String value) {
            // typed method carries the annotation; compiler also emits a bridge accept(Object)
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — MethodLevel overrides ClassLevel of a different type
    // ---------------------------------------------------------------------

    @InqBulkhead("ignored-because-method-level-wins")
    static class MethodOverridesClassImpl implements InterfaceWithAbstract {
        @Override
        @InqRetry("method-wins")
        public void perform(String input) {
            // method-level annotation must shadow the class-level one entirely
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — ClassLevelOnly: direct on impl
    // ---------------------------------------------------------------------

    @InqBulkhead("direct-class-level")
    static class DirectClassLevelImpl implements InterfaceWithAbstract {
        @Override
        public void perform(String input) {
            // no method-level annotation
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — ClassLevelOnly: via @Inherited from superclass
    // ---------------------------------------------------------------------

    @InqBulkhead("inherited-class-level")
    static class ClassLevelAnnotatedParent {
        // Parent carries the class-level annotation; @Inherited makes it visible on subclasses.
    }

    static class ChildOfClassLevelParent extends ClassLevelAnnotatedParent
            implements InterfaceWithAbstract {
        @Override
        public void perform(String input) {
            // method-level annotation deliberately absent
        }
    }

    // Suppress unused warning for fixture-only types referenced exclusively via reflection.
    @SuppressWarnings("unused")
    private static final List<Class<?>> KEEP_ALIVE = List.of(
            NoOverrideAnywhereImpl.class,
            UnannotatedChild.class,
            DirectlyAnnotatedImpl.class,
            ChildWithoutAnnotation.class,
            LeafWithoutAnnotation.class,
            AnnotatedStringConsumer.class,
            MethodOverridesClassImpl.class,
            DirectClassLevelImpl.class,
            ChildOfClassLevelParent.class);
}
