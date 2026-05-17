package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.invocation.MethodInvoker;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PassThroughEntryTest {

    public static final class TestSubject {

        boolean doNothingCalled;

        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        public int sum(int a, int b) {
            return a + b;
        }

        public void doNothing() {
            doNothingCalled = true;
        }

        public String throwsRuntime() {
            throw new IllegalStateException("runtime boom");
        }

        public String throwsChecked() throws IOException {
            throw new IOException("checked boom");
        }
    }

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return TestSubject.class.getDeclaredMethod(name, params);
    }

    @Nested
    class HappyPath {

        @Test
        void should_pass_arguments_through_to_the_invoker_and_return_the_result() throws Throwable {
            // Given
            TestSubject target = new TestSubject();
            MethodInvoker invoker = MethodInvoker.create(target, method("greet", String.class));
            PassThroughEntry entry = new PassThroughEntry(invoker);

            // When — PassThroughEntry documents that it ignores proxy and handler.
            Object result = entry.dispatch(null, null, new Object[]{"World"});

            // Then
            assertThat(result).isEqualTo("Hello, World!");
        }

        @Test
        void should_return_null_for_a_void_method() throws Throwable {
            // Given
            TestSubject target = new TestSubject();
            MethodInvoker invoker = MethodInvoker.create(target, method("doNothing"));
            PassThroughEntry entry = new PassThroughEntry(invoker);

            // When
            Object result = entry.dispatch(null, null, new Object[0]);

            // Then
            assertThat(result).isNull();
            assertThat(target.doNothingCalled).isTrue();
        }

        @Test
        void should_handle_a_method_with_multiple_arguments() throws Throwable {
            // Given
            MethodInvoker invoker = MethodInvoker.create(new TestSubject(), method("sum", int.class, int.class));
            PassThroughEntry entry = new PassThroughEntry(invoker);

            // When
            Object result = entry.dispatch(null, null, new Object[]{40, 2});

            // Then
            assertThat(result).isEqualTo(42);
        }
    }

    @Nested
    class ExceptionPropagation {

        @Test
        void should_propagate_runtime_exception_from_the_invoker() throws NoSuchMethodException {
            // Given
            MethodInvoker invoker = MethodInvoker.create(new TestSubject(), method("throwsRuntime"));
            PassThroughEntry entry = new PassThroughEntry(invoker);

            // When / Then
            assertThatThrownBy(() -> entry.dispatch(null, null, new Object[0]))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("runtime boom");
        }

        @Test
        void should_propagate_checked_exception_from_the_invoker() throws NoSuchMethodException {
            // Given
            MethodInvoker invoker = MethodInvoker.create(new TestSubject(), method("throwsChecked"));
            PassThroughEntry entry = new PassThroughEntry(invoker);

            // When / Then
            assertThatThrownBy(() -> entry.dispatch(null, null, new Object[0]))
                    .isExactlyInstanceOf(IOException.class)
                    .hasMessage("checked boom");
        }
    }

    @Nested
    class Construction {

        @Test
        void should_reject_null_invoker_with_npe() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new PassThroughEntry(null))
                    .withMessage("invoker");
        }
    }
}
