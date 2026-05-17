package eu.inqudium.proxy.construction;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncParadigmValidatorTest {

    interface TestService {
        CompletableFuture<String> fetchAsync(String name);
    }

    private static Method fetchAsyncMethod() throws NoSuchMethodException {
        return TestService.class.getDeclaredMethod("fetchAsync", String.class);
    }

    @Test
    void should_accept_a_list_of_async_elements() throws NoSuchMethodException {
        // Given
        List<InqElement> elements = List.of(
                new FakeAsyncDecorator("bh", InqElementType.BULKHEAD),
                new FakeAsyncDecorator("cb", InqElementType.CIRCUIT_BREAKER));

        // When / Then
        assertThatCode(() -> AsyncParadigmValidator.validate(elements, fetchAsyncMethod()))
                .doesNotThrowAnyException();
    }

    @Test
    void should_throw_when_an_element_does_not_implement_inq_async_decorator() throws NoSuchMethodException {
        // Given — FakeElement implements InqElement only, not InqAsyncDecorator
        List<InqElement> elements = List.of(
                new FakeElement("bh", InqElementType.BULKHEAD));

        // When / Then
        assertThatThrownBy(() -> AsyncParadigmValidator.validate(elements, fetchAsyncMethod()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InqAsyncDecorator");
    }

    @Test
    void should_throw_when_an_element_only_implements_sync_decorator() throws NoSuchMethodException {
        // What is to be tested?
        //   A sync-only decorator (implements InqDecorator but not
        //   InqAsyncDecorator) must be rejected for an async method.
        //   This is the paradigm-mismatch case the validator is
        //   designed to catch — a pipeline can carry sync-only
        //   elements without realising they are unsuitable for the
        //   async service interface.
        // How will the test case be deemed successful and why?
        //   IllegalStateException with the element's name and the
        //   target paradigm in the message.
        // Why is it important to test this test case?
        //   Pins the primary user-facing diagnostic for the
        //   wrong-paradigm error.

        // Given
        List<InqElement> elements = List.of(
                new FakeDecorator("syncOnly", InqElementType.BULKHEAD));

        // When / Then
        assertThatThrownBy(() -> AsyncParadigmValidator.validate(elements, fetchAsyncMethod()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InqAsyncDecorator")
                .hasMessageContaining("syncOnly");
    }

    @Test
    void should_include_method_and_element_name_in_the_error_message() throws NoSuchMethodException {
        // What is to be tested?
        //   The error message must name both the offending element
        //   and the method that triggered the validation. Without
        //   both, the user cannot locate the misconfigured pipeline
        //   element relative to the service interface.
        // How will the test case be deemed successful and why?
        //   The thrown message contains the method name and the
        //   element's name verbatim.
        // Why is it important to test this test case?
        //   The defensive guard's value is its diagnostic; a generic
        //   "wrong paradigm" message would leave the user grepping.

        // Given
        List<InqElement> elements = List.of(
                new FakeElement("rl", InqElementType.RATE_LIMITER));
        Method method = fetchAsyncMethod();

        // When / Then
        assertThatThrownBy(() -> AsyncParadigmValidator.validate(elements, method))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fetchAsync")
                .hasMessageContaining("rl")
                .hasMessageContaining("RATE_LIMITER");
    }

    @Test
    void should_accept_an_empty_list() throws NoSuchMethodException {
        // Given
        Method method = fetchAsyncMethod();

        // When / Then — vacuously valid: there are no offending elements
        assertThatCode(() -> AsyncParadigmValidator.validate(List.of(), method))
                .doesNotThrowAnyException();
    }

    @Test
    void should_reject_null_elements() throws NoSuchMethodException {
        // Given
        Method method = fetchAsyncMethod();

        // When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> AsyncParadigmValidator.validate(null, method))
                .withMessage("elements");
    }

    @Test
    void should_reject_null_method() {
        // Given
        List<InqElement> elements = List.of(
                new FakeAsyncDecorator("bh", InqElementType.BULKHEAD));

        // When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> AsyncParadigmValidator.validate(elements, null))
                .withMessage("method");
    }
}
