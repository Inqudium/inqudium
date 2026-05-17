package eu.inqudium.proxy.introspection;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.LayerTerminal;
import eu.inqudium.pipeline.InqPipeline;
import eu.inqudium.proxy.ProxyDispatcher;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyStackAdapterTest {

    public interface OrderService {
        String getOrder(long id);

        String greet(String name);

        default String defaultGreeting() {
            return "default";
        }
    }

    public static final class DefaultOrderService implements OrderService {
        @Override
        public String getOrder(long id) {
            return "order#" + id;
        }

        @Override
        @InqBulkhead("orderBh")
        public String greet(String name) {
            return "Hello, " + name + "!";
        }
    }

    static final class FakeBulkhead implements InqDecorator<Object, Object> {

        private final String name;

        FakeBulkhead(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public InqElementType elementType() {
            return InqElementType.BULKHEAD;
        }

        @Override
        public InqEventPublisher eventPublisher() {
            return null;
        }

        @Override
        public Object execute(long chainId, long callId, Object argument,
                              LayerTerminal<Object, Object> next) {
            return next.execute(chainId, callId, argument);
        }
    }

    private static InqPipeline pipelineWithBulkhead() {
        return InqPipeline.builder().shield(new FakeBulkhead("orderBh")).build();
    }

    @Nested
    class Supports {

        @Test
        void should_return_true_for_a_proxy_produced_by_proxy_dispatcher() {
            // Given
            OrderService proxy = ProxyDispatcher.protect(
                    pipelineWithBulkhead(), OrderService.class, new DefaultOrderService());

            // When / Then
            assertThat(ProxyStackAdapter.supports(proxy)).isTrue();
        }

        @Test
        void should_return_false_for_a_proxy_with_a_foreign_invocation_handler() {
            // What is to be tested?
            //   ProxyStackAdapter.supports must guard the
            //   InvocationHandler type, not just the proxy-ness of
            //   the instance. A JDK proxy with a foreign handler
            //   must NOT report as supported.
            // How will the test case be deemed successful and why?
            //   supports() returns false for a JDK proxy whose
            //   handler is a no-op lambda.
            // Why is it important to test this test case?
            //   Pins the handler-type guard — without it, inspect()
            //   would ClassCastException on a foreign proxy.

            // Given
            OrderService foreignProxy = (OrderService) Proxy.newProxyInstance(
                    OrderService.class.getClassLoader(),
                    new Class<?>[]{OrderService.class},
                    (p, m, a) -> null);

            // When / Then
            assertThat(ProxyStackAdapter.supports(foreignProxy)).isFalse();
        }

        @Test
        void should_return_false_for_a_non_proxy_object() {
            // Given
            Object plain = new Object();

            // When / Then
            assertThat(ProxyStackAdapter.supports(plain)).isFalse();
        }

        @Test
        void should_return_false_for_null() {
            // When / Then
            assertThat(ProxyStackAdapter.supports(null)).isFalse();
        }
    }

    @Nested
    class Inspect {

        @Test
        void should_throw_illegal_argument_for_a_non_supported_instance() {
            // Given
            Object plain = new Object();

            // When / Then
            assertThatThrownBy(() -> ProxyStackAdapter.inspect(plain))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a proxy produced by ProxyDispatcher");
        }

        @Test
        void should_throw_illegal_argument_for_null() {
            // When / Then — null fails the requireNonNull guard before
            // reaching the supports() check; the NPE message names
            // the parameter.
            assertThatThrownBy(() -> ProxyStackAdapter.inspect(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("instance");
        }

        @Test
        void should_return_the_proxy_s_stack_id() {
            // Given — build two proxies; their stack ids must differ
            OrderService proxyA = ProxyDispatcher.protect(
                    pipelineWithBulkhead(), OrderService.class, new DefaultOrderService());
            OrderService proxyB = ProxyDispatcher.protect(
                    pipelineWithBulkhead(), OrderService.class, new DefaultOrderService());

            // When
            ProxyStackInfo infoA = ProxyStackAdapter.inspect(proxyA);
            ProxyStackInfo infoB = ProxyStackAdapter.inspect(proxyB);

            // Then
            assertThat(infoA.stackId()).isPositive();
            assertThat(infoB.stackId()).isPositive();
            assertThat(infoA.stackId()).isNotEqualTo(infoB.stackId());
        }

        @Test
        void should_return_the_service_interface_as_target_type() {
            // Given
            OrderService proxy = ProxyDispatcher.protect(
                    pipelineWithBulkhead(), OrderService.class, new DefaultOrderService());

            // When
            ProxyStackInfo info = ProxyStackAdapter.inspect(proxy);

            // Then
            assertThat(info.targetType()).contains(OrderService.class);
        }

        @Test
        void should_return_the_pipelines_elements_as_a_snapshot() {
            // What is to be tested?
            //   The elements list reported by inspect must reflect the
            //   pipeline's elements at construction time, in their
            //   canonical order, and be immutable.
            // How will the test case be deemed successful and why?
            //   The returned list contains exactly the single
            //   FakeBulkhead the pipeline was built with, named
            //   "orderBh". Attempting to mutate it throws.
            // Why is it important to test this test case?
            //   Pins the snapshot semantics for the adapter; a
            //   regression that returned the live pipeline list
            //   would expose later pipeline mutations to consumers
            //   that retained the DTO.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            OrderService proxy = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new DefaultOrderService());

            // When
            ProxyStackInfo info = ProxyStackAdapter.inspect(proxy);

            // Then
            assertThat(info.elements()).extracting(InqElement::name).containsExactly("orderBh");
            assertThatThrownBy(() -> info.elements().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void should_return_one_method_layers_per_service_method() {
            // Given
            OrderService proxy = ProxyDispatcher.protect(
                    pipelineWithBulkhead(), OrderService.class, new DefaultOrderService());

            // When
            ProxyStackInfo info = ProxyStackAdapter.inspect(proxy);

            // Then — three declared OrderService methods plus three
            // Object methods (equals/hashCode/toString seeded by the
            // builder per ARCHITECTURE.md §10) = six entries.
            int declared = OrderService.class.getDeclaredMethods().length;
            assertThat(info.methodLayers()).hasSize(declared + 3);
        }

        @Test
        void should_render_method_signatures_in_adr_039_format() {
            // What is to be tested?
            //   Every entry's methodSignature follows the
            //   ADR-039-canonical "Class.method(P1, P2)" format with
            //   the declaring-class simple name.
            // How will the test case be deemed successful and why?
            //   At least the `greet(String)` entry surfaces as
            //   "OrderService.greet(String)" — a concrete pin on the
            //   formatter's integration into the cache builder.
            // Why is it important to test this test case?
            //   Catches a regression that called the wrong formatter
            //   or built signatures from a different reflection
            //   accessor.

            // Given
            OrderService proxy = ProxyDispatcher.protect(
                    pipelineWithBulkhead(), OrderService.class, new DefaultOrderService());

            // When
            ProxyStackInfo info = ProxyStackAdapter.inspect(proxy);

            // Then
            assertThat(info.methodLayers())
                    .extracting(MethodLayers::methodSignature)
                    .contains("OrderService.greet(String)",
                            "OrderService.getOrder(long)",
                            "OrderService.defaultGreeting()");
        }

        @Test
        void should_carry_layer_descriptions_for_decorated_methods() {
            // Given
            OrderService proxy = ProxyDispatcher.protect(
                    pipelineWithBulkhead(), OrderService.class, new DefaultOrderService());

            // When
            ProxyStackInfo info = ProxyStackAdapter.inspect(proxy);
            MethodLayers greetLayers = info.methodLayers().stream()
                    .filter(ml -> ml.methodSignature().equals("OrderService.greet(String)"))
                    .findFirst()
                    .orElseThrow();

            // Then
            assertThat(greetLayers.layerDescriptions()).isNotEmpty();
        }

        @Test
        void should_carry_empty_layer_descriptions_for_pass_through_methods() {
            // What is to be tested?
            //   Pass-through methods (no annotations) and default
            //   methods carry an empty layerDescriptions list, since
            //   they bypass the layer chain entirely.
            // How will the test case be deemed successful and why?
            //   getOrder (pass-through) and defaultGreeting (default
            //   method) both report empty layer lists.
            // Why is it important to test this test case?
            //   Pins the contract that introspection differentiates
            //   decorated from non-decorated methods. A regression
            //   that emitted a stale list would mislead diagnostic
            //   consumers about which methods are actually protected.

            // Given
            OrderService proxy = ProxyDispatcher.protect(
                    pipelineWithBulkhead(), OrderService.class, new DefaultOrderService());

            // When
            ProxyStackInfo info = ProxyStackAdapter.inspect(proxy);
            List<MethodLayers> passThroughEntries = info.methodLayers().stream()
                    .filter(ml -> ml.methodSignature().equals("OrderService.getOrder(long)")
                            || ml.methodSignature().equals("OrderService.defaultGreeting()"))
                    .toList();

            // Then
            assertThat(passThroughEntries).hasSize(2);
            assertThat(passThroughEntries)
                    .allSatisfy(ml -> assertThat(ml.layerDescriptions()).isEmpty());
        }

        @Test
        void should_include_object_methods_in_method_layers() {
            // Given
            OrderService proxy = ProxyDispatcher.protect(
                    pipelineWithBulkhead(), OrderService.class, new DefaultOrderService());

            // When
            ProxyStackInfo info = ProxyStackAdapter.inspect(proxy);
            List<String> signatures = info.methodLayers().stream()
                    .map(MethodLayers::methodSignature)
                    .toList();

            // Then — Object methods seeded by the builder per
            // ARCHITECTURE.md §10
            assertThat(signatures).contains(
                    "Object.equals(Object)",
                    "Object.hashCode()",
                    "Object.toString()");
        }
    }
}
