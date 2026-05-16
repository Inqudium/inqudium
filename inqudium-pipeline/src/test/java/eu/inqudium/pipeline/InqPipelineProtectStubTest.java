package eu.inqudium.pipeline;

import eu.inqudium.core.element.InqElementType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InqPipelineProtectStubTest {

    @Test
    void should_throw_illegal_state_exception_when_proxy_module_is_absent() {
        // Given
        InqPipeline pipeline = InqPipeline.builder()
                .shield(new TestElement(InqElementType.CIRCUIT_BREAKER, "cb"))
                .build();
        SomeService target = name -> "hello " + name;

        // When / Then — DetectionProxy.isPresent() is false at sub-step
        // 3.3, so the absence guard must fire before any further work.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> pipeline.protect(SomeService.class, target));
    }

    @Test
    void should_include_actionable_message_pointing_at_inqudium_proxy_dependency() {
        // Given
        InqPipeline pipeline = InqPipeline.builder()
                .shield(new TestElement(InqElementType.CIRCUIT_BREAKER, "cb"))
                .build();
        SomeService target = name -> "hello " + name;

        // When / Then — loose contains-assertions: pin the words a
        // maintainer needs to see, not the exact wording, so future copy
        // edits do not break this test.
        assertThatThrownBy(() -> pipeline.protect(SomeService.class, target))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inqudium-proxy")
                .hasMessageContaining("on the classpath");
    }

    @Test
    void should_propagate_null_serviceinterface_as_a_meaningful_error() {
        // What is to be tested?
        //   The behaviour of protect(null, target) at sub-step 3.3.
        // How will the test case be deemed successful and why?
        //   The absence guard runs before any argument validation, so at
        //   3.3 the call must raise IllegalStateException regardless of
        //   the null service-interface argument. The exception type is
        //   the same one users see for "module missing" — meaningful
        //   enough to make the failure mode obvious.
        // Why is it important to test this test case?
        //   ADR-037 does not nail down the exception type for null
        //   serviceInterface; this test pins the observable behaviour
        //   at the 3.3 milestone so that 3.9 (which wires the real
        //   delegation) can intentionally evolve it rather than
        //   accidentally regress it.

        // Given
        InqPipeline pipeline = InqPipeline.builder()
                .shield(new TestElement(InqElementType.CIRCUIT_BREAKER, "cb"))
                .build();
        SomeService target = name -> "hello " + name;

        // When / Then
        assertThatThrownBy(() -> pipeline.protect(null, target))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Tiny test-local service interface for the stub.
     */
    interface SomeService {
        String greet(String name);
    }
}
