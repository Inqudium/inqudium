package eu.inqudium.proxy.invocation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReflectiveInvokerTest {

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return TestSubject.class.getDeclaredMethod(name, params);
    }

    @Nested
    class HappyPath {

        @Test
        void should_invoke_a_no_arg_void_method() throws Throwable {
            // Given
            TestSubject target = new TestSubject();
            ReflectiveInvoker invoker = new ReflectiveInvoker(target, method("doNothing"));

            // When
            Object result = invoker.invoke(new Object[0]);

            // Then
            assertThat(result).isNull();
            assertThat(target.doNothingCalled).isTrue();
        }

        @Test
        void should_invoke_a_method_with_primitive_arguments() throws Throwable {
            // Given
            ReflectiveInvoker invoker = new ReflectiveInvoker(new TestSubject(), method("sum", int.class, int.class));

            // When
            Object result = invoker.invoke(new Object[]{2, 3});

            // Then
            assertThat(result).isEqualTo(5);
        }

        @Test
        void should_invoke_a_method_with_reference_arguments() throws Throwable {
            // Given
            ReflectiveInvoker invoker = new ReflectiveInvoker(new TestSubject(), method("greet", String.class));

            // When
            Object result = invoker.invoke(new Object[]{"world"});

            // Then
            assertThat(result).isEqualTo("hello world");
        }

        @Test
        void should_return_a_primitive_value_as_boxed_object() throws Throwable {
            // Given
            ReflectiveInvoker invoker = new ReflectiveInvoker(new TestSubject(), method("sum", int.class, int.class));

            // When
            Object result = invoker.invoke(new Object[]{40, 2});

            // Then
            assertThat(result).isInstanceOf(Integer.class).isEqualTo(42);
        }

        @Test
        void should_return_a_reference_value() throws Throwable {
            // Given
            ReflectiveInvoker invoker = new ReflectiveInvoker(new TestSubject(), method("greet", String.class));

            // When
            Object result = invoker.invoke(new Object[]{"there"});

            // Then
            assertThat(result).isInstanceOf(String.class).isEqualTo("hello there");
        }
    }

    @Nested
    class ExceptionPropagation {

        @Test
        void should_wrap_runtime_exception_in_invocation_target_exception() throws NoSuchMethodException {
            // Given
            ReflectiveInvoker invoker = new ReflectiveInvoker(new TestSubject(), method("throwRuntime"));

            // When / Then — Method.invoke wraps the target's throwable in ITE.
            assertThatThrownBy(() -> invoker.invoke(new Object[0]))
                    .isExactlyInstanceOf(InvocationTargetException.class)
                    .cause()
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("runtime boom");
        }

        @Test
        void should_wrap_checked_exception_in_invocation_target_exception() throws NoSuchMethodException {
            // Given
            ReflectiveInvoker invoker = new ReflectiveInvoker(new TestSubject(), method("throwChecked"));

            // When / Then
            assertThatThrownBy(() -> invoker.invoke(new Object[0]))
                    .isExactlyInstanceOf(InvocationTargetException.class)
                    .cause()
                    .isExactlyInstanceOf(IOException.class)
                    .hasMessage("checked boom");
        }

        @Test
        void should_wrap_error_in_invocation_target_exception() throws NoSuchMethodException {
            // Given
            ReflectiveInvoker invoker = new ReflectiveInvoker(new TestSubject(), method("throwError"));

            // When / Then
            assertThatThrownBy(() -> invoker.invoke(new Object[0]))
                    .isExactlyInstanceOf(InvocationTargetException.class)
                    .cause()
                    .isExactlyInstanceOf(AssertionError.class)
                    .hasMessage("error boom");
        }

        @Test
        void should_expose_the_original_throwable_as_the_invocation_target_exception_cause() throws NoSuchMethodException {
            // What is to be tested?
            //   That the ITE wrapping the target's exception keeps the original
            //   throwable exactly — same type, same identity-of-cause — so that
            //   downstream unwrapping logic (ThrowableUnwrap) has a fixed point
            //   to reach.
            // How will the test case be deemed successful and why?
            //   The caught ITE must carry the originally-thrown IllegalStateException
            //   instance as its cause. If the JDK ever changed this contract, the
            //   proxy's whole error-classification pipeline would break.
            // Why is it important to test this test case?
            //   The contract difference between ReflectiveInvoker (wraps in ITE)
            //   and MethodHandleInvoker (propagates unchanged) is the *reason*
            //   ThrowableUnwrap exists. Pinning the ITE contract here documents
            //   the asymmetry the unwrapper compensates for.

            // Given
            ReflectiveInvoker invoker = new ReflectiveInvoker(new TestSubject(), method("throwRuntime"));

            // When
            Throwable thrown = null;
            try {
                invoker.invoke(new Object[0]);
            } catch (Throwable t) {
                thrown = t;
            }

            // Then
            assertThat(thrown)
                    .isExactlyInstanceOf(InvocationTargetException.class);
            assertThat(thrown.getCause())
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("runtime boom");
        }
    }
}
