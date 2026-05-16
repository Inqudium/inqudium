package eu.inqudium.proxy.handler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArgNormalizerTest {

    @Test
    void should_return_an_empty_array_when_given_null() {
        // Given / When
        Object[] result = ArgNormalizer.normalise(null);

        // Then
        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_return_the_same_array_instance_when_given_a_non_null_array() {
        // Given — ADR-035 §11 forbids defensive copying.
        Object[] input = new Object[]{"a", 42, null};

        // When
        Object[] result = ArgNormalizer.normalise(input);

        // Then
        assertThat(result).isSameAs(input);
    }

    @Test
    void should_return_an_empty_array_singleton_across_null_inputs() {
        // What is to be tested?
        //   That repeated null inputs map to the same cached empty-array instance,
        //   not to a fresh allocation per call.
        // How will the test case be deemed successful and why?
        //   The two return values must be reference-equal. A non-singleton would
        //   indicate unnecessary allocation on the hot path of every no-arg
        //   service-method dispatch.
        // Why is it important to test this test case?
        //   ArgNormalizer is called inside InvocationHandler#invoke for every
        //   single proxy call. The empty-array singleton avoids a per-call
        //   Object[0] allocation; a regression would silently leak garbage.

        // Given / When
        Object[] first = ArgNormalizer.normalise(null);
        Object[] second = ArgNormalizer.normalise(null);

        // Then
        assertThat(first).isSameAs(second);
    }

    @Test
    void should_not_defensively_copy_a_non_empty_array() {
        // Given
        Object[] input = new Object[]{"x"};

        // When
        Object[] result = ArgNormalizer.normalise(input);
        input[0] = "y";

        // Then — mutations to the source array are visible through the returned reference,
        // confirming no defensive copy was made.
        assertThat(result[0]).isEqualTo("y");
        assertThat(result).isSameAs(input);
    }
}
