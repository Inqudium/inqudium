package eu.inqudium.annotation.evaluator;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link BridgeMethodResolver}. Each test fixture is a small
 * generic-interface-plus-implementation constellation that causes the Java
 * compiler to emit the relevant bridge method(s); the test verifies the
 * resolver's behaviour against the compiler-emitted bridge directly.
 */
class BridgeMethodResolverTest {

    @Nested
    class SingleMatch {

        @Test
        void should_resolve_bridge_for_simple_generic_interface_with_one_type_parameter() {
            // Given — StringService implements Service<String>, producing one bridge "Object process(Object)"
            Method bridge = bridgeOf(StringService.class, "process");
            Method expectedTyped = declaredMethodWithParameters(StringService.class, "process", String.class);

            // When — the resolver is asked for the typed counterpart
            Method resolved = BridgeMethodResolver.resolveBridge(bridge);

            // Then — the typed String-taking method is returned
            assertThat(resolved).isEqualTo(expectedTyped);
            assertThat(resolved.isBridge()).isFalse();
            assertThat(resolved.getReturnType()).isEqualTo(String.class);
        }

        @Test
        void should_resolve_bridge_for_covariant_return_type_override() {
            // Given — CovariantChild overrides CovariantParent#compute() with an Integer return; the bridge
            // returns the supertype Number while the typed method returns Integer.
            Method bridge = bridgeOf(CovariantChild.class, "compute");
            Method expectedTyped = declaredMethodWithParameters(CovariantChild.class, "compute");

            // When — the resolver is asked for the typed counterpart
            Method resolved = BridgeMethodResolver.resolveBridge(bridge);

            // Then — rule 4 (return-type assignability) selects the Integer-returning method
            assertThat(resolved).isEqualTo(expectedTyped);
            assertThat(resolved.getReturnType()).isEqualTo(Integer.class);
            assertThat(bridge.getReturnType()).isEqualTo(Number.class);
        }

        @Test
        void should_resolve_each_bridge_independently_when_interface_has_multiple_type_parameters() {
            // Given — IntStringPair implements Pair<Integer, String>; the compiler emits one bridge for each
            // typed method.
            Method firstBridge = bridgeOf(IntStringPair.class, "first");
            Method secondBridge = bridgeOf(IntStringPair.class, "second");
            Method expectedFirst = declaredMethodWithParameters(IntStringPair.class, "first", String.class);
            Method expectedSecond = declaredMethodWithParameters(IntStringPair.class, "second", Integer.class);

            // When — each bridge is resolved separately
            Method resolvedFirst = BridgeMethodResolver.resolveBridge(firstBridge);
            Method resolvedSecond = BridgeMethodResolver.resolveBridge(secondBridge);

            // Then — both resolutions return their respective typed methods, independently
            assertThat(resolvedFirst).isEqualTo(expectedFirst);
            assertThat(resolvedSecond).isEqualTo(expectedSecond);
            assertThat(resolvedFirst).isNotEqualTo(resolvedSecond);
        }

