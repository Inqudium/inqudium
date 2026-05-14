package eu.inqudium.annotation.evaluator;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link DefaultMethodResolver}. The fixtures are small static
 * nested constellations that pin down each branch of the algorithm from
 * ADR-036 §5 steps 1–4.
 */
class MethodResolverTest {

    private final MethodResolver resolver = new DefaultMethodResolver();

    // ---------------------------------------------------------------------
    // Default-method handling — pass-through gate and override variants
    // ---------------------------------------------------------------------

    @Nested
    class DefaultMethodHandling {

        @Test
        void should_return_empty_when_default_method_is_not_overridden_anywhere_in_the_target_hierarchy() {
            // Given — InterfaceWithDefault declares default void greet(); NoOverrideImpl does not declare greet
            // anywhere in its hierarchy (it has no superclass that overrides either).
            Method interfaceMethod = declared(InterfaceWithDefault.class, "greet");

            // When — the resolver is queried with the impl class
            Optional<Method> resolved = resolver.resolveAnnotationSourceMethod(
                    interfaceMethod, NoOverrideImpl.class);

            // Then — the pass-through gate (step 1) fires: nothing overrides → empty
            assertThat(resolved).isEmpty();
        }

        @Test
        void should_return_the_override_when_target_class_directly_overrides_a_default_method() {
            // Given — DirectOverrideImpl declares its own greet(), shadowing the interface default
            Method interfaceMethod = declared(InterfaceWithDefault.class, "greet");
            Method expectedOverride = declared(DirectOverrideImpl.class, "greet");

            // When
            Optional<Method> resolved = resolver.resolveAnnotationSourceMethod(
                    interfaceMethod, DirectOverrideImpl.class);

            // Then — step 1 sees the override on the target itself, step 2 returns the declared method
            assertThat(resolved).contains(expectedOverride);
        }

        @Test
        void should_return_empty_when_target_class_does_not_declare_the_method_but_an_ancestor_overrides() {
            // What is to be tested?
            //   MethodResolver is per-class. Even when the default method is overridden by an ancestor
            //   (so step 1's pass-through gate does NOT fire), step 2–3 must still return empty because
            //   the target class itself does not declare the method.
            // How will the test case be deemed successful and why?
            //   The resolver returns Optional.empty() when queried with the leaf class. This confirms the
            //   pass-through gate did not falsely fire (which would also return empty) and that the
            //   per-class contract is respected. Successful behaviour is observed indirectly via the
            //   companion test below which queries the ancestor and gets the override back.
            // Why is it important to test this test case?
            //   ADR-036 §5 step 3 explicitly delegates hierarchy walking to InheritanceResolver. If the
            //   MethodResolver silently returned an ancestor's method, the inheritance walk would
            //   double-visit annotations and break the Spring-strict "first hit wins" rule.

            // Given — Grandparent overrides the default; Parent and Leaf do not declare greet
            Method interfaceMethod = declared(InterfaceWithDefault.class, "greet");

            // When — querying the leaf
            Optional<Method> resolvedLeaf = resolver.resolveAnnotationSourceMethod(
                    interfaceMethod, GrandchildOfOverridingAncestor.class);

            // Then — empty, because the leaf does not declare greet itself (per-class contract)
            assertThat(resolvedLeaf).isEmpty();
        }

        @Test
        void should_return_the_override_when_queried_with_the_ancestor_class_that_overrides_the_default() {
            // Given — the same constellation as above, but queried at the ancestor that declares the override
            Method interfaceMethod = declared(InterfaceWithDefault.class, "greet");
            Method expectedOverride = declared(OverridingAncestor.class, "greet");

            // When
            Optional<Method> resolved = resolver.resolveAnnotationSourceMethod(
                    interfaceMethod, OverridingAncestor.class);

            // Then — step 1 finds the override on the target itself, step 2 returns the declared method
            assertThat(resolved).contains(expectedOverride);
        }
    }

    // ---------------------------------------------------------------------
    // Non-default interface methods — direct lookup
    // ---------------------------------------------------------------------

    @Nested
    class DirectLookup {

        @Test
        void should_return_the_declared_method_when_target_class_declares_a_matching_non_default_method() {
            // Given — InterfaceWithAbstract declares abstract void perform(String); DirectAbstractImpl declares perform
            Method interfaceMethod = declared(InterfaceWithAbstract.class, "perform", String.class);
            Method expected = declared(DirectAbstractImpl.class, "perform", String.class);

            // When
            Optional<Method> resolved = resolver.resolveAnnotationSourceMethod(
                    interfaceMethod, DirectAbstractImpl.class);

            // Then
            assertThat(resolved).contains(expected);
        }

