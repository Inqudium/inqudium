package eu.inqudium.proxy.invocation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MethodHandleInvokerTest {

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return TestSubject.class.getDeclaredMethod(name, params);
    }

    @Nested
    class HappyPath {

        @Test
        void should_invoke_a_no_arg_void_method() throws Throwable {
            // Given
            TestSubject target = new TestSubject();
            MethodHandleInvoker invoker = new MethodHandleInvoker(target, method("doNothing"));

            // When
            Object result = invoker.invoke(new Object[0]);

            // Then
            assertThat(result).isNull();
            assertThat(target.doNothingCalled).isTrue();
        }

        @Test
        void should_invoke_a_method_with_primitive_arguments() throws Throwable {
            // Given
            MethodHandleInvoker invoker = new MethodHandleInvoker(new TestSubject(), method("sum", int.class, int.class));

            // When
            Object result = invoker.invoke(new Object[]{2, 3});

            // Then
            assertThat(result).isEqualTo(5);
        }

        @Test
        void should_invoke_a_method_with_reference_arguments() throws Throwable {
            // Given
            MethodHandleInvoker invoker = new MethodHandleInvoker(new TestSubject(), method("greet", String.class));

            // When
            Object result = invoker.invoke(new Object[]{"world"});

            // Then
            assertThat(result).isEqualTo("hello world");
        }

        @Test
        void should_return_a_primitive_value_as_boxed_object() throws Throwable {
            // Given
            MethodHandleInvoker invoker = new MethodHandleInvoker(new TestSubject(), method("sum", int.class, int.class));

            // When
            Object result = invoker.invoke(new Object[]{40, 2});

            // Then — boxing happens at the MethodHandle invokeWithArguments boundary.
            assertThat(result).isInstanceOf(Integer.class).isEqualTo(42);
        }

        @Test
        void should_return_a_reference_value() throws Throwable {
            // Given
            MethodHandleInvoker invoker = new MethodHandleInvoker(new TestSubject(), method("greet", String.class));

            // When
            Object result = invoker.invoke(new Object[]{"there"});

            // Then
            assertThat(result).isInstanceOf(String.class).isEqualTo("hello there");
        }
    }

    @Nested
    class ExceptionPropagation {

        @Test
        void should_propagate_runtime_exception_unchanged() throws NoSuchMethodException {
            // Given
            MethodHandleInvoker invoker = new MethodHandleInvoker(new TestSubject(), method("throwRuntime"));

            // When / Then — MethodHandle propagates the original throwable unchanged, no ITE wrapper.
            assertThatThrownBy(() -> invoker.invoke(new Object[0]))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("runtime boom");
        }

        @Test
        void should_propagate_checked_exception_unchanged() throws NoSuchMethodException {
            // Given
            MethodHandleInvoker invoker = new MethodHandleInvoker(new TestSubject(), method("throwChecked"));

            // When / Then
            assertThatThrownBy(() -> invoker.invoke(new Object[0]))
                    .isExactlyInstanceOf(IOException.class)
                    .hasMessage("checked boom");
        }

        @Test
        void should_propagate_error_unchanged() throws NoSuchMethodException {
            // Given
            MethodHandleInvoker invoker = new MethodHandleInvoker(new TestSubject(), method("throwError"));

            // When / Then
            assertThatThrownBy(() -> invoker.invoke(new Object[0]))
                    .isExactlyInstanceOf(AssertionError.class)
                    .hasMessage("error boom");
        }
    }
}
