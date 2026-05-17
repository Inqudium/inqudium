package eu.inqudium.proxy.handler;

import eu.inqudium.proxy.entries.MethodDispatchEntry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ObjectMethodHandlerTest {

    public interface SomeIface {
        String anyMethod();
    }

    /**
     * Test fixture with content-based equals/hashCode/toString so we
     * can verify the handler delegates real-target behaviour exactly.
     */
    static final class Target {
        final int id;

        Target(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Target t && t.id == this.id;
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public String toString() {
            return "Target#" + id;
        }
    }

    private static Object proxyAround(Target target) {
        Map<Method, MethodDispatchEntry> empty = new HashMap<>();
        InqInvocationHandler handler = new InqInvocationHandler(
                1L, () -> 1L, target, empty);
        return Proxy.newProxyInstance(
                SomeIface.class.getClassLoader(),
                new Class<?>[]{SomeIface.class},
                handler);
    }

    @Nested
    class EqualsKind {

        @Test
        void should_return_true_when_other_is_a_proxy_with_equal_real_target() {
            // Given — two proxies with content-equal targets
            Target a = new Target(7);
            Target b = new Target(7);
            Object proxyOther = proxyAround(b);

            // When
            Object result = ObjectMethodHandler.dispatch(
                    ObjectMethodHandler.Kind.EQUALS, new Object(), a, new Object[]{proxyOther});

            // Then
            assertThat(result).isEqualTo(true);
        }

        @Test
        void should_return_false_when_other_is_a_proxy_with_distinct_real_target() {
            // Given
            Target a = new Target(7);
            Target b = new Target(8);
            Object proxyOther = proxyAround(b);

            // When
            Object result = ObjectMethodHandler.dispatch(
                    ObjectMethodHandler.Kind.EQUALS, new Object(), a, new Object[]{proxyOther});

            // Then
            assertThat(result).isEqualTo(false);
        }

        @Test
        void should_return_false_when_other_is_null() {
            // Given
            Target a = new Target(7);

            // When
            Object result = ObjectMethodHandler.dispatch(
                    ObjectMethodHandler.Kind.EQUALS, new Object(), a, new Object[]{null});

            // Then
            assertThat(result).isEqualTo(false);
        }

        @Test
        void should_return_false_when_other_is_not_a_proxy() {
            // What is to be tested?
            //   The §10 contract: equality requires the "other" side to
            //   be a JDK proxy. A plain object (even one equal to the
            //   real target) must compare unequal.
            // How will the test case be deemed successful and why?
            //   ObjectMethodHandler.dispatch with the real target itself
            //   as the "other" returns false — because the real target
            //   is not a JDK proxy.
            // Why is it important to test this test case?
            //   This surprising-but-correct corollary preserves equals
            //   symmetry: a non-proxy's equals(proxy) returns false
            //   (proxy isn't an instance of the non-proxy's class), so
            //   the proxy's equals(non-proxy) must also return false.

            // Given
            Target a = new Target(7);
            Target equalNonProxy = new Target(7);

            // When
            Object result = ObjectMethodHandler.dispatch(
                    ObjectMethodHandler.Kind.EQUALS, new Object(), a, new Object[]{equalNonProxy});

            // Then
            assertThat(result).isEqualTo(false);
        }

        @Test
        void should_return_false_when_other_is_a_proxy_with_different_handler_type() {
            // Given — a JDK proxy whose handler is NOT InqInvocationHandler
            Target a = new Target(7);
            InvocationHandler foreignHandler = (p, m, ar) -> null;
            Object foreignProxy = Proxy.newProxyInstance(
                    SomeIface.class.getClassLoader(),
                    new Class<?>[]{SomeIface.class},
                    foreignHandler);

            // When
            Object result = ObjectMethodHandler.dispatch(
                    ObjectMethodHandler.Kind.EQUALS, new Object(), a, new Object[]{foreignProxy});

            // Then
            assertThat(result).isEqualTo(false);
        }

        @Test
        void should_return_false_when_args_is_empty() {
            // What is to be tested?
            //   Defensive arity check — equals always carries exactly
            //   one argument. The handler must not crash on an
            //   unexpected args length and must surface a non-equal
            //   answer.
            // How will the test case be deemed successful and why?
            //   dispatch with an empty args array returns false (the
            //   defensive branch).
            // Why is it important to test this test case?
            //   Pins the defensive normalisation guarantee — a future
            //   refactor to ArgNormalizer that produced empty arrays
            //   for no-arg JDK calls would not break this dispatcher
            //   into an AIOOBE.

            // Given
            Target a = new Target(7);

            // When
            Object result = ObjectMethodHandler.dispatch(
                    ObjectMethodHandler.Kind.EQUALS, new Object(), a, new Object[0]);

            // Then
            assertThat(result).isEqualTo(false);
        }
    }

    @Nested
    class HashCodeKind {

        @Test
        void should_return_the_real_targets_hash_code() {
            // Given
            Target a = new Target(42);

            // When
            Object result = ObjectMethodHandler.dispatch(
                    ObjectMethodHandler.Kind.HASH_CODE, new Object(), a, new Object[0]);

            // Then
            assertThat(result).isEqualTo(42);
        }

        @Test
        void should_return_the_same_value_on_repeated_calls_for_an_immutable_target() {
            // What is to be tested?
            //   For an immutable target, hashCode must be stable across
            //   calls (an Object.hashCode contract for equal objects).
            //   The dispatcher must not impose any per-call drift.
            // How will the test case be deemed successful and why?
            //   Three calls produce the same hash.
            // Why is it important to test this test case?
            //   A regression that mixed in stack/call IDs (or any
            //   per-call counter) would break this hard JDK contract
            //   and corrupt hash-based collections.

            // Given
            Target a = new Target(123);

            // When
            Object first = ObjectMethodHandler.dispatch(
                    ObjectMethodHandler.Kind.HASH_CODE, new Object(), a, new Object[0]);
            Object second = ObjectMethodHandler.dispatch(
                    ObjectMethodHandler.Kind.HASH_CODE, new Object(), a, new Object[0]);
            Object third = ObjectMethodHandler.dispatch(
                    ObjectMethodHandler.Kind.HASH_CODE, new Object(), a, new Object[0]);

            // Then
            assertThat(first).isEqualTo(123);
            assertThat(second).isEqualTo(123);
            assertThat(third).isEqualTo(123);
        }
    }

    @Nested
    class ToStringKind {

        @Test
        void should_render_as_proxy_class_simple_name_plus_target_to_string() {
            // Given — a real JDK proxy gives the dispatcher a non-empty
            // proxy class with a recognisable simple name shape
            Target a = new Target(99);
            Object proxy = proxyAround(a);

            // When
            Object result = ObjectMethodHandler.dispatch(
                    ObjectMethodHandler.Kind.TO_STRING, proxy, a, new Object[0]);

            // Then
            assertThat(result).isEqualTo(
                    proxy.getClass().getSimpleName() + "[Target#99]");
        }

        @Test
        void should_render_the_real_targets_to_string_inside_the_brackets() {
            // Given
            Target a = new Target(5);
            Object proxy = proxyAround(a);

            // When
            String result = (String) ObjectMethodHandler.dispatch(
                    ObjectMethodHandler.Kind.TO_STRING, proxy, a, new Object[0]);

            // Then — the target's own toString output is embedded
            assertThat(result).contains("Target#5");
            assertThat(result).startsWith(proxy.getClass().getSimpleName() + "[");
            assertThat(result).endsWith("]");
        }
    }

    @Nested
    class NullArguments {

        @Test
        void should_reject_null_kind_with_npe() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ObjectMethodHandler.dispatch(
                            null, new Object(), new Object(), new Object[0]))
                    .withMessage("kind");
        }

        @Test
        void should_reject_null_proxy_with_npe() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ObjectMethodHandler.dispatch(
                            ObjectMethodHandler.Kind.TO_STRING,
                            null, new Object(), new Object[0]))
                    .withMessage("proxy");
        }

        @Test
        void should_reject_null_real_target_with_npe() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ObjectMethodHandler.dispatch(
                            ObjectMethodHandler.Kind.TO_STRING,
                            new Object(), null, new Object[0]))
                    .withMessage("realTarget");
        }

        @Test
        void should_reject_null_args_with_npe() {
            // Given / When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ObjectMethodHandler.dispatch(
                            ObjectMethodHandler.Kind.TO_STRING,
                            new Object(), new Object(), null))
                    .withMessage("args");
        }
    }
}
