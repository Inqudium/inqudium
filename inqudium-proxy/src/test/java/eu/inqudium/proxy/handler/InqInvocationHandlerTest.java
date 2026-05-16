package eu.inqudium.proxy.handler;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InqInvocationHandlerTest {

    @Test
    void should_throw_unsupported_operation_when_invoke_is_called() throws NoSuchMethodException {
        // Given
        InqInvocationHandler handler = new InqInvocationHandler();
        Method method = Object.class.getMethod("toString");

        // When / Then
        assertThatThrownBy(() -> handler.invoke(new Object(), method, new Object[0]))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_carry_a_message_pointing_at_sub_step_3_9() throws NoSuchMethodException {
        // Given
        InqInvocationHandler handler = new InqInvocationHandler();
        Method method = Object.class.getMethod("toString");

        // When / Then — the stub message must reference the sub-step that
        // fleshes the handler out so a future reader of the stack trace can
        // navigate to the right place.
        assertThatThrownBy(() -> handler.invoke(new Object(), method, new Object[0]))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("3.9");
    }

    @Test
    void should_be_an_invocation_handler() {
        // Given / When / Then
        assertThat(new InqInvocationHandler()).isInstanceOf(InvocationHandler.class);
    }
}
