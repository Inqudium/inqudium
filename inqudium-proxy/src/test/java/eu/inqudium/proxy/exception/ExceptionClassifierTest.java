package eu.inqudium.proxy.exception;

import eu.inqudium.proxy.InqUndeclaredCheckedException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionClassifierTest {

    /**
     * Test fixture: a service-like interface with carefully chosen
     * throws clauses so the classifier can compare an unwrapped
     * throwable against {@code method.getExceptionTypes()}.
     *
     * <ul>
     *   <li>{@link #declaresIoException()} throws {@link IOException}</li>
     *   <li>{@link #declaresNothing()} throws nothing checked</li>
     * </ul>
     */
    interface TestService {
        void declaresIoException() throws IOException;
        void declaresNothing();
    }

    private static Method declaresIoException() {
        try {
            return TestService.class.getDeclaredMethod("declaresIoException");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static Method declaresNothing() {
        try {
            return TestService.class.getDeclaredMethod("declaresNothing");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Nested
    class PassThrough {

        @Test
        void should_pass_through_a_runtime_exception() {
            // Given
            IllegalStateException original = new IllegalStateException("boom");

            // When
            Throwable result = ExceptionClassifier.classify(original, declaresNothing());

            // Then
            assertThat(result).isSameAs(original);
        }

        @Test
        void should_pass_through_an_error() {
            // Given
            AssertionError original = new AssertionError("boom");

            // When
            Throwable result = ExceptionClassifier.classify(original, declaresNothing());

            // Then
            assertThat(result).isSameAs(original);
        }

        @Test
        void should_pass_through_a_declared_checked_exception() {
            // Given — IOException is declared in declaresIoException()'s throws clause.
            IOException original = new IOException("boom");

            // When
            Throwable result = ExceptionClassifier.classify(original, declaresIoException());

            // Then
            assertThat(result).isSameAs(original);
        }
    }

    @Nested
    class Wrapping {

        @Test
        void should_wrap_an_undeclared_checked_exception_in_inq_undeclared() {
            // Given — declaresNothing() does not declare any checked exception, so an
            // SQLException must be wrapped as undeclared.
            SQLException original = new SQLException("boom");
            Method method = declaresNothing();

            // When
            Throwable result = ExceptionClassifier.classify(original, method);

            // Then
            assertThat(result)
                    .isExactlyInstanceOf(InqUndeclaredCheckedException.class);
            assertThat(result.getCause()).isSameAs(original);
        }

        @Test
        void should_attribute_the_wrapping_to_the_method_under_call() {
            // Given
            Method method = declaresNothing();

            // When
            Throwable result = ExceptionClassifier.classify(new SQLException("boom"), method);

            // Then
            assertThat(result).isInstanceOf(InqUndeclaredCheckedException.class);
            assertThat(((InqUndeclaredCheckedException) result).method()).isSameAs(method);
        }
    }

    @Nested
    class Unwrapping {

        @Test
        void should_unwrap_an_ite_before_classifying() {
            // Given — IOException is declared, so once unwrapped it passes through.
            IOException root = new IOException("boom");
            InvocationTargetException wrapper = new InvocationTargetException(root);

            // When
            Throwable result = ExceptionClassifier.classify(wrapper, declaresIoException());

            // Then
            assertThat(result).isSameAs(root);
        }

        @Test
        void should_unwrap_nested_wrappers_before_classifying() {
            // Given
            SQLException root = new SQLException("boom");
            InvocationTargetException inner = new InvocationTargetException(root);
            InvocationTargetException outer = new InvocationTargetException(inner);

            // When — declaresNothing() does not declare SQLException, so the classifier wraps it.
            Throwable result = ExceptionClassifier.classify(outer, declaresNothing());

            // Then
            assertThat(result)
                    .isExactlyInstanceOf(InqUndeclaredCheckedException.class);
            assertThat(result.getCause()).isSameAs(root);
        }

        @Test
        void should_pass_through_a_runtime_exception_wrapped_in_ite() {
            // Given
            IllegalStateException root = new IllegalStateException("boom");
            InvocationTargetException wrapper = new InvocationTargetException(root);

            // When
            Throwable result = ExceptionClassifier.classify(wrapper, declaresNothing());

            // Then
            assertThat(result).isSameAs(root);
        }
    }
}
