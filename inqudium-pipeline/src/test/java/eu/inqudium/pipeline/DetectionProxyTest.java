package eu.inqudium.pipeline;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class DetectionProxyTest {

    @Test
    void should_report_proxy_module_as_absent_when_inqudium_proxy_is_not_on_classpath() {
        // Given — at sub-step 3.3 the inqudium-proxy module does not exist
        // yet (3.4 creates the skeleton; 3.9 introduces ProxyDispatcher), so
        // the probe must report the entry point as absent.

        // When / Then
        assertThat(DetectionProxy.isPresent()).isFalse();

        // Note: this assertion flips to true in sub-step 3.9, once
        // inqudium-proxy ships eu.inqudium.proxy.ProxyDispatcher and the
        // proxy module is on the test classpath transitively. The
        // assertion is therefore intentionally pinned to the current
        // state of the reactor at 3.3.
    }

    @Test
    void should_not_be_instantiable_via_its_private_constructor() throws NoSuchMethodException {
        // Given
        Constructor<DetectionProxy> constructor = DetectionProxy.class.getDeclaredConstructor();

        // When
        int modifiers = constructor.getModifiers();

        // Then — we do not attempt to instantiate the utility class; the
        // assertion is on the modifier alone.
        assertThat(Modifier.isPrivate(modifiers)).isTrue();
    }
}
