package eu.inqudium.proxy.construction;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncParadigmValidatorTest {

    interface TestService {
        String greet(String name);
    }

    private static Method greetMethod() throws NoSuchMethodException {
        return TestService.class.getDeclaredMethod("greet", String.class);
    }

    @Test
    void should_accept_a_list_of_sync_elements() throws NoSuchMethodException {
        // Given
        List<InqElement> elements = List.of(
                new FakeDecorator("bh", InqElementType.BULKHEAD),
                new FakeDecorator("cb", InqElementType.CIRCUIT_BREAKER));

        // When / Then
        assertThatCode(() -> SyncParadigmValidator.validate(elements, greetMethod()))
                .doesNotThrowAnyException();
    }

    @Test
    void should_throw_when_an_element_does_not_implement_inq_decorator() throws NoSuchMethodException {
        // Given — FakeElement implements InqElement only, not InqDecorator
        List<InqElement> elements = List.of(
                new FakeElement("bh", InqElementType.BULKHEAD));

        // When / Then
        assertThatThrownBy(() -> SyncParadigmValidator.validate(elements, greetMethod()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InqDecorator");
    }

    @Test
    void should_include_method_and_element_name_in_the_error_message() throws NoSuchMethodException {
        // What is to be tested?
        //   When a paradigm mismatch is detected, the error message
        //   must name both the offending element and the method that
        //   triggered the validation. Without both, the user cannot
        //   locate the misconfigured pipeline element relative to the
        //   service interface.
        // How will the test case be deemed successful and why?
        //   The thrown message contains the method name and the
        //   element's name verbatim. Asserting on substrings — not the
        //   full message — keeps the test resilient to incidental
        //   wording changes.
        // Why is it important to test this test case?
        //   The defensive guard's value is its diagnostic; a generic
        //   "wrong paradigm" message would leave the user grepping.

        // Given
        List<InqElement> elements = List.of(
                new FakeElement("rl", InqElementType.RATE_LIMITER));
        Method method = greetMethod();

        // When / Then
        assertThatThrownBy(() -> SyncParadigmValidator.validate(elements, method))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("greet")
                .hasMessageContaining("rl")
                .hasMessageContaining("RATE_LIMITER");
    }

    @Test
    void should_accept_an_empty_list() throws NoSuchMethodException {
        // Given
        Method method = greetMethod();

        // When / Then — vacuously valid: there are no offending elements
        assertThatCode(() -> SyncParadigmValidator.validate(List.of(), method))
                .doesNotThrowAnyException();
    }

    @Test
    void should_reject_null_elements() throws NoSuchMethodException {
        // Given
        Method method = greetMethod();

        // When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> SyncParadigmValidator.validate(null, method))
                .withMessage("elements");
    }

    @Test
    void should_reject_null_method() {
        // Given
        List<InqElement> elements = List.of(new FakeDecorator("bh", InqElementType.BULKHEAD));

        // When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> SyncParadigmValidator.validate(elements, null))
                .withMessage("method");
    }
}
