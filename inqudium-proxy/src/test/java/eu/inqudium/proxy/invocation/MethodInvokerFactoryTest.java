package eu.inqudium.proxy.invocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MethodInvokerFactoryTest {

    private static final String PROPERTY = "inqudium.proxy.invoker";

    private String savedValue;

    @BeforeEach
    void saveProperty() {
        savedValue = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (savedValue == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, savedValue);
        }
    }

    private static Method greetMethod() throws NoSuchMethodException {
        return TestSubject.class.getDeclaredMethod("greet", String.class);
    }

    @Test
    void should_create_a_method_handle_invoker_by_default() throws NoSuchMethodException {
        // Given — property is cleared in @BeforeEach
        TestSubject target = new TestSubject();

        // When
        MethodInvoker invoker = MethodInvoker.create(target, greetMethod());

        // Then
        assertThat(invoker).isInstanceOf(MethodHandleInvoker.class);
    }

    @Test
    void should_create_a_method_handle_invoker_when_property_is_mh() throws NoSuchMethodException {
        // Given
        System.setProperty(PROPERTY, "mh");

        // When
        MethodInvoker invoker = MethodInvoker.create(new TestSubject(), greetMethod());

        // Then
        assertThat(invoker).isInstanceOf(MethodHandleInvoker.class);
    }

    @Test
    void should_create_a_reflective_invoker_when_property_is_reflective() throws NoSuchMethodException {
        // Given
        System.setProperty(PROPERTY, "reflective");

        // When
        MethodInvoker invoker = MethodInvoker.create(new TestSubject(), greetMethod());

        // Then
        assertThat(invoker).isInstanceOf(ReflectiveInvoker.class);
    }

    @Test
    void should_throw_illegal_argument_exception_for_unknown_property_value() throws NoSuchMethodException {
        // Given
        System.setProperty(PROPERTY, "bogus");
        Method method = greetMethod();
        TestSubject target = new TestSubject();

        // When / Then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MethodInvoker.create(target, method))
                .withMessageContaining("bogus")
                .withMessageContaining(PROPERTY);
    }

    @Test
    void should_reject_null_target() throws NoSuchMethodException {
        // Given
        Method method = greetMethod();

        // When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> MethodInvoker.create(null, method))
                .withMessage("target");
    }

    @Test
    void should_reject_null_method() {
        // Given
        TestSubject target = new TestSubject();

        // When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> MethodInvoker.create(target, null))
                .withMessage("method");
    }
}
