package eu.inqudium.proxy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class InqUndeclaredCheckedExceptionTest {

    interface SampleService {
        String getValue();
    }

    private static Method sampleMethod() {
        try {
            return SampleService.class.getDeclaredMethod("getValue");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("test fixture broken", e);
        }
    }

    @Test
    void should_store_method_and_cause_supplied_to_constructor() {
        // Given
        Method method = sampleMethod();
        Throwable cause = new IOException("boom");

        // When
        InqUndeclaredCheckedException exception = new InqUndeclaredCheckedException(method, cause);

        // Then
        assertThat(exception.method()).isSameAs(method);
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void should_expose_method_via_method_accessor() {
        // Given
        Method method = sampleMethod();

        // When
        InqUndeclaredCheckedException exception = new InqUndeclaredCheckedException(method, new IOException());

        // Then
        assertThat(exception.method()).isSameAs(method);
    }

    @Test
    void should_expose_cause_via_get_cause() {
        // Given
        Throwable cause = new IOException("boom");

        // When
        InqUndeclaredCheckedException exception = new InqUndeclaredCheckedException(sampleMethod(), cause);

        // Then
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void should_carry_a_message_referencing_the_method_signature() {
        // Given
        Method method = sampleMethod();

        // When
        InqUndeclaredCheckedException exception = new InqUndeclaredCheckedException(method, new IOException());

        // Then — the JDK's Method.toString() form is embedded in the message.
        assertThat(exception.getMessage())
                .startsWith("Undeclared checked exception thrown by ")
                .contains(method.toString());
    }

    @Test
    void should_be_an_undeclared_throwable_exception() {
        // Given / When
        InqUndeclaredCheckedException exception = new InqUndeclaredCheckedException(sampleMethod(), new IOException());

        // Then — both the JDK supertype and the Inqudium subtype must be catchable.
        assertThat(exception).isInstanceOf(UndeclaredThrowableException.class);
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    void should_reject_null_method_with_npe() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new InqUndeclaredCheckedException(null, new IOException()))
                .withMessage("method");
    }

    @Test
    void should_reject_null_cause_with_npe() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new InqUndeclaredCheckedException(sampleMethod(), null))
                .withMessage("cause");
    }
}