        @Test
        void should_return_empty_when_target_class_does_not_declare_a_matching_non_default_method() {
            // Given — the method lives on the parent; the leaf does not declare it
            Method interfaceMethod = declared(InterfaceWithAbstract.class, "perform", String.class);

            // When — querying the leaf
            Optional<Method> resolved = resolver.resolveAnnotationSourceMethod(
                    interfaceMethod, ChildWithoutOwnPerform.class);

            // Then — empty, even though the parent declares perform; per-class contract
            assertThat(resolved).isEmpty();
        }
    }

    // ---------------------------------------------------------------------
    // Bridge delegation
    // ---------------------------------------------------------------------

    @Nested
    class BridgeDelegation {

        @Test
        void should_resolve_bridge_to_typed_method_when_target_class_declares_a_bridge_for_a_generic_interface() {
            // What is to be tested?
            //   GenericConsumer<T> declares accept(T). StringConsumer implements GenericConsumer<String>, which
            //   causes the compiler to emit a bridge "void accept(Object)" plus the typed
            //   "void accept(String)". When MethodResolver is queried with the interface's erased method, it
            //   sees the bridge first and must delegate to BridgeMethodResolver to obtain the typed method.
            // How will the test case be deemed successful and why?
            //   The resolved method has parameter type String (not Object) and isBridge() == false. This
            //   confirms the §5 step 4 delegation works end-to-end.
            // Why is it important to test this test case?
            //   Annotations attach to the typed method, not the bridge. Returning the bridge would route
            //   annotation reads to the wrong reflective element and silently misclassify the method as
            //   unannotated.

            // Given — the interface method has the erased signature (Object parameter)
            Method interfaceMethod = declared(GenericConsumer.class, "accept", Object.class);
            Method expectedTyped = declared(StringConsumer.class, "accept", String.class);

            // When
            Optional<Method> resolved = resolver.resolveAnnotationSourceMethod(
                    interfaceMethod, StringConsumer.class);

            // Then
            assertThat(resolved).contains(expectedTyped);
            assertThat(resolved.get().isBridge()).isFalse();
            assertThat(resolved.get().getParameterTypes()).containsExactly(String.class);
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
            assertThatThrownBy(() -> resolver.resolveAnnotationSourceMethod(null, DirectOverrideImpl.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("interfaceMethod");
        }

        @Test
        void should_reject_null_target_class_with_illegal_argument_exception() {
            // Given — a real interface method
            Method interfaceMethod = declared(InterfaceWithDefault.class, "greet");

            // When / Then
            assertThatThrownBy(() -> resolver.resolveAnnotationSourceMethod(interfaceMethod, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("targetClass");
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
    // Fixtures — default-method handling
    // ---------------------------------------------------------------------

    interface InterfaceWithDefault {
        default String greet() {
            return "default";
        }
    }

    static class NoOverrideImpl implements InterfaceWithDefault {
        // Intentionally empty: greet is inherited from the interface default; nothing in the
        // class hierarchy declares it.
    }

    static class DirectOverrideImpl implements InterfaceWithDefault {
        @Override
        public String greet() {
            return "override";
        }
    }

    static class OverridingAncestor implements InterfaceWithDefault {
        @Override
        public String greet() {
            return "ancestor-override";
        }
    }

    static class MiddleWithoutOverride extends OverridingAncestor {
        // Intentionally empty: inherits the override from OverridingAncestor.
    }

    static class GrandchildOfOverridingAncestor extends MiddleWithoutOverride {
        // Intentionally empty: inherits the override from OverridingAncestor via MiddleWithoutOverride.
    }

    // ---------------------------------------------------------------------
    // Fixtures — non-default lookup
    // ---------------------------------------------------------------------

    interface InterfaceWithAbstract {
        void perform(String input);
    }

    static class DirectAbstractImpl implements InterfaceWithAbstract {
        @Override
        public void perform(String input) {
            // no-op
        }
    }

    static class ParentWithPerform implements InterfaceWithAbstract {
        @Override
        public void perform(String input) {
            // no-op
        }
    }

    static class ChildWithoutOwnPerform extends ParentWithPerform {
        // Intentionally empty: perform is inherited from ParentWithPerform; this class does not declare it.
    }

    // ---------------------------------------------------------------------
    // Fixtures — bridge delegation
    // ---------------------------------------------------------------------

    interface GenericConsumer<T> {
        void accept(T value);
    }

    static class StringConsumer implements GenericConsumer<String> {
        @Override
        public void accept(String value) {
            // no-op
        }
    }

    // Suppress unused warning for fixture-only types referenced only via reflection.
    @SuppressWarnings("unused")
    private static final List<Class<?>> KEEP_ALIVE = List.of(
            NoOverrideImpl.class,
            DirectOverrideImpl.class,
            GrandchildOfOverridingAncestor.class,
            ChildWithoutOwnPerform.class,
            DirectAbstractImpl.class,
            StringConsumer.class);
}
