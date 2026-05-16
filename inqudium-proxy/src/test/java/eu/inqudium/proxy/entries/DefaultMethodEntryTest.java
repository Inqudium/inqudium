package eu.inqudium.proxy.entries;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DefaultMethodEntryTest {

    public interface ServiceWithDefault {

        default String greet(String name) {
            return "Hello, " + name + "!";
        }

        default int sum(int a, int b) {
            return a + b;
        }

        String mustOverride();
    }

    private static ServiceWithDefault newProxy() {
        return (ServiceWithDefault) Proxy.newProxyInstance(
                DefaultMethodEntryTest.class.getClassLoader(),
                new Class[]{ServiceWithDefault.class},
                (proxyArg, method, args) -> {
                    DefaultMethodEntry entry = new DefaultMethodEntry(method);
                    return entry.dispatch(proxyArg, null, args);
                });
    }

    @Nested
    class HappyPath {

        @Test
        void should_invoke_the_default_method_via_invoke_default() {
            // Given
            ServiceWithDefault proxy = newProxy();

            // When
            String result = proxy.greet("World");

            // Then — the default body on ServiceWithDefault ran and produced
            // the expected greeting.
            assertThat(result).isEqualTo("Hello, World!");
        }

        @Test
        void should_pass_arguments_to_the_default_method() {
            // Given
            ServiceWithDefault proxy = newProxy();

            // When
            int result = proxy.sum(40, 2);

            // Then
            assertThat(result).isEqualTo(42);
        }

        @Test
        void should_return_the_default_method_s_result() {
            // Given
            ServiceWithDefault proxy = newProxy();

            // When
            String result = proxy.greet("Inqudium");

            // Then — confirms the returned value is the default body's
            // computed value, not a fixture constant inadvertently leaked
            // by the test harness.
            assertThat(result).isEqualTo("Hello, Inqudium!");
        }
    }

    @Nested
    class Construction {

        @Test
        void should_reject_null_default_method_with_npe() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new DefaultMethodEntry(null))
                    .withMessage("defaultMethod");
        }

        @Test
        void should_reject_a_non_default_method_with_illegal_argument_exception() throws NoSuchMethodException {
            // Given — List#size is an abstract method on List, not a default.
            Method nonDefault = List.class.getMethod("size");
            assertThat(nonDefault.isDefault()).isFalse();

            // When / Then
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DefaultMethodEntry(nonDefault))
                    .withMessageContaining("not a default method");
        }
    }
}
