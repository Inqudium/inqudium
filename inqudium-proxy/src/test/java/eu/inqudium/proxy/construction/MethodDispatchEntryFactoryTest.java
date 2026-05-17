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
    class ObjectMethods {

        @Test
        void should_route_equals_to_passthrough_entry_in_3_8() throws Throwable {
            // Given
            Method equals = objectMethod("equals", Object.class);
            InqPipeline pipeline = pipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createEntry(
                    equals, new MethodPlan.PassThrough(), pipeline, target, TestServiceImpl.class);

            // Then — TODO(3.10): currently PassThrough; 3.10 reroutes to ObjectMethodEntry.
            assertThat(entry.getClass().getSimpleName()).isEqualTo("PassThroughEntry");
        }

        @Test
        void should_route_to_string_to_passthrough_entry_in_3_8() throws Throwable {
            // Given
            Method toString = objectMethod("toString");
            InqPipeline pipeline = pipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createEntry(
                    toString, new MethodPlan.PassThrough(), pipeline, target, TestServiceImpl.class);

            // Then
            assertThat(entry.getClass().getSimpleName()).isEqualTo("PassThroughEntry");
        }

        @Test
        void should_route_hash_code_to_passthrough_entry_in_3_8() throws Throwable {
            // Given
            Method hashCode = objectMethod("hashCode");
            InqPipeline pipeline = pipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createEntry(
                    hashCode, new MethodPlan.PassThrough(), pipeline, target, TestServiceImpl.class);

            // Then
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
                    new eu.inqudium.proxy.handler.InqInvocationHandler(1L, () -> 1L, java.util.Map.of());
            Object result = entry.dispatch(null, handler, new Object[0]);
            assertThat(result).isEqualTo("decorated");
            assertThat(bulkhead.callCount()).isEqualTo(1);
        }

        @Test
        void should_throw_unsupported_operation_for_an_async_decorated_method() throws NoSuchMethodException {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();
            Method async = method("asyncDecorated");
            MethodPlan plan = new MethodPlan.Decorated(List.of("bh"));

            // When / Then
            assertThatThrownBy(() -> MethodDispatchEntryFactory.createEntry(
                    async, plan, pipeline, target, TestServiceImpl.class))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("CompletionStage");
        }

        @Test
        void should_include_sub_step_3_11_in_the_async_rejection_message() throws NoSuchMethodException {
            // What is to be tested?
            //   The async-rejection message must direct the user at
            //   sub-step 3.11 — the planned landing for async dispatch.
            //   Without this, the user has no signpost from the error
            //   to the roadmap.
            // How will the test case be deemed successful and why?
            //   The exception message contains the literal "3.11".
            //   This pins the documented forward-reference.
            // Why is it important to test this test case?
            //   The pointer is the only signal the user gets that
            //   async is on the roadmap rather than a permanent
            //   limitation; a regression that dropped it would degrade
            //   the user experience materially.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();
            Method async = method("asyncDecorated");
            MethodPlan plan = new MethodPlan.Decorated(List.of("bh"));

            // When / Then
            assertThatThrownBy(() -> MethodDispatchEntryFactory.createEntry(
                    async, plan, pipeline, target, TestServiceImpl.class))
                    .hasMessageContaining("3.11");
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
