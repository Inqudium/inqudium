package eu.inqudium.proxy;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.LayerTerminal;
import eu.inqudium.imperative.core.pipeline.AsyncLayerTerminal;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.pipeline.InqPipeline;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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

    /**
     * Order-service implementation with content-based equals/hashCode
     * so two distinct instances with the same id compare equal. Used
     * by ObjectMethods tests that need real-target equality semantics
     * (not identity).
     */
    public static final class EquatableOrderService implements OrderService {

        private final int id;

        public EquatableOrderService(int id) {
            this.id = id;
        }

        @Override
        public String getOrder(long orderId) {
            return "order#" + orderId;
        }

        @Override
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        @Override
        public String throwsRuntime() {
            throw new IllegalStateException("runtime boom");
        }

        @Override
        public String throwsChecked() {
            throw new IllegalStateException("not used");
        }

        @Override
        public String throwsUndeclared() {
            return null;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof EquatableOrderService other && other.id == this.id;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(id);
        }

        @Override
        public String toString() {
            return "EquatableOrderService#" + id;
        }
    }

    public interface AsyncService {
        CompletableFuture<String> asyncCall();

        CompletableFuture<String> asyncBoom();
    }

    public static final class AsyncServiceImpl implements AsyncService {
        @Override
        @InqBulkhead("orderBh")
        public CompletableFuture<String> asyncCall() {
            return CompletableFuture.completedFuture("done");
        }

        @Override
        @InqBulkhead("orderBh")
        public CompletableFuture<String> asyncBoom() {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("async boom"));
            return failed;
        }
    }

    public static final class SyncThrowAsyncServiceImpl implements AsyncService {
        @Override
        @InqBulkhead("orderBh")
        public CompletableFuture<String> asyncCall() {
            throw new IllegalStateException("sync throw from async method");
        }

        @Override
        public CompletableFuture<String> asyncBoom() {
            return CompletableFuture.completedFuture("never");
        }
    }

    /**
     * Async-paradigm decorator fixture: transparent pass-through that
     * bumps a counter. Mirrors {@link FakeBulkhead} on the async side.
     */
    static final class FakeAsyncBulkhead implements InqAsyncDecorator<Object, Object> {

        private final String name;
        private final AtomicInteger callCount = new AtomicInteger();

        FakeAsyncBulkhead(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public eu.inqudium.core.element.InqElementType elementType() {
            return eu.inqudium.core.element.InqElementType.BULKHEAD;
        }

        @Override
        public InqEventPublisher eventPublisher() {
            return null;
        }

        @Override
        public CompletionStage<Object> executeAsync(long chainId, long callId, Object argument,
                                                    AsyncLayerTerminal<Object, Object> next) {
            callCount.incrementAndGet();
            return next.executeAsync(chainId, callId, argument);
        }

        int callCount() {
            return callCount.get();
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

    private static InqPipeline pipelineWithAsyncBulkhead() {
        return InqPipeline.builder()
                .shield(new FakeAsyncBulkhead("orderBh"))
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
        void should_consider_two_proxies_with_equal_targets_equal() {
            // What is to be tested?
            //   ARCHITECTURE.md §10 equals semantics: two proxies whose
            //   real targets are equal must themselves be equal,
            //   regardless of proxy-instance identity.
            // How will the test case be deemed successful and why?
            //   Two equal EquatableTarget instances wrapped in separate
            //   proxies report equal via both Object.equals invocations.
            // Why is it important to test this test case?
            //   This is the central proxy-equality contract — collections
            //   and caches keyed on the proxy would behave incorrectly if
            //   broken.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            OrderService proxyA = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new EquatableOrderService(7));
            OrderService proxyB = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new EquatableOrderService(7));

            // When / Then
            assertThat(proxyA.equals(proxyB)).isTrue();
        }

        @Test
        void should_consider_two_proxies_with_distinct_targets_unequal() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            OrderService proxyA = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new EquatableOrderService(7));
            OrderService proxyB = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new EquatableOrderService(8));

            // When / Then
            assertThat(proxyA.equals(proxyB)).isFalse();
        }

        @Test
        void should_consider_a_proxy_unequal_to_a_non_proxy() {
            // What is to be tested?
            //   Comparing a proxy with a plain object (including the real
            //   target itself) must return false because the "other"
            //   side is not a JDK proxy. Per ARCHITECTURE.md §10 the
            //   equals semantics are intentionally proxy-symmetric: only
            //   two proxies can ever compare equal.
            // How will the test case be deemed successful and why?
            //   proxy.equals(target) is false even though the proxy's
            //   real target is the same instance.
            // Why is it important to test this test case?
            //   This is the surprising-but-correct corollary of the §10
            //   contract; pinning it stops regressions toward
            //   "delegate-to-target-when-non-proxy" semantics that would
            //   break symmetry.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            DefaultOrderService target = new DefaultOrderService();
            OrderService proxy = ProxyDispatcher.protect(
                    pipeline, OrderService.class, target);

            // When / Then
            assertThat(proxy.equals(target)).isFalse();
            assertThat(proxy.equals("not a proxy")).isFalse();
        }

        @Test
        void should_consider_a_proxy_unequal_to_a_proxy_with_a_different_handler_type() {
            // What is to be tested?
            //   The other side must be a JDK proxy whose InvocationHandler
            //   is an InqInvocationHandler. A JDK proxy with a foreign
            //   handler must not compare equal even if it implements the
            //   same interface.
            // How will the test case be deemed successful and why?
            //   proxy.equals(foreignProxy) returns false.
            // Why is it important to test this test case?
            //   Pins the handler-type guard — without it, equals would
            //   either NPE on a missing realTarget accessor or accept
            //   structurally-unrelated proxies as equal.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            OrderService proxy = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new DefaultOrderService());
            OrderService foreignProxy = (OrderService) java.lang.reflect.Proxy.newProxyInstance(
                    OrderService.class.getClassLoader(),
                    new Class<?>[]{OrderService.class},
                    (p, m, a) -> null);

            // When / Then
            assertThat(proxy.equals(foreignProxy)).isFalse();
        }

        @Test
        void should_consider_a_proxy_unequal_to_null() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            OrderService proxy = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new DefaultOrderService());

            // When / Then
            assertThat(proxy.equals(null)).isFalse();
        }

        @Test
        void should_be_equals_symmetric_for_equal_targets() {
            // What is to be tested?
            //   For two proxies with equal targets, equality is
            //   symmetric: proxyA.equals(proxyB) iff proxyB.equals(proxyA).
            //   This is the most-likely-to-be-subtly-wrong property of
            //   the §10 contract.
            // How will the test case be deemed successful and why?
            //   Both directions of the equality check return true.
            // Why is it important to test this test case?
            //   Object.equals's contract mandates symmetry; collections
            //   relying on equals (HashMap, HashSet) break in subtle
            //   ways when symmetry is violated.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            OrderService proxyA = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new EquatableOrderService(11));
            OrderService proxyB = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new EquatableOrderService(11));

            // When / Then
            assertThat(proxyA.equals(proxyB)).isEqualTo(proxyB.equals(proxyA));
            assertThat(proxyA.equals(proxyB)).isTrue();
        }

        @Test
        void should_be_equals_symmetric_for_unequal_targets() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            OrderService proxyA = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new EquatableOrderService(11));
            OrderService proxyB = ProxyDispatcher.protect(
                    pipeline, OrderService.class, new EquatableOrderService(12));

            // When / Then
            assertThat(proxyA.equals(proxyB)).isEqualTo(proxyB.equals(proxyA));
            assertThat(proxyA.equals(proxyB)).isFalse();
        }

        @Test
        void should_delegate_hash_code_to_the_real_target() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            EquatableOrderService target = new EquatableOrderService(42);
            OrderService proxy = ProxyDispatcher.protect(
                    pipeline, OrderService.class, target);

            // When / Then
            assertThat(proxy.hashCode()).isEqualTo(target.hashCode());
        }

        @Test
        void should_render_to_string_as_proxy_class_name_plus_target_string() {
            // What is to be tested?
            //   toString is descriptive — the proxy's class simple name
            //   followed by the real target's toString in square brackets.
            // How will the test case be deemed successful and why?
            //   proxy.toString() equals "<ProxySimpleName>[<target>]".
            //   The proxy's class simple name is JDK-generated and looks
            //   like "$Proxy12"; we assert structural shape rather than
            //   pinning the exact generated name.
            // Why is it important to test this test case?
            //   Documents the toString contract from §10 — readable
            //   diagnostics, never the raw JDK default that hides what
            //   the proxy wraps.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            EquatableOrderService target = new EquatableOrderService(99);
            OrderService proxy = ProxyDispatcher.protect(
                    pipeline, OrderService.class, target);

            // When
            String rendered = proxy.toString();

            // Then
            assertThat(rendered).isEqualTo(
                    proxy.getClass().getSimpleName() + "[" + target + "]");
            assertThat(rendered).contains(target.toString());
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
    class AsyncDispatch {

        @Test
        void should_build_a_proxy_for_a_service_interface_with_async_methods() {
            // What is to be tested?
            //   With inqudium-imperative on the classpath and an
            //   async-paradigm element in the pipeline, ProxyDispatcher
            //   must construct a working proxy for a service interface
            //   that declares CompletionStage-returning methods. Pins
            //   the 3.11 transition away from
            //   UnsupportedOperationException.
            // How will the test case be deemed successful and why?
            //   protect(...) returns a non-null proxy instance assignable
            //   to AsyncService. No exception is thrown.
            // Why is it important to test this test case?
            //   The construction-time happy-path for async — every
            //   following test depends on this working.

            // Given
            InqPipeline pipeline = pipelineWithAsyncBulkhead();
            AsyncServiceImpl target = new AsyncServiceImpl();

            // When
            AsyncService proxy = ProxyDispatcher.protect(
                    pipeline, AsyncService.class, target);

            // Then
            assertThat(proxy).isNotNull();
        }

        @Test
        void should_execute_an_async_method_via_the_decorated_chain() throws Exception {
            // What is to be tested?
            //   The async method routes through the AsyncCacheEntry,
            //   the layer fires, and the returned stage carries the
            //   target's value. End-to-end exercise of the async
            //   dispatch path.
            // How will the test case be deemed successful and why?
            //   The returned CompletionStage completes with "done" and
            //   the bulkhead's call counter is incremented once.
            // Why is it important to test this test case?
            //   This is the central async happy-path on the dispatcher
            //   level — equivalent to the SyncDispatch.
            //   should_route_an_annotated_method test.

            // Given
            FakeAsyncBulkhead bulkhead = new FakeAsyncBulkhead("orderBh");
            InqPipeline pipeline = InqPipeline.builder().shield(bulkhead).build();
            AsyncServiceImpl target = new AsyncServiceImpl();
            AsyncService proxy = ProxyDispatcher.protect(
                    pipeline, AsyncService.class, target);

            // When
            String result = proxy.asyncCall().get();

            // Then
            assertThat(result).isEqualTo("done");
            assertThat(bulkhead.callCount()).isEqualTo(1);
        }

        @Test
        void should_complete_the_returned_stage_exceptionally_when_the_target_s_async_op_fails() {
            // What is to be tested?
            //   When the target returns an exceptionally-completed
            //   stage, the proxy's caller observes that failure inside
            //   the stage (not as a synchronous throw). The async
            //   error channel must stay async.
            // How will the test case be deemed successful and why?
            //   The returned CompletableFuture is completed
            //   exceptionally; calling .get() throws ExecutionException
            //   whose cause is the target's IllegalStateException.
            // Why is it important to test this test case?
            //   Pins the two-channel separation (sync layer faults vs
            //   async stage failures) for the end-to-end dispatch.

            // Given
            InqPipeline pipeline = pipelineWithAsyncBulkhead();
            AsyncServiceImpl target = new AsyncServiceImpl();
            AsyncService proxy = ProxyDispatcher.protect(
                    pipeline, AsyncService.class, target);

            // When
            CompletableFuture<String> result = proxy.asyncBoom();

            // Then
            assertThat(result.isCompletedExceptionally()).isTrue();
            assertThatThrownBy(result::get)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("async boom");
        }

        @Test
        void should_wrap_a_sync_target_throw_in_a_failed_future() throws Exception {
            // What is to be tested?
            //   When the async-method body throws synchronously before
            //   producing a stage, the caller still observes a
            //   CompletionStage — the throwable is wrapped in
            //   CompletableFuture.failedFuture by the folder's
            //   terminal. The contract is that an async method always
            //   returns a stage; sync-throws are surfaced as failed
            //   stages, not as direct throws.
            // How will the test case be deemed successful and why?
            //   proxy.asyncCall() returns a non-null stage that is
            //   completed exceptionally with the original throwable.
            // Why is it important to test this test case?
            //   This is the most user-visible aspect of the
            //   single-channel async contract; a regression that let
            //   the sync-throw escape would break every
            //   stage.thenApply / .exceptionally chain at the caller.

            // Given
            InqPipeline pipeline = pipelineWithAsyncBulkhead();
            SyncThrowAsyncServiceImpl target = new SyncThrowAsyncServiceImpl();
            AsyncService proxy = ProxyDispatcher.protect(
                    pipeline, AsyncService.class, target);

            // When
            CompletableFuture<String> result = proxy.asyncCall();

            // Then
            assertThat(result).isNotNull();
            assertThat(result.isCompletedExceptionally()).isTrue();
            assertThatThrownBy(result::get)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sync throw from async method");
        }
    }
}