        @Test
        void should_resolve_chained_bridges_within_the_same_class_to_the_same_typed_method() {
            // What is to be tested?
            //   IntProducer extends TypedAbstractProducer<Integer> which implements Producer<U extends Number>.
            //   The compiler emits two bridges on IntProducer: "Number produce()" (for the bounded supertype U)
            //   and "Object produce()" (for the unbounded interface T). Both must resolve to the same typed
            //   method "Integer produce()".
            // How will the test case be deemed successful and why?
            //   The resolver returns the identical Integer-returning method for both bridges, with no exception,
            //   confirming the algorithm handles same-class chained bridges without ambiguity.
            // Why is it important to test this test case?
            //   ADR-036 §5 explicitly calls out chained bridges as a case the algorithm must handle. If the
            //   resolver only inspected one bridge or short-circuited, the second bridge would slip past
            //   undetected, producing different annotation sources at runtime depending on which bridge a caller
            //   started from.

            // Given — both bridges sit on IntProducer; locate them by return type
            List<Method> bridges = bridgesOf(IntProducer.class, "produce");
            Method numberBridge = bridges.stream()
                    .filter(m -> m.getReturnType().equals(Number.class))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a Number-returning bridge on IntProducer"));
            Method objectBridge = bridges.stream()
                    .filter(m -> m.getReturnType().equals(Object.class))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected an Object-returning bridge on IntProducer"));
            Method expectedTyped = declaredMethodWithParameters(IntProducer.class, "produce");

            // When — each bridge is resolved
            Method resolvedFromNumber = BridgeMethodResolver.resolveBridge(numberBridge);
            Method resolvedFromObject = BridgeMethodResolver.resolveBridge(objectBridge);

            // Then — both bridges resolve to the same Integer-returning typed method
            assertThat(resolvedFromNumber).isEqualTo(expectedTyped);
            assertThat(resolvedFromObject).isEqualTo(expectedTyped);
            assertThat(resolvedFromNumber).isEqualTo(resolvedFromObject);
            assertThat(expectedTyped.getReturnType()).isEqualTo(Integer.class);
        }
    }

    @Nested
    class Ambiguity {

        @Test
        void should_throw_when_more_than_one_typed_candidate_matches_the_bridge() {
            // What is to be tested?
            //   AmbiguousService implements Acceptor<Number> with m(Number) and additionally declares an
            //   overload m(Integer). The bridge "void m(Object)" matches both typed methods because Object is
            //   assignable from both Number and Integer.
            // How will the test case be deemed successful and why?
            //   The resolver fails fast with InqAnnotationConfigurationException and the message names the
            //   bridge plus both candidates, so the author can locate and restructure the offending code.
            // Why is it important to test this test case?
            //   ADR-036 §5 mandates a hard fail on ambiguity rather than a heuristic guess. A silent fallback
            //   could route annotations to the wrong typed method at runtime.

            // Given — the bridge m(Object) and the names of both candidate methods on AmbiguousService
            Method bridge = bridgeOf(AmbiguousService.class, "m");

            // When / Then — the resolver fails with a descriptive ambiguity error
            assertThatThrownBy(() -> BridgeMethodResolver.resolveBridge(bridge))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining(AmbiguousService.class.getName())
                    .hasMessageContaining("ambiguous")
                    .hasMessageContaining(Number.class.getTypeName())
                    .hasMessageContaining(Integer.class.getTypeName());
        }
    }

    @Nested
    class NoMatch {

        @Test
        void should_throw_when_the_typed_counterpart_is_not_declared_on_the_bridges_class() {
            // What is to be tested?
            //   CovariantInheritedChild extends CovariantInheritedParent (which declares "Integer get()") and
            //   implements NumberSource (which requires "Number get()"). The compiler emits a bridge
            //   "Number get()" on CovariantInheritedChild that delegates to the inherited typed method on the
            //   parent. CovariantInheritedChild#getDeclaredMethods() therefore does not contain the typed
            //   counterpart.
            // How will the test case be deemed successful and why?
            //   The resolver, scanning only the declaring class, finds no non-bridge match and throws
            //   InqAnnotationConfigurationException. The message identifies the bridge.
            // Why is it important to test this test case?
            //   ADR-036 §5 mandates that the resolver scans only the declaring class and explicitly does not
            //   silently fall back to the bridge when no typed counterpart is found locally. Cross-class
            //   resolution is the inheritance resolver's job (sub-step 3); this resolver must fail rather than
            //   pretend to succeed.

            // Given — the bridge on CovariantInheritedChild
            Method bridge = bridgeOf(CovariantInheritedChild.class, "get");

            // When / Then — the resolver fails with a no-match error identifying the bridge
            assertThatThrownBy(() -> BridgeMethodResolver.resolveBridge(bridge))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining(CovariantInheritedChild.class.getName())
                    .hasMessageContaining("get")
                    .hasMessageContaining("no typed counterpart");
        }
    }

    @Nested
    class DefensiveChecks {

