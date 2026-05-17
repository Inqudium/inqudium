package eu.inqudium.proxy.handler;

import eu.inqudium.proxy.InqUndeclaredCheckedException;
import eu.inqudium.proxy.entries.MethodDispatchEntry;
import eu.inqudium.proxy.invocation.MethodInvoker;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
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

    public interface TestService {
        String greet(String name);

        int sum(int a, int b);

        void doNothing();

        String throwsRuntime();

        String throwsChecked() throws IOException;

        String throwsUndeclared();

        String throwsError();
    }

    public static final class RecordingTarget implements TestService {

        Object[] greetArgs;
        int doNothingCallCount;

        @Override
        public String greet(String name) {
            greetArgs = new Object[]{name};
            return "Hello, " + name + "!";
        }

        @Override
        public int sum(int a, int b) {
            return a + b;
        }

        @Override
        public void doNothing() {
            doNothingCallCount++;
        }

        @Override
        public String throwsRuntime() {
            throw new IllegalStateException("runtime boom");
        }

        @Override
        public String throwsChecked() throws IOException {
            throw new IOException("declared boom");
        }

        @Override
        public String throwsUndeclared() {
            // sneakyThrow a checked exception that throwsUndeclared()
            // does not declare. The signature does not list IOException
            // — that's exactly the undeclared-checked situation the
            // classifier must catch.
            sneakyThrow(new IOException("undeclared boom"));
            return null;
        }

        @Override
        public String throwsError() {
            throw new AssertionError("error boom");
        }

        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
            throw (E) t;
        }
    }

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return TestService.class.getDeclaredMethod(name, params);
    }

    private static Map<Method, MethodDispatchEntry> entriesFor(TestService target) throws NoSuchMethodException {
        Map<Method, MethodDispatchEntry> map = new HashMap<>();
        for (Method m : TestService.class.getDeclaredMethods()) {
            map.put(m, MethodDispatchEntry.passThrough(MethodInvoker.create(target, m)));
        }
        return map;
    }

    @Nested
    class State {

        @Test
        void should_store_stack_id_passed_to_constructor() throws NoSuchMethodException {
            // Given
            RecordingTarget target = new RecordingTarget();

            // When
            InqInvocationHandler handler = new InqInvocationHandler(
                    42L, countingSource(), target, entriesFor(target));

            // Then
            assertThat(handler.stackId()).isEqualTo(42L);
        }

        @Test
        void should_expose_real_target_via_accessor() throws NoSuchMethodException {
            // What is to be tested?
            //   That the handler exposes the real target it was
            //   constructed with via realTarget(). This is the read
            //   path ObjectMethodHandler uses for cross-proxy equals
            //   per ARCHITECTURE.md §10.
            // How will the test case be deemed successful and why?
            //   realTarget() returns the exact instance passed to the
            //   constructor (reference identity, not equals).
            // Why is it important to test this test case?
            //   A regression that built a fresh wrapper or returned a
            //   defensive copy would break equals symmetry and the
            //   ProxyStackAdapter introspection path that depends on
            //   the same accessor.

            // Given
            RecordingTarget target = new RecordingTarget();

            // When
            InqInvocationHandler handler = new InqInvocationHandler(
                    1L, countingSource(), target, entriesFor(target));

            // Then
            assertThat(handler.realTarget()).isSameAs(target);
        }

        @Test
        void should_pull_call_ids_from_the_source() throws NoSuchMethodException {
            // Given — a source we control, returning a fixed sequence
            AtomicLong counter = new AtomicLong(99);
            RecordingTarget target = new RecordingTarget();
            InqInvocationHandler handler = new InqInvocationHandler(
                    1L, counter::incrementAndGet, target, entriesFor(target));

            // When
            long first = handler.nextCallId();

            // Then — the handler reads through to the supplier
            assertThat(first).isEqualTo(100L);
        }

        @Test
        void should_pull_a_fresh_value_on_each_call_to_next_call_id() throws NoSuchMethodException {
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
            RecordingTarget target = new RecordingTarget();
            InqInvocationHandler handler = new InqInvocationHandler(
                    1L, countingSource(), target, entriesFor(target));

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
        void should_reject_null_call_id_source_with_npe() throws NoSuchMethodException {
            // Given
            RecordingTarget target = new RecordingTarget();
            Map<Method, MethodDispatchEntry> entries = entriesFor(target);

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new InqInvocationHandler(1L, null, target, entries))
                    .withMessage("callIdSource");
        }

        @Test
        void should_reject_null_real_target_with_npe() throws NoSuchMethodException {
            // Given
            Map<Method, MethodDispatchEntry> entries = entriesFor(new RecordingTarget());

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new InqInvocationHandler(1L, countingSource(), null, entries))
                    .withMessage("realTarget");
        }

        @Test
        void should_reject_null_entries_map_with_npe() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> new InqInvocationHandler(
                            1L, countingSource(), new RecordingTarget(), null))
                    .withMessage("entries");
        }
    }

    @Nested
    class Dispatch {

        @Test
        void should_normalise_null_args_before_dispatching() throws Throwable {
            // What is to be tested?
            //   The handler must replace a null args array (the JDK
            //   convention for no-arg methods) with an empty array
            //   before passing it to the dispatch entry. Otherwise
            //   MethodInvoker.invoke(null) would NPE on no-arg methods.
            // How will the test case be deemed successful and why?
            //   Calling invoke with null args for doNothing() must
            //   succeed and reach the target. A non-normalised null
            //   would surface as an NPE deep inside the invoker.
            // Why is it important to test this test case?
            //   Pins the contract that the handler is the single
            //   normalisation site for the proxy invocation's args.

            // Given
            RecordingTarget target = new RecordingTarget();
            InqInvocationHandler handler = new InqInvocationHandler(
                    1L, countingSource(), target, entriesFor(target));
            Method doNothing = method("doNothing");

            // When
            Object result = handler.invoke(new Object(), doNothing, null);

            // Then
            assertThat(result).isNull();
            assertThat(target.doNothingCallCount).isEqualTo(1);
        }

        @Test
        void should_route_to_the_entry_returned_by_the_cache() throws Throwable {
            // Given
            RecordingTarget target = new RecordingTarget();
            InqInvocationHandler handler = new InqInvocationHandler(
                    1L, countingSource(), target, entriesFor(target));
            Method greet = method("greet", String.class);

            // When
            Object result = handler.invoke(new Object(), greet, new Object[]{"World"});

            // Then — the right entry's target method was invoked
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(target.greetArgs).containsExactly("World");
        }

        @Test
        void should_pass_the_proxy_and_handler_to_the_entry() throws Throwable {
            // What is to be tested?
            //   That handler.invoke calls dispatch with the proxy
            //   instance from the JDK and the handler itself. Verified
            //   indirectly by routing the call through a real JDK
            //   proxy and asserting the end-to-end result. If the
            //   handler dropped the proxy parameter, JDK-internals
            //   would surface a mismatch.
            // How will the test case be deemed successful and why?
            //   The proxy returned by Proxy.newProxyInstance behaves
            //   as the service, returning the target's value through
            //   the dispatch entry.
            // Why is it important to test this test case?
            //   Catches the regression where a future refactor passes
            //   the wrong reference (or null) as the proxy/handler
            //   parameter to dispatch — DefaultMethodEntry and any
            //   future Object-method entries depend on this being
            //   correct.

            // Given
            RecordingTarget target = new RecordingTarget();
            InqInvocationHandler handler = new InqInvocationHandler(
                    1L, countingSource(), target, entriesFor(target));

            // When — go through a real JDK proxy so the JDK supplies
            // the proxy parameter to invoke(...)
            TestService proxy = (TestService) Proxy.newProxyInstance(
                    TestService.class.getClassLoader(),
                    new Class<?>[]{TestService.class},
                    handler);

            // Then
            assertThat(proxy.greet("World")).isEqualTo("Hello, World!");
            assertThat(proxy.sum(40, 2)).isEqualTo(42);
        }

        @Test
        void should_propagate_a_runtime_exception_from_the_entry_unchanged() throws NoSuchMethodException {
            // Given
            RecordingTarget target = new RecordingTarget();
            InqInvocationHandler handler = new InqInvocationHandler(
                    1L, countingSource(), target, entriesFor(target));
            Method throwing = method("throwsRuntime");

            // When / Then
            assertThatThrownBy(() -> handler.invoke(new Object(), throwing, new Object[0]))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("runtime boom");
        }

        @Test
        void should_classify_an_undeclared_checked_exception_as_inq_undeclared() throws NoSuchMethodException {
            // What is to be tested?
            //   When the target throws a checked exception that the
            //   service method does not declare, the handler must
            //   classify it via ExceptionClassifier and surface an
            //   InqUndeclaredCheckedException.
            // How will the test case be deemed successful and why?
            //   The thrown exception is exactly
            //   InqUndeclaredCheckedException with the original
            //   IOException as its cause.
            // Why is it important to test this test case?
            //   This is the contract from ADR-035 §10: undeclared
            //   checked exceptions must be wrapped, never propagated
            //   raw, so reflective callers cannot see surprising
            //   types on a method whose signature did not advertise
            //   them.

            // Given
            RecordingTarget target = new RecordingTarget();
            InqInvocationHandler handler = new InqInvocationHandler(
                    1L, countingSource(), target, entriesFor(target));
            Method throwing = method("throwsUndeclared");

            // When / Then
            assertThatThrownBy(() -> handler.invoke(new Object(), throwing, new Object[0]))
                    .isExactlyInstanceOf(InqUndeclaredCheckedException.class)
                    .hasCauseExactlyInstanceOf(IOException.class);
        }

        @Test
        void should_propagate_an_error_from_the_entry_unchanged() throws NoSuchMethodException {
            // Given
            RecordingTarget target = new RecordingTarget();
            InqInvocationHandler handler = new InqInvocationHandler(
                    1L, countingSource(), target, entriesFor(target));
            Method throwing = method("throwsError");

            // When / Then
            assertThatThrownBy(() -> handler.invoke(new Object(), throwing, new Object[0]))
                    .isExactlyInstanceOf(AssertionError.class)
                    .hasMessage("error boom");
        }

        @Test
        void should_throw_illegal_state_when_the_method_is_not_in_the_cache() throws NoSuchMethodException {
            // Given — entries map intentionally missing the doNothing entry
            RecordingTarget target = new RecordingTarget();
            Map<Method, MethodDispatchEntry> incomplete = new HashMap<>();
            Method greet = method("greet", String.class);
            incomplete.put(greet,
                    MethodDispatchEntry.passThrough(MethodInvoker.create(target, greet)));
            InqInvocationHandler handler = new InqInvocationHandler(
                    1L, countingSource(), target, incomplete);
            Method missing = method("doNothing");

            // When / Then — cache miss is classified by the handler's
            // try/catch, but IllegalStateException is a RuntimeException
            // so the classifier returns it unchanged.
            assertThatThrownBy(() -> handler.invoke(new Object(), missing, new Object[0]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No dispatch entry");
        }
    }
}
