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
    void should_permit_passthrough_default_method_and_sync_cache_entries() {
        // Given / When
        Class<?>[] permits = MethodDispatchEntry.class.getPermittedSubclasses();

        // Then — sub-step 3.7 grows the family to three permits.
        // Sub-steps 3.10 and 3.11 grow the list further; each of those
        // sub-steps updates this test.
        assertThat(permits).containsExactlyInAnyOrder(
                PassThroughEntry.class, DefaultMethodEntry.class, SyncCacheEntry.class);
    }

    @Test
    void should_remain_an_internal_interface_with_no_default_methods() throws NoSuchMethodException {
        // What is to be tested?
        //   The interface defines exactly one abstract instance method,
        //   `dispatch`. Static factory methods may exist (sub-step 3.8
        //   added passThrough / defaultMethod / syncCache as
        //   cross-package entry points for construction code) but no
        //   default method may sneak in: default methods would invite
        //   external implementations to inherit behaviour rather than
        //   compose with the sealed family.
        // How will the test case be deemed successful and why?
        //   Among the declared instance (non-static) methods there is
        //   exactly one, `dispatch`, and it is not default.
        // Why is it important to test this test case?
        //   The sealed-family dispatch contract is foundational; an
        //   accidental default method would change the dispatch model
        //   silently.

        // Given / When
        Method[] instanceMethods = java.util.Arrays.stream(
                        MethodDispatchEntry.class.getDeclaredMethods())
                .filter(m -> !java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                .toArray(Method[]::new);

        // Then
        assertThat(instanceMethods).hasSize(1);

        Method dispatch = MethodDispatchEntry.class.getDeclaredMethod(
                "dispatch", Object.class, InqInvocationHandler.class, Object[].class);
        assertThat(dispatch.getReturnType()).isEqualTo(Object.class);
        assertThat(dispatch.isDefault()).isFalse();
    }
}
