package eu.inqudium.proxy.introspection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MethodLayersTest {

    interface SomeService {
        String hello(String name);
    }

    private static Method helloMethod() throws NoSuchMethodException {
        return SomeService.class.getDeclaredMethod("hello", String.class);
    }

    @Test
    void should_construct_with_a_signature_layer_list_and_method_optional() throws NoSuchMethodException {
        // Given
        Method m = helloMethod();

        // When
        MethodLayers layers = new MethodLayers(
                "SomeService.hello(String)",
                List.of("BULKHEAD(orderBh)"),
                Optional.of(m));

        // Then
        assertThat(layers.methodSignature()).isEqualTo("SomeService.hello(String)");
        assertThat(layers.layerDescriptions()).containsExactly("BULKHEAD(orderBh)");
        assertThat(layers.method()).contains(m);
    }

    @Test
    void should_make_layer_descriptions_immutable_via_copy() throws NoSuchMethodException {
        // What is to be tested?
        //   The compact constructor wraps layerDescriptions via
        //   List.copyOf — a mutating call on the returned list must
        //   fail, and post-construction mutation of the source list
        //   must not be visible through the accessor.
        // How will the test case be deemed successful and why?
        //   The returned list rejects add(); a mutation on the
        //   original list does not show up in the snapshot.
        // Why is it important to test this test case?
        //   ADR-039 introspection promises immutable DTOs; consumers
        //   may retain the list and rely on its content stability.

        // Given
        Method m = helloMethod();
        List<String> source = new ArrayList<>(Arrays.asList("A", "B"));
        MethodLayers layers = new MethodLayers(
                "SomeService.hello(String)", source, Optional.of(m));

        // When — mutate the source after construction
        source.add("C");

        // Then
        assertThat(layers.layerDescriptions()).containsExactly("A", "B");
        assertThatThrownBy(() -> layers.layerDescriptions().add("D"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_reject_null_method_signature_with_npe() throws NoSuchMethodException {
        // Given
        Method m = helloMethod();

        // When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new MethodLayers(null, List.of(), Optional.of(m)))
                .withMessage("methodSignature");
    }

    @Test
    void should_reject_null_method_optional_with_npe() {
        // What is to be tested?
        //   The compact constructor rejects null for the
        //   method-Optional component. The Optional itself is the
        //   nullability boundary — passing Optional.empty() is valid,
        //   but passing a raw null is not.
        // How will the test case be deemed successful and why?
        //   Constructing with method=null surfaces NPE("method").
        // Why is it important to test this test case?
        //   Pins the contract that the record uses Optional as the
        //   nullability sentinel, not raw null.

        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new MethodLayers("Sig.foo()", List.of(), null))
                .withMessage("method");
    }

    @Test
    void should_accept_an_empty_layer_descriptions_list() throws NoSuchMethodException {
        // Given
        Method m = helloMethod();

        // When
        MethodLayers layers = new MethodLayers(
                "SomeService.hello(String)", List.of(), Optional.of(m));

        // Then
        assertThat(layers.layerDescriptions()).isEmpty();
    }
}
