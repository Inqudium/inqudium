package eu.inqudium.proxy.entries;

import eu.inqudium.proxy.handler.InqInvocationHandler;
import eu.inqudium.proxy.handler.ObjectMethodHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ObjectMethodEntryTest {

    static final class Target {
        @Override
        public int hashCode() {
            return 77;
        }

        @Override
        public String toString() {
            return "Target!";
        }
    }

    @Test
    void should_require_non_null_kind() {
        // What is to be tested?
        //   The package-private record's compact constructor must
        //   reject null kinds — the entry has nothing meaningful to
        //   dispatch on without one.
        // How will the test case be deemed successful and why?
        //   The factory call surfaces an NPE with message "kind"
        //   (Objects.requireNonNull pattern).
        // Why is it important to test this test case?
        //   Pins the fail-fast contract on construction.

        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> MethodDispatchEntry.objectMethod(null))
                .withMessage("kind");
    }

    @Test
    void should_delegate_dispatch_to_object_method_handler_with_the_carried_kind() throws Throwable {
        // What is to be tested?
        //   The entry's dispatch must call ObjectMethodHandler with the
        //   Kind it was constructed with and the realTarget read from
        //   the handler. We verify by routing two entries (HASH_CODE
        //   and TO_STRING) against the same target and asserting both
        //   produce the same value ObjectMethodHandler would.
        // How will the test case be deemed successful and why?
        //   The hashCode entry returns target.hashCode(); the toString
        //   entry returns the descriptive string format. These match
        //   what ObjectMethodHandler.dispatch would produce.
        // Why is it important to test this test case?
        //   This is the central contract of ObjectMethodEntry — it is
        //   a thin Kind-carrier that delegates to the shared handler.

        // Given
        Target target = new Target();
        InqInvocationHandler handler = new InqInvocationHandler(
                1L, () -> 1L, target, Map.<Method, MethodDispatchEntry>of());
        MethodDispatchEntry hashCodeEntry =
                MethodDispatchEntry.objectMethod(ObjectMethodHandler.Kind.HASH_CODE);
        MethodDispatchEntry toStringEntry =
                MethodDispatchEntry.objectMethod(ObjectMethodHandler.Kind.TO_STRING);
        Object dummyProxy = new Object();

        // When
        Object hashResult = hashCodeEntry.dispatch(dummyProxy, handler, new Object[0]);
        Object toStringResult = toStringEntry.dispatch(dummyProxy, handler, new Object[0]);

        // Then
        assertThat(hashResult).isEqualTo(77);
        assertThat(toStringResult).isEqualTo(
                dummyProxy.getClass().getSimpleName() + "[Target!]");
    }

    @Test
    void should_be_a_record_with_a_kind_component() {
        // What is to be tested?
        //   The entry is a record carrying exactly one Kind component.
        //   The factory abstracts away the record-ness from callers,
        //   but the implementation choice has a few downstream
        //   consequences (equals/hashCode of the entry itself,
        //   serialisability concerns); this test pins the shape.
        // How will the test case be deemed successful and why?
        //   The Class is a record with a single record component
        //   typed ObjectMethodHandler.Kind.
        // Why is it important to test this test case?
        //   A refactor that turned the entry into a regular class
        //   would lose the auto-generated equals/hashCode and silently
        //   change the entry's identity semantics.

        // Given
        MethodDispatchEntry entry =
                MethodDispatchEntry.objectMethod(ObjectMethodHandler.Kind.EQUALS);

        // When / Then
        assertThat(entry.getClass().isRecord()).isTrue();
        assertThat(entry.getClass().getRecordComponents()).hasSize(1);
        assertThat(entry.getClass().getRecordComponents()[0].getType())
                .isEqualTo(ObjectMethodHandler.Kind.class);
    }
}
