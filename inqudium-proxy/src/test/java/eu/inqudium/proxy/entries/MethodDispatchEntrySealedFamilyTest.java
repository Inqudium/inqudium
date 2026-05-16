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
        // Given / When
        Method[] declared = MethodDispatchEntry.class.getDeclaredMethods();

        // Then — exactly one abstract method named `dispatch` with the
        // signature spec'd in ARCHITECTURE.md §7.5.
        assertThat(declared).hasSize(1);

        Method dispatch = MethodDispatchEntry.class.getDeclaredMethod(
                "dispatch", Object.class, InqInvocationHandler.class, Object[].class);
        assertThat(dispatch.getReturnType()).isEqualTo(Object.class);
        assertThat(dispatch.isDefault()).isFalse();
    }
}
