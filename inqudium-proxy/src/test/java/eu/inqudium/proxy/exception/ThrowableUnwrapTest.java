package eu.inqudium.proxy.exception;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ThrowableUnwrapTest {

    @Nested
    class NonWrappers {

        @Test
        void should_return_a_runtime_exception_unchanged() {
            // Given
            RuntimeException original = new IllegalStateException("boom");

            // When
            Throwable result = ThrowableUnwrap.unwrap(original);

            // Then
            assertThat(result).isSameAs(original);
        }

        @Test
        void should_return_a_checked_exception_unchanged() {
            // Given
            Throwable original = new IOException("boom");

            // When
            Throwable result = ThrowableUnwrap.unwrap(original);

            // Then
            assertThat(result).isSameAs(original);
        }

        @Test
        void should_return_an_error_unchanged() {
            // Given
            Error original = new AssertionError("boom");

            // When
            Throwable result = ThrowableUnwrap.unwrap(original);

            // Then
            assertThat(result).isSameAs(original);
        }
    }

    @Nested
    class InvocationTargetExceptionUnwrapping {

        @Test
        void should_unwrap_a_single_ite_to_its_cause() {
            // Given
            IOException cause = new IOException("boom");
            InvocationTargetException wrapper = new InvocationTargetException(cause);

            // When
            Throwable result = ThrowableUnwrap.unwrap(wrapper);

            // Then
            assertThat(result).isSameAs(cause);
        }

        @Test
        void should_unwrap_nested_ite_chains_recursively() {
            // Given
            IOException root = new IOException("root");
            InvocationTargetException inner = new InvocationTargetException(root);
            InvocationTargetException outer = new InvocationTargetException(inner);

            // When
            Throwable result = ThrowableUnwrap.unwrap(outer);

            // Then
            assertThat(result).isSameAs(root);
        }

        @Test
        void should_return_the_ite_itself_when_its_cause_is_null() {
            // What is to be tested?
            //   That an InvocationTargetException with a null cause is returned as-is,
            //   rather than the unwrap loop dereferencing the null cause and crashing.
            // How will the test case be deemed successful and why?
            //   The returned throwable must be the wrapper instance itself. The
            //   loop must stop gracefully when there is nothing further to unwrap.
            // Why is it important to test this test case?
            //   InvocationTargetException permits a null cause via the
            //   getTargetException-shaped constructor; defensive code must not
            //   NPE on this rare-but-legal shape.

            // Given
            InvocationTargetException wrapper = new InvocationTargetException(null);

            // When
            Throwable result = ThrowableUnwrap.unwrap(wrapper);

            // Then
            assertThat(result).isSameAs(wrapper);
        }
    }

    @Nested
    class UndeclaredThrowableExceptionUnwrapping {

        @Test
        void should_unwrap_a_single_ute_to_its_cause() {
            // Given
            IOException cause = new IOException("boom");
            UndeclaredThrowableException wrapper = new UndeclaredThrowableException(cause);

            // When
            Throwable result = ThrowableUnwrap.unwrap(wrapper);

            // Then
            assertThat(result).isSameAs(cause);
        }

        @Test
        void should_unwrap_nested_ute_chains_recursively() {
            // Given
            IOException root = new IOException("root");
            UndeclaredThrowableException inner = new UndeclaredThrowableException(root);
            UndeclaredThrowableException outer = new UndeclaredThrowableException(inner);

            // When
            Throwable result = ThrowableUnwrap.unwrap(outer);

            // Then
            assertThat(result).isSameAs(root);
        }
    }

    @Nested
    class Mixed {

        @Test
        void should_unwrap_an_ite_wrapping_a_ute_wrapping_a_runtime_exception() {
            // Given
            IllegalStateException root = new IllegalStateException("root");
            UndeclaredThrowableException ute = new UndeclaredThrowableException(root);
            InvocationTargetException ite = new InvocationTargetException(ute);

            // When
            Throwable result = ThrowableUnwrap.unwrap(ite);

            // Then
            assertThat(result).isSameAs(root);
        }
    }

    @Nested
    class NullHandling {

        @Test
        void should_throw_npe_when_given_null() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ThrowableUnwrap.unwrap(null))
                    .withMessage("t");
        }
    }
}