        @Test
        void should_reject_null_bridge_with_illegal_argument_exception() {
            // Given — a null bridge reference
            // When / Then — the resolver fails fast with IllegalArgumentException
            assertThatThrownBy(() -> BridgeMethodResolver.resolveBridge(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
        }

        @Test
        void should_reject_non_bridge_method_with_illegal_argument_exception() {
            // Given — a regular, non-bridge method
            Method nonBridge = declaredMethodWithParameters(StringService.class, "process", String.class);
            assertThat(nonBridge.isBridge()).isFalse();

            // When / Then — the resolver rejects it as a programmer error
            assertThatThrownBy(() -> BridgeMethodResolver.resolveBridge(nonBridge))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a bridge");
        }
    }

    // ---------------------------------------------------------------------
    // Reflection helpers
    // ---------------------------------------------------------------------

    private static Method bridgeOf(Class<?> declaringClass, String methodName) {
        List<Method> bridges = bridgesOf(declaringClass, methodName);
        if (bridges.size() != 1) {
            throw new AssertionError("expected exactly one bridge for " + declaringClass.getName()
                    + "#" + methodName + " but found " + bridges.size() + ": " + bridges);
        }
        return bridges.get(0);
    }

    private static List<Method> bridgesOf(Class<?> declaringClass, String methodName) {
        return Arrays.stream(declaringClass.getDeclaredMethods())
                .filter(Method::isBridge)
                .filter(m -> m.getName().equals(methodName))
                .toList();
    }

    private static Method declaredMethodWithParameters(Class<?> declaringClass, String methodName,
                                                       Class<?>... parameterTypes) {
        try {
            Method method = declaringClass.getDeclaredMethod(methodName, parameterTypes);
            if (method.isBridge()) {
                throw new AssertionError("expected non-bridge method but " + method + " is a bridge");
            }
            return method;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("missing typed method " + declaringClass.getName() + "#" + methodName, e);
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — case 1: simple generic interface, one type parameter
    // ---------------------------------------------------------------------

    interface Service<T> {
        T process(T input);
    }

    static class StringService implements Service<String> {
        @Override
        public String process(String input) {
            return input;
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — case 2: covariant return type
    // ---------------------------------------------------------------------

    static class CovariantParent {
        public Number compute() {
            return 0;
        }
    }

    static class CovariantChild extends CovariantParent {
        @Override
        public Integer compute() {
            return 1;
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — case 3: multiple type parameters
    // ---------------------------------------------------------------------

    interface Pair<A, B> {
        A first(B from);

        B second(A from);
    }

    static class IntStringPair implements Pair<Integer, String> {
        @Override
        public Integer first(String from) {
            return from == null ? 0 : from.length();
        }

        @Override
        public String second(Integer from) {
            return from == null ? "" : from.toString();
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — case 4: chained bridges within the same class
    // ---------------------------------------------------------------------

    interface Producer<T> {
        T produce();
    }

    abstract static class TypedAbstractProducer<U extends Number> implements Producer<U> {
        @Override
        public abstract U produce();
    }

    static class IntProducer extends TypedAbstractProducer<Integer> {
        @Override
        public Integer produce() {
            return 42;
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — case 5: ambiguity
    // ---------------------------------------------------------------------

    interface Acceptor<T> {
        void m(T value);
    }

    static class AmbiguousService implements Acceptor<Number> {
        @Override
        public void m(Number value) {
            // typed implementation
        }

        @SuppressWarnings("unused")
        public void m(Integer value) {
            // overload whose parameter type is also a subtype of the bridge's erased Object
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures — case 6: no match
    // ---------------------------------------------------------------------

    interface NumberSource {
        Number get();
    }

    static class CovariantInheritedParent {
        public Integer get() {
            return 0;
        }
    }

    static class CovariantInheritedChild extends CovariantInheritedParent implements NumberSource {
        // Intentionally empty: the typed Integer get() is inherited from the parent, while the compiler
        // emits a bridge "Number get()" on this class to satisfy the NumberSource interface. The resolver
        // therefore sees the bridge but no co-located typed counterpart.
    }
}
