package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.handler.InqInvocationHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class MethodDispatchEntrySealedFamilyTest {

    @Test
    void should_be_a_sealed_interface() {
        // What is to be tested?
        //   That MethodDispatchEntry remains a sealed type.
        // How will the test case be deemed successful and why?
        //   Class#isSealed returns true. A non-sealed family would silently
        //   let external code register surprise dispatch strategies.
        // Why is it important to test this test case?
        //   The sealed contract is foundational to the proxy's dispatch
        //   model; subsequent sub-steps add permits one by one, and an
        //   accidental "unsealing" must fail loud and immediately.

        // Given / When / Then
        assertThat(MethodDispatchEntry.class.isSealed()).isTrue();
    }

    @Test
    void should_permit_all_five_dispatch_entry_records() {
        // Given / When
        Class<?>[] permits = MethodDispatchEntry.class.getPermittedSubclasses();

        // Then — sub-step 3.11 grows the family to five permits with
        // the addition of AsyncCacheEntry.
        assertThat(permits).containsExactlyInAnyOrder(
                PassThroughEntry.class,
                DefaultMethodEntry.class,
                SyncCacheEntry.class,
                ObjectMethodEntry.class,
                AsyncCacheEntry.class);
    }

    @Test
    void should_expose_five_static_factories_on_the_sealed_interface() {
        // What is to be tested?
        //   The sealed family ships five cross-package static factories
        //   (passThrough, defaultMethod, syncCache, objectMethod,
        //   asyncCache). The factory count and names are part of the
        //   construction contract — ProxyBuilder and
        //   MethodDispatchEntryFactory rely on these named entry points.
        // How will the test case be deemed successful and why?
        //   The interface declares exactly five static factory methods,
        //   and each named factory is present.
        // Why is it important to test this test case?
        //   A regression that dropped or renamed a factory would compile
        //   only where the factory is actually used; this test surfaces
        //   the regression at the contract level.

        // Given / When
        java.util.Set<String> staticFactoryNames = java.util.Arrays.stream(
                        MethodDispatchEntry.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet());

        // Then
        assertThat(staticFactoryNames).containsExactlyInAnyOrder(
                "passThrough", "defaultMethod", "syncCache", "objectMethod", "asyncCache");
    }

    @Test
    void should_expose_one_abstract_dispatch_method_and_one_default_layer_descriptions_method() throws NoSuchMethodException {
        // What is to be tested?
        //   The interface declares exactly two instance (non-static)
        //   methods: the abstract `dispatch` (the core contract) and a
        //   default `layerDescriptions` accessor (sub-step 3.12, used
        //   by ProxyStackAdapter for ADR-039 introspection). Static
        //   factories live on the interface but are filtered out here.
        // How will the test case be deemed successful and why?
        //   The instance-method set has size two, `dispatch` is
        //   abstract, and `layerDescriptions` is default. A regression
        //   that turned `dispatch` into a default or that dropped
        //   `layerDescriptions` would fail at the contract level.
        // Why is it important to test this test case?
        //   The sealed-family dispatch contract is foundational; an
        //   accidental change to the method shape would silently
        //   change the dispatch model and the introspection contract.

        // Given / When
        Method[] instanceMethods = java.util.Arrays.stream(
                        MethodDispatchEntry.class.getDeclaredMethods())
                .filter(m -> !java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                .toArray(Method[]::new);

        // Then
        assertThat(instanceMethods).hasSize(2);

        Method dispatch = MethodDispatchEntry.class.getDeclaredMethod(
                "dispatch", Object.class, InqInvocationHandler.class, Object[].class);
        assertThat(dispatch.getReturnType()).isEqualTo(Object.class);
        assertThat(dispatch.isDefault()).isFalse();

        Method layerDescriptions = MethodDispatchEntry.class
                .getDeclaredMethod("layerDescriptions");
        assertThat(layerDescriptions.getReturnType()).isEqualTo(java.util.List.class);
        assertThat(layerDescriptions.isDefault()).isTrue();
    }
}
