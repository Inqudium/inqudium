package eu.inqudium.proxy;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.LayerTerminal;
import eu.inqudium.imperative.core.pipeline.AsyncLayerTerminal;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.pipeline.InqPipeline;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end test of the public
 * {@link InqPipeline#protect(Class, Object)} API. Lives in
 * {@code inqudium-proxy}'s test sources so that
 * {@code inqudium-proxy} is on the classpath and the
 * {@code ProxyDelegation} reflection bridge in
 * {@code inqudium-pipeline} resolves successfully.
 *
 * <p>The companion test in
 * {@code inqudium-pipeline}'s test sources
 * ({@code InqPipelineProtectWithoutProxyTest}) exercises the
 * proxy-absent branch.</p>
 */
class EndToEndPipelineProtectTest {

    public interface OrderService {
        String greet(String name);

        String throwsRuntime();
    }

    public static final class DefaultOrderService implements OrderService {
        @Override
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        @Override
        public String throwsRuntime() {
            throw new IllegalStateException("runtime boom");
        }
    }

    public interface AsyncOrderService {
        CompletableFuture<String> fetchOrderAsync(long id);
    }

    public static final class DefaultAsyncOrderService implements AsyncOrderService {
        @Override
        public CompletableFuture<String> fetchOrderAsync(long id) {
            return CompletableFuture.completedFuture("Order#" + id);
        }
    }

    static final class FakeAsyncBulkhead implements InqAsyncDecorator<Object, Object> {

        private final String name;

        FakeAsyncBulkhead(String name) {
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
        public CompletionStage<Object> executeAsync(long chainId, long callId, Object argument,
                                                    AsyncLayerTerminal<Object, Object> next) {
            return next.executeAsync(chainId, callId, argument);
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

    @Test
    void should_build_a_working_proxy_via_pipeline_protect() {
        // Given
        InqPipeline pipeline = InqPipeline.builder()
                .shield(new FakeBulkhead("orderBh"))
                .build();

        // When
        OrderService proxy = pipeline.protect(
                OrderService.class, new DefaultOrderService());

        // Then — proxy is functional through the reflection bridge
        assertThat(proxy.greet("World")).isEqualTo("Hello, World!");
    }

    @Test
    void should_construct_independent_proxies_with_distinct_stack_ids() {
        // What is to be tested?
        //   Two protect(...) calls produce two distinct proxies. The
        //   ProxyDispatcher pulls a fresh stack ID per call, so the
        //   two proxies are independent — calls on one do not affect
        //   counters on the other.
        // How will the test case be deemed successful and why?
        //   Both proxies behave correctly and are not the same object
        //   reference. Identity divergence is the simplest observable
        //   evidence that distinct stack IDs were allocated.
        // Why is it important to test this test case?
        //   Pins the per-call construction contract — a regression
        //   that cached and re-used a single proxy would break
        //   the stable-ID assumption that introspection (3.12) relies
        //   on.

        // Given
        InqPipeline pipeline = InqPipeline.builder()
                .shield(new FakeBulkhead("orderBh"))
                .build();

        // When
        OrderService firstProxy = pipeline.protect(
                OrderService.class, new DefaultOrderService());
        OrderService secondProxy = pipeline.protect(
                OrderService.class, new DefaultOrderService());

        // Then
        assertThat(firstProxy).isNotSameAs(secondProxy);
        assertThat(firstProxy.greet("A")).isEqualTo("Hello, A!");
        assertThat(secondProxy.greet("B")).isEqualTo("Hello, B!");
    }

    @Test
    void should_build_a_working_async_proxy_via_pipeline_protect() throws Exception {
        // What is to be tested?
        //   End-to-end exercise of the async dispatch path through
        //   pipeline.protect — from the InqPipeline.protect interface
        //   method, through the ProxyDelegation reflection bridge,
        //   into the proxy's async branch, and back out via the
        //   returned CompletionStage. Sub-step 3.11's full
        //   integration point.
        // How will the test case be deemed successful and why?
        //   proxy.fetchOrderAsync(42).join() returns "Order#42". A
        //   regression anywhere along that chain breaks the test.
        // Why is it important to test this test case?
        //   Top-level smoke test that ties everything together;
        //   without it, the new async path could be wired backwards
        //   and unit tests would still pass.

        // Given
        InqPipeline pipeline = InqPipeline.builder()
                .shield(new FakeAsyncBulkhead("orderBh"))
                .build();

        // When
        AsyncOrderService proxy = pipeline.protect(
                AsyncOrderService.class, new DefaultAsyncOrderService());
        String result = proxy.fetchOrderAsync(42L).get();

        // Then
        assertThat(result).isEqualTo("Order#42");
    }

    @Test
    void should_unwrap_runtime_exceptions_from_the_reflection_bridge() {
        // What is to be tested?
        //   When the underlying ProxyDispatcher (or the dispatched
        //   call) throws a RuntimeException, the ProxyDelegation
        //   reflection bridge must unwrap the
        //   InvocationTargetException so callers see the original
        //   exception type — not a reflective wrapper.
        // How will the test case be deemed successful and why?
        //   proxy.throwsRuntime() raises IllegalStateException
        //   ("runtime boom"), not InvocationTargetException or
        //   any wrapper around it.
        // Why is it important to test this test case?
        //   The reflection bridge is invisible to callers; if it
        //   leaked InvocationTargetException, every caller would
        //   need to write awkward unwrapping code. This pins the
        //   "as if not reflective" promise of ProxyDelegation.

        // Given
        InqPipeline pipeline = InqPipeline.builder()
                .shield(new FakeBulkhead("orderBh"))
                .build();
        OrderService proxy = pipeline.protect(
                OrderService.class, new DefaultOrderService());

        // When / Then
        assertThatThrownBy(proxy::throwsRuntime)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("runtime boom");
    }
}
