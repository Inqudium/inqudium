package eu.inqudium.annotation;

import eu.inqudium.core.element.InqElementType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflection-based contract test for {@link InqShield}. Pins the annotation's
 * declared attributes, their default values, and its meta-annotations so that
 * accidental changes to the annotation surface are detected at build time.
 */
class InqShieldTest {

    @Nested
    class AttributeDeclarations {

        @Test
        void should_declare_exactly_two_attribute_methods_named_order_and_customOrder() {
            // Given — the InqShield annotation type
            Class<InqShield> annotationType = InqShield.class;

            // When — its declared methods are inspected
            String[] declaredAttributeNames = Arrays.stream(annotationType.getDeclaredMethods())
                    .map(Method::getName)
                    .sorted()
                    .toArray(String[]::new);

            // Then — exactly the two ADR-036 attributes are present
            assertThat(declaredAttributeNames).containsExactly("customOrder", "order");
        }

        @Test
        void should_declare_order_as_String_with_default_INQUDIUM() throws NoSuchMethodException {
            // Given — the order() attribute method
            Method orderAttribute = InqShield.class.getDeclaredMethod("order");

            // When — its return type and default value are read
            Class<?> returnType = orderAttribute.getReturnType();
            Object defaultValue = orderAttribute.getDefaultValue();

            // Then — String with default "INQUDIUM"
            assertThat(returnType).isEqualTo(String.class);
            assertThat(defaultValue).isEqualTo("INQUDIUM");
        }

        @Test
        void should_declare_customOrder_as_InqElementType_array_with_empty_default() throws NoSuchMethodException {
            // Given — the customOrder() attribute method
            Method customOrderAttribute = InqShield.class.getDeclaredMethod("customOrder");

            // When — its return type and default value are read
            Class<?> returnType = customOrderAttribute.getReturnType();
            Object defaultValue = customOrderAttribute.getDefaultValue();

            // Then — InqElementType[] with a zero-length default array
            assertThat(returnType).isEqualTo(InqElementType[].class);
            assertThat(defaultValue).isInstanceOf(InqElementType[].class);
            assertThat((InqElementType[]) defaultValue).isEmpty();
        }
    }

    @Nested
    class MetaAnnotations {

        @Test
        void should_be_targeted_at_method_and_type() {
            // Given — the @Target meta-annotation on InqShield
            Target target = InqShield.class.getAnnotation(Target.class);

            // When — its element types are read
            ElementType[] elementTypes = target == null ? new ElementType[0] : target.value();

            // Then — exactly METHOD and TYPE, in any order
            assertThat(target).isNotNull();
            assertThat(elementTypes).containsExactlyInAnyOrder(ElementType.METHOD, ElementType.TYPE);
        }

        @Test
        void should_have_runtime_retention() {
            // Given — the @Retention meta-annotation on InqShield
            Retention retention = InqShield.class.getAnnotation(Retention.class);

            // When — its policy is read
            RetentionPolicy policy = retention == null ? null : retention.value();

            // Then — RUNTIME, so reflection-based evaluators can read it
            assertThat(retention).isNotNull();
            assertThat(policy).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void should_be_documented() {
            // Given/When — the @Documented meta-annotation on InqShield
            Documented documented = InqShield.class.getAnnotation(Documented.class);

            // Then — present, so the annotation appears in generated Javadoc
            assertThat(documented).isNotNull();
        }

        @Test
        void should_be_inherited() {
            // Given/When — the @Inherited meta-annotation on InqShield
            Inherited inherited = InqShield.class.getAnnotation(Inherited.class);

            // Then — present, so class-level usage propagates to subclasses
            assertThat(inherited).isNotNull();
        }
    }
}
