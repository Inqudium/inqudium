package eu.inqudium.proxy.dispatch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DetectionAsyncTest {

    @Test
    void should_return_true_when_inqudium_imperative_is_on_the_classpath() {
        // What is to be tested?
        //   In this test module, inqudium-imperative is a compile-scope
        //   dependency of inqudium-proxy (declared optional in the
        //   proxy pom, but always present here). DetectionAsync probes
        //   for InqAsyncDecorator via Class.forName; the lookup must
        //   succeed.
        // How will the test case be deemed successful and why?
        //   isPresent() returns true. The negative case (imperative
        //   absent) is covered indirectly by the 3.13 module-loading
        //   discipline test under a separate Maven profile.
        // Why is it important to test this test case?
        //   This is the foundational check that gates the async branch
        //   of MethodDispatchEntryFactory.

        // Given / When / Then
        assertThat(DetectionAsync.isPresent()).isTrue();
    }

    @Test
    void should_be_idempotent_across_repeated_calls() {
        // What is to be tested?
        //   The check is cached at class load time; repeated calls
        //   return the same value without re-running Class.forName.
        // How will the test case be deemed successful and why?
        //   Three calls in a row all return the same boolean.
        // Why is it important to test this test case?
        //   Documents the constant-result contract — callers can call
        //   isPresent() freely without performance concerns.

        // Given / When
        boolean first = DetectionAsync.isPresent();
        boolean second = DetectionAsync.isPresent();
        boolean third = DetectionAsync.isPresent();

        // Then
        assertThat(first).isEqualTo(second).isEqualTo(third);
    }

    @Test
    void should_be_a_utility_class_with_no_instance_fields() {
        // What is to be tested?
        //   DetectionAsync exposes only a private constructor and a
        //   single static accessor; it carries no instance state.
        //   Pinning this prevents drift toward an instantiable class
        //   with mutable per-instance behaviour.
        // How will the test case be deemed successful and why?
        //   The class declares zero non-static fields. The static
        //   PRESENT field is permitted as it backs the cached check.
        // Why is it important to test this test case?
        //   The class-loading discipline depends on DetectionAsync's
        //   shape — a non-static field referencing an async type
        //   would be loaded eagerly during DetectionAsync's class
        //   initialisation, defeating the whole probe.

        // Given / When
        long instanceFields = java.util.Arrays.stream(
                        DetectionAsync.class.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .count();

        // Then
        assertThat(instanceFields).isZero();
        assertThat(DetectionAsync.class).isFinal();
    }
}
