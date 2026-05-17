package eu.inqudium.proxy;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.LayerTerminal;
import eu.inqudium.pipeline.InqPipeline;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyDispatcherTest {

    // =====================================================================
    // Fixtures
    // =====================================================================

    public interface OrderService {

        String getOrder(long id);

        String greet(String name);

        default String defaultGreeting() {
            return "default";
        }

        String throwsRuntime();

        String throwsChecked() throws IOException;

        String throwsUndeclared();
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

        @Override
        public String throwsRuntime() {
            throw new IllegalStateException("runtime boom");
        }

        @Override
        public String throwsChecked() throws IOException {
            throw new IOException("declared boom");
        }

        @Override
        public String throwsUndeclared() {
            sneakyThrow(new IOException("undeclared boom"));
            return null;
        }

        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
            throw (E) t;
        }
    }

    public interface AsyncService {
        CompletableFuture<String> asyncCall();
    }

    public static final class AsyncServiceImpl implements AsyncService {
        @Override
        @InqBulkhead("orderBh")
        public CompletableFuture<String> asyncCall() {
            return CompletableFuture.completedFuture("done");
        }
    }

    public static class NotAnInterface {
    }

    public interface UnrelatedService {
        String anything();
    }

    public static final class UnrelatedImpl implements UnrelatedService {
        @Override
        public String anything() {
            return "anything";
        }
    }

    /**
     * Minimal sync-paradigm decorator fixture: implements InqDecorator
     * with a transparent pass-through that bumps a counter. Used by
     * the SyncDispatch tests to verify a layer was actually folded
     * into the chain.
     */
    static final class FakeBulkhead implements InqDecorator<Object, Object> {

        private final String name;
        private final AtomicInteger callCount = new AtomicInteger();

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
            callCount.incrementAndGet();
            return next.execute(chainId, callId, argument);
        }

        int callCount() {
            return callCount.get();
        }
    }

    private static InqPipeline pipelineWithBulkhead() {
        return InqPipeline.builder()
                .shield(new FakeBulkhead("orderBh"))
                .build();
    }

    // =====================================================================
    // Tests
    // =====================================================================

    @Nested
    class SyncDispatch {

        @Test
        void should_route_an_unannotated_method_through_passthrough() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            DefaultOrderService target = new DefaultOrderService();
            OrderService proxy = ProxyDispatcher.protect(pipeline, OrderService.class, target);

            // When
            String result = proxy.getOrder(42L);

            // Then
            assertThat(result).isEqualTo("order#42");
        }

        @Test
        void should_route_an_annotated_method_through_the_decorated_chain() {
            // Given
            FakeBulkhead bulkhead = new FakeBulkhead("orderBh");
            InqPipeline pipeline = InqPipeline.builder().shield(bulkhead).build();
            DefaultOrderService target = new DefaultOrderService();
            OrderService proxy = ProxyDispatcher.protect(pipeline, OrderService.class, target);

            // When
            String result = proxy.greet("World");

            // Then — both the target ran and the decorator was threaded
            assertThat(result).isEqualTo("Hello, World!");
            assertThat(bulkhead.callCount()).isEqualTo(1);
        }

        @Test
        void should_route_a_default_method_through_invoke_default() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            OrderService proxy = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new DefaultOrderService());

            // When
            String result = proxy.defaultGreeting();

            // Then — the interface's default body wins
            assertThat(result).isEqualTo("default");
        }

        @Test
        void should_return_the_target_s_value_when_no_layers_modify_it() {
            // What is to be tested?
            //   Unmodified pass-through of return values through the
            //   decorated chain — the FakeBulkhead is a transparent
            //   pass-through, so the proxy's return value must equal
            //   what the target returns.
            // How will the test case be deemed successful and why?
            //   proxy.greet("World") equals target.greet("World").
            // Why is it important to test this test case?
            //   Pins the no-layer-side-effect property: a transparent
            //   decorator must not alter return values. Regressions
            //   here would mean the dispatch path is mutating
            //   responses, breaking the resilience-layer contract.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            DefaultOrderService target = new DefaultOrderService();
            OrderService proxy = ProxyDispatcher.protect(pipeline, OrderService.class, target);

            // When
            String fromProxy = proxy.greet("World");
            String fromTarget = target.greet("World");

            // Then
            assertThat(fromProxy).isEqualTo(fromTarget);
        }
    }

    @Nested
    class ObjectMethods {

        @Test
        void should_pass_equals_through_to_the_target_temporarily() {
            // What is to be tested?
            //   At 3.9, Object.equals on a proxy routes via the
            //   temporary PassThrough entry. 3.10 will replace this
            //   with proxy-aware equals semantics; until then, the
            //   call lands on the target.
            // How will the test case be deemed successful and why?
            //   target.equals(target) is true and target.equals(other)
            //   is false; the proxy mirrors those answers because the
            //   call passes through.
            // Why is it important to test this test case?
            //   Documents the transitional state so the 3.10 change
            //   has a clear "before" baseline.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            DefaultOrderService target = new DefaultOrderService();
            OrderService proxy = ProxyDispatcher.protect(pipeline, OrderService.class, target);

            // When / Then — target.equals(target) is identity-true
            assertThat(proxy.equals(target)).isTrue();
            assertThat(proxy.equals("not the target")).isFalse();
        }

        @Test
        void should_pass_to_string_through_to_the_target_temporarily() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            DefaultOrderService target = new DefaultOrderService();
            OrderService proxy = ProxyDispatcher.protect(pipeline, OrderService.class, target);

            // When
            String s = proxy.toString();

            // Then — the target's default Object.toString is returned
            assertThat(s).isEqualTo(target.toString());
        }

        @Test
        void should_pass_hash_code_through_to_the_target_temporarily() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            DefaultOrderService target = new DefaultOrderService();
            OrderService proxy = ProxyDispatcher.protect(pipeline, OrderService.class, target);

            // When
            int proxyHash = proxy.hashCode();

            // Then
            assertThat(proxyHash).isEqualTo(target.hashCode());
        }
    }

    @Nested
    class ExceptionHandling {

        @Test
        void should_propagate_a_runtime_exception_from_the_target() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            OrderService proxy = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new DefaultOrderService());

            // When / Then
            assertThatThrownBy(proxy::throwsRuntime)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("runtime boom");
        }

        @Test
        void should_propagate_a_declared_checked_exception_from_the_target() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            OrderService proxy = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new DefaultOrderService());

            // When / Then
            assertThatThrownBy(proxy::throwsChecked)
                    .isExactlyInstanceOf(IOException.class)
                    .hasMessage("declared boom");
        }

        @Test
        void should_wrap_an_undeclared_checked_exception_in_inq_undeclared() {
            // What is to be tested?
            //   ADR-035 §10 contract: a checked exception not declared
            //   by the service method's signature must be wrapped in
            //   InqUndeclaredCheckedException before being raised out
            //   of the proxy.
            // How will the test case be deemed successful and why?
            //   The thrown exception is exactly
            //   InqUndeclaredCheckedException, with the original
            //   IOException as cause.
            // Why is it important to test this test case?
            //   This is the single most user-facing contract of the
            //   classifier; regressions here change observable
            //   exception types in client code.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            OrderService proxy = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new DefaultOrderService());

            // When / Then
            assertThatThrownBy(proxy::throwsUndeclared)
                    .isExactlyInstanceOf(InqUndeclaredCheckedException.class)
                    .hasCauseExactlyInstanceOf(IOException.class);
        }
    }

    @Nested
    class InputValidation {

        @Test
        void should_reject_a_concrete_class_as_service_interface() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();

            // When / Then
            assertThatThrownBy(() -> ProxyDispatcher.protect(
                    pipeline, NotAnInterface.class, new NotAnInterface()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be an interface");
        }

        @Test
        void should_reject_a_target_that_does_not_implement_the_service_interface() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();

            // When / Then — UnrelatedImpl does not implement OrderService
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class svc = OrderService.class;
            @SuppressWarnings("unchecked")
            Object misfit = new UnrelatedImpl();

            assertThatThrownBy(() -> ProxyDispatcher.protect(pipeline, svc, misfit))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not implement");
        }

        @Test
        void should_reject_null_pipeline() {
            // Given
            DefaultOrderService target = new DefaultOrderService();

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ProxyDispatcher.protect(null, OrderService.class, target))
                    .withMessage("pipeline");
        }

        @Test
        void should_reject_null_service_interface() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ProxyDispatcher.protect(pipeline, null, new DefaultOrderService()))
                    .withMessage("serviceInterface");
        }

        @Test
        void should_reject_null_target() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ProxyDispatcher.protect(pipeline, OrderService.class, null))
                    .withMessage("target");
        }
    }

    @Nested
    class AsyncRejection {

        @Test
        void should_throw_unsupported_operation_for_an_async_method_during_construction() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            AsyncServiceImpl target = new AsyncServiceImpl();

            // When / Then — construction phase rejects async methods
            // until sub-step 3.11 lands the async path.
            assertThatThrownBy(() -> ProxyDispatcher.protect(pipeline, AsyncService.class, target))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("3.11");
        }
    }
}
