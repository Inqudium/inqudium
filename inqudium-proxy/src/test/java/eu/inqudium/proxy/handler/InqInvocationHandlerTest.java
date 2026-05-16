package eu.inqudium.proxy.handler;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InqInvocationHandlerTest {

    private static LongSupplier countingSource() {
        AtomicLong counter = new AtomicLong();
        return counter::incrementAndGet;
    }

    @Nested
    class StubBehaviour {

        @Test
        void should_throw_unsupported_operation_when_invoke_is_called() throws NoSuchMethodException {
            // Given
            InqInvocationHandler handler = new InqInvocationHandler(1L, countingSource());
            Method method = Object.class.getMethod("toString");

            // When / Then
            assertThatThrownBy(() -> handler.invoke(new Object(), method, new Object[0]))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void should_carry_a_message_pointing_at_sub_step_3_9() throws NoSuchMethodException {
            // Given
            InqInvocationHandler handler = new InqInvocationHandler(1L, countingSource());
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
            assertThat(new InqInvocationHandler(1L, countingSource()))
                    .isInstanceOf(InvocationHandler.class);
        }
    }

    @Nested
    class State {

        @Test
        void should_store_stack_id_passed_to_constructor() {
            // Given / When
            InqInvocationHandler handler = new InqInvocationHandler(42L, countingSource());

            // Then
            assertThat(handler.stackId()).isEqualTo(42L);
        }

        @Test
        void should_pull_call_ids_from_the_source() {
            // Given — a source we control, returning a fixed sequence
            AtomicLong counter = new AtomicLong(99);
            InqInvocationHandler handler = new InqInvocationHandler(1L, counter::incrementAndGet);

            // When
            long first = handler.nextCallId();

            // Then — the handler reads through to the supplier
            assertThat(first).isEqualTo(100L);
        }

        @Test
        void should_pull_a_fresh_value_on_each_call_to_next_call_id() {
            // What is to be tested?
            //   That nextCallId() does not cache or memoise — every call pulls
            //   a fresh value from the supplier.
            // How will the test case be deemed successful and why?
            //   Three successive calls return three distinct, monotonically-
            //   increasing values from the AtomicLong-backed supplier. Caching
            //   would return the same value or break the sequence.
            // Why is it important to test this test case?
            //   The whole correlation-ID scheme relies on each method
            //   invocation getting its own call-ID; a caching handler would
            //   make every invocation share an ID, destroying observability
            //   and breaking ADR-034.

            // Given
            InqInvocationHandler handler = new InqInvocationHandler(1L, countingSource());

            // When
            long first = handler.nextCallId();
            long second = handler.nextCallId();
            long third = handler.nextCallId();

            // Then
            assertThat(first).isEqualTo(1L);
            assertThat(second).isEqualTo(2L);
            assertThat(third).isEqualTo(3L);
        }

        @Test
        void should_reject_null_call_id_source_with_npe() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new InqInvocationHandler(1L, null))
                    .withMessage("callIdSource");
        }
    }
}
