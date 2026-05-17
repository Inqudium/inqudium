package eu.inqudium.proxy.construction;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.evaluator.MethodPlan;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.pipeline.InqPipeline;
import eu.inqudium.proxy.entries.MethodDispatchEntry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MethodDispatchEntryFactoryTest {

    // =====================================================================
    // Fixtures
    // =====================================================================

    public interface TestService {

        String simple();

        String decorated();

        default String defaultUnoverridden() {
            return "d-default";
        }

        default String defaultOverridden() {
            return "d-default";
        }

        CompletableFuture<String> asyncMethod();

        CompletableFuture<String> asyncDecorated();
    }

    public static class TestServiceImpl implements TestService {

        @Override
        public String simple() {
            return "simple";
        }

        @Override
        @InqBulkhead("bh")
        public String decorated() {
            return "decorated";
        }

        @Override
        public String defaultOverridden() {
            return "overridden";
        }

        @Override
        public CompletableFuture<String> asyncMethod() {
            return CompletableFuture.completedFuture("async");
        }

        @Override
        @InqBulkhead("bh")
        public CompletableFuture<String> asyncDecorated() {
            return CompletableFuture.completedFuture("asyncDecorated");
        }
        // defaultUnoverridden inherited from interface
    }

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return TestService.class.getDeclaredMethod(name, params);
    }

    private static Method objectMethod(String name, Class<?>... params) throws NoSuchMethodException {
        return Object.class.getDeclaredMethod(name, params);
    }

    private static InqPipeline pipelineWithBulkhead() {
        return InqPipeline.builder()
                .shield(new FakeDecorator("bh", InqElementType.BULKHEAD))
                .build();
    }

    // =====================================================================
    // Tests
    // =====================================================================

    @Nested
    class NoLongerRoutesObject {

        @Test
        void should_not_be_called_with_object_class_methods_post_3_10() throws Throwable {
            // What is to be tested?
            //   Object-declared methods (equals, hashCode, toString) are
            //   no longer this factory's responsibility. Since the
            //   annotation evaluator iterates serviceInterface.getMethods()
            //   — which on an interface excludes Object methods — no
            //   evaluator plan ever names an Object method. ProxyBuilder
            //   seeds Object-method entries directly via
            //   MethodDispatchEntry.objectMethod(Kind) after the evaluator
            //   pass, never through this factory.
            // How will the test case be deemed successful and why?
            //   The factory's dead Object-class branch was removed in
            //   3.10, not replaced with a defensive guard. Calling the
            //   factory with an Object-declared method therefore reaches
            //   the regular switch on MethodPlan and produces a
            //   PassThroughEntry — confirmation that no hidden
            //   Object-method special-casing remains. The architectural
            //   contract that Object methods never enter this factory is
            //   enforced by ProxyBuilder, not by this factory.
            // Why is it important to test this test case?
            //   Pins the 3.10 cleanup: a future regression that
            //   reintroduced an Object-class branch would surface
            //   immediately, and the test documents that the factory is
            //   not the architectural responsibility for Object methods.

            // Given
            Method equals = objectMethod("equals", Object.class);
            InqPipeline pipeline = pipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createEntry(
                    equals, new MethodPlan.PassThrough(), pipeline, target, TestServiceImpl.class);

            // Then — no Object-class special-casing in the factory; the
            // method is treated like any other PassThrough-planned method
            // because by contract ProxyBuilder never calls the factory
            // with an Object-declared method.
            assertThat(entry.getClass().getSimpleName()).isEqualTo("PassThroughEntry");
        }
    }

    @Nested
    class PassThroughPlans {

        @Test
        void should_route_a_normal_method_with_passthrough_plan_to_passthrough_entry() throws Throwable {
            // Given — TestService.simple() carries no annotations
            Method simple = method("simple");
            InqPipeline pipeline = pipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createEntry(
                    simple, new MethodPlan.PassThrough(), pipeline, target, TestServiceImpl.class);

            // Then
            assertThat(entry.getClass().getSimpleName()).isEqualTo("PassThroughEntry");
            assertThat(entry.dispatch(null, null, new Object[0])).isEqualTo("simple");
        }

        @Test
        void should_route_an_unoverridden_default_method_to_default_method_entry() throws Throwable {
            // Given — TestServiceImpl does not override defaultUnoverridden
            Method defaultMethod = method("defaultUnoverridden");
            InqPipeline pipeline = pipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createEntry(
                    defaultMethod, new MethodPlan.PassThrough(), pipeline, target, TestServiceImpl.class);

            // Then
            assertThat(entry.getClass().getSimpleName()).isEqualTo("DefaultMethodEntry");
        }

        @Test
        void should_route_an_overridden_default_method_to_passthrough_entry() throws Throwable {
            // Given — TestServiceImpl overrides defaultOverridden with a
            // concrete implementation. The factory must recognise the
            // override and dispatch via the impl rather than the
            // interface default body.
            Method defaultMethod = method("defaultOverridden");
            InqPipeline pipeline = pipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createEntry(
                    defaultMethod, new MethodPlan.PassThrough(), pipeline, target, TestServiceImpl.class);

            // Then
            assertThat(entry.getClass().getSimpleName()).isEqualTo("PassThroughEntry");
            assertThat(entry.dispatch(null, null, new Object[0])).isEqualTo("overridden");
        }
    }

    @Nested
    class DecoratedPlans {

        @Test
        void should_route_a_sync_decorated_method_to_sync_cache_entry() throws Throwable {
            // What is to be tested?
            //   Given a Decorated plan referencing a sync element by
            //   name, the factory resolves the element, validates the
            //   paradigm, folds the chain, and returns a
            //   SyncCacheEntry. We pin the type, then dispatch through
            //   the entry to confirm the layer was actually inserted
            //   in front of the target call.
            // How will the test case be deemed successful and why?
            //   (a) the returned entry is a SyncCacheEntry,
            //   (b) dispatch returns the target's value,
            //   (c) the layer's call counter is incremented exactly
            //   once. Together this proves the layer is reachable on
            //   the dispatch path, not merely present in the entry
            //   metadata.
            // Why is it important to test this test case?
            //   This is the central happy-path for decorated methods
            //   in 3.8; a regression here breaks the entire 3.9
            //   end-to-end flow.

            // Given
            FakeDecorator bulkhead = new FakeDecorator("bh", InqElementType.BULKHEAD);
            InqPipeline pipeline = InqPipeline.builder().shield(bulkhead).build();
            TestServiceImpl target = new TestServiceImpl();
            Method decorated = method("decorated");
            MethodPlan plan = new MethodPlan.Decorated(List.of("bh"));

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createEntry(
                    decorated, plan, pipeline, target, TestServiceImpl.class);

            // Then
            assertThat(entry.getClass().getSimpleName()).isEqualTo("SyncCacheEntry");
            // dispatch the entry to confirm the layer is wired in.
            // SyncCacheEntry needs a real handler for stackId/callId.
            eu.inqudium.proxy.handler.InqInvocationHandler handler =
                    new eu.inqudium.proxy.handler.InqInvocationHandler(
                            1L, () -> 1L, target,
                            TestService.class, java.util.List.of(), java.util.Map.of());
            Object result = entry.dispatch(null, handler, new Object[0]);
            assertThat(result).isEqualTo("decorated");
            assertThat(bulkhead.callCount()).isEqualTo(1);
        }

    }

    @Nested
    class AsyncDispatch {

        @Test
        void should_route_an_async_decorated_method_to_async_cache_entry() throws Throwable {
            // What is to be tested?
            //   Given a Decorated plan referencing an async element by
            //   name, the factory resolves the element, validates the
            //   async paradigm, folds the chain, and returns an
            //   AsyncCacheEntry. We pin the type, then dispatch
            //   through the entry to confirm the layer was actually
            //   inserted in front of the target call.
            // How will the test case be deemed successful and why?
            //   (a) the returned entry is an AsyncCacheEntry,
            //   (b) the dispatched CompletionStage carries the
            //   target's value,
            //   (c) the layer's call counter is incremented exactly
            //   once.
            // Why is it important to test this test case?
            //   Central happy-path for async-decorated methods on
            //   the factory side — the 3.11 analogue of the
            //   sync test above. A regression breaks every async
            //   end-to-end flow.

            // Given
            FakeAsyncDecorator bulkhead = new FakeAsyncDecorator(
                    "bh", InqElementType.BULKHEAD);
            InqPipeline pipeline = InqPipeline.builder().shield(bulkhead).build();
            TestServiceImpl target = new TestServiceImpl();
            Method async = method("asyncDecorated");
            MethodPlan plan = new MethodPlan.Decorated(List.of("bh"));

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createEntry(
                    async, plan, pipeline, target, TestServiceImpl.class);

            // Then
            assertThat(entry.getClass().getSimpleName()).isEqualTo("AsyncCacheEntry");
            eu.inqudium.proxy.handler.InqInvocationHandler handler =
                    new eu.inqudium.proxy.handler.InqInvocationHandler(
                            1L, () -> 1L, target,
                            TestService.class, java.util.List.of(), java.util.Map.of());
            Object result = entry.dispatch(null, handler, new Object[0]);
            assertThat(result).isInstanceOf(java.util.concurrent.CompletionStage.class);
            assertThat(((java.util.concurrent.CompletionStage<?>) result)
                    .toCompletableFuture().get()).isEqualTo("asyncDecorated");
            assertThat(bulkhead.callCount()).isEqualTo(1);
        }

        @Test
        void should_propagate_async_paradigm_validation_failure() throws NoSuchMethodException {
            // What is to be tested?
            //   When the pipeline supplies a sync-only element for an
            //   async method, the factory must surface
            //   AsyncParadigmValidator's IllegalStateException with the
            //   InqAsyncDecorator marker in the message.
            // How will the test case be deemed successful and why?
            //   IllegalStateException whose message contains
            //   "InqAsyncDecorator". This pins that the async branch
            //   uses AsyncParadigmValidator (not SyncParadigmValidator).
            // Why is it important to test this test case?
            //   Pins the paradigm-validator routing in the factory's
            //   async branch — a regression that called the wrong
            //   validator would surface as a misleading error message
            //   (talking about InqDecorator instead of
            //   InqAsyncDecorator).

            // Given — pipeline carries a sync-only decorator for the
            // async method's element.
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new FakeDecorator("bh", InqElementType.BULKHEAD))
                    .build();
            TestServiceImpl target = new TestServiceImpl();
            Method async = method("asyncDecorated");
            MethodPlan plan = new MethodPlan.Decorated(List.of("bh"));

            // When / Then
            assertThatThrownBy(() -> MethodDispatchEntryFactory.createEntry(
                    async, plan, pipeline, target, TestServiceImpl.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("InqAsyncDecorator");
        }

    }

    @Nested
    class Validation {

        @Test
        void should_propagate_paradigm_validation_failure() throws NoSuchMethodException {
            // Given — pipeline contains an element that does NOT
            // implement InqDecorator. The factory must surface the
            // validator's IllegalStateException.
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new FakeElement("bh", InqElementType.BULKHEAD))
                    .build();
            TestServiceImpl target = new TestServiceImpl();
            Method decorated = method("decorated");
            MethodPlan plan = new MethodPlan.Decorated(List.of("bh"));

            // When / Then
            assertThatThrownBy(() -> MethodDispatchEntryFactory.createEntry(
                    decorated, plan, pipeline, target, TestServiceImpl.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("InqDecorator");
        }
    }
}
