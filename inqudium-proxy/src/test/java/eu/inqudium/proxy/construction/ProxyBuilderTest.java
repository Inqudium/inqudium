package eu.inqudium.proxy.construction;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.annotation.evaluator.InqAnnotationConfigurationException;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.pipeline.InqPipeline;
import eu.inqudium.proxy.entries.MethodDispatchEntry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyBuilderTest {

    // =====================================================================
    // Fixtures
    // =====================================================================

    public interface SyncService {

        String simple();

        String decorated();

        default String defaultUnoverridden() {
            return "default";
        }

        default String defaultOverridden() {
            return "default";
        }
    }

    public static class SyncServiceImpl implements SyncService {

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
    }

    public interface AsyncService {
        CompletableFuture<String> asyncDecorated();
    }

    public static class AsyncServiceImpl implements AsyncService {
        @Override
        @InqBulkhead("bh")
        public CompletableFuture<String> asyncDecorated() {
            return CompletableFuture.completedFuture("async");
        }
    }

    public interface MissingNameService {
        String oops();
    }

    public static class MissingNameImpl implements MissingNameService {
        @Override
        @InqRetry("missing")
        public String oops() {
            return "oops";
        }
    }

    public interface ParadigmMismatchService {
        String decorated();
    }

    public static class ParadigmMismatchImpl implements ParadigmMismatchService {
        @Override
        @InqBulkhead("bh")
        public String decorated() {
            return "decorated";
        }
    }

    public static class NotAnInterface {
    }

    public interface UnrelatedService {
        String anything();
    }

    public static class UnrelatedImpl implements UnrelatedService {
        @Override
        public String anything() {
            return "anything";
        }
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
    class HappyPath {

        @Test
        void should_build_entries_for_a_purely_sync_service_interface() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            SyncServiceImpl target = new SyncServiceImpl();

            // When
            Map<Method, MethodDispatchEntry> entries = ProxyBuilder.build(
                    pipeline, SyncService.class, target);

            // Then — interface declares four methods plus three Object
            // methods (equals, hashCode, toString) come via getMethods.
            // The evaluator and proxy walk SyncService.class.getMethods()
            // which excludes the Object-declared trio for an interface,
            // so the entries map should contain exactly the four
            // service methods.
            assertThat(entries).isNotEmpty();
            assertThat(entries.keySet().stream().map(Method::getName))
                    .contains("simple", "decorated",
                            "defaultUnoverridden", "defaultOverridden");
        }

        @Test
        void should_include_default_methods_in_the_entries_map() throws NoSuchMethodException {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            SyncServiceImpl target = new SyncServiceImpl();
            Method unoverridden = SyncService.class.getDeclaredMethod("defaultUnoverridden");
            Method overridden = SyncService.class.getDeclaredMethod("defaultOverridden");

            // When
            Map<Method, MethodDispatchEntry> entries = ProxyBuilder.build(
                    pipeline, SyncService.class, target);

            // Then
            assertThat(entries).containsKeys(unoverridden, overridden);
            assertThat(entries.get(unoverridden).getClass().getSimpleName())
                    .isEqualTo("DefaultMethodEntry");
            assertThat(entries.get(overridden).getClass().getSimpleName())
                    .isEqualTo("PassThroughEntry");
        }

        @Test
        void should_classify_each_method_according_to_its_plan_and_signature() throws NoSuchMethodException {
            // What is to be tested?
            //   ProxyBuilder must produce the right entry type for each
            //   classification axis: PassThrough plan with non-default
            //   method → PassThroughEntry; PassThrough plan with
            //   unoverridden default → DefaultMethodEntry; Decorated
            //   plan with sync return → SyncCacheEntry.
            // How will the test case be deemed successful and why?
            //   Each method on SyncService maps to the documented entry
            //   type. The assertion uses simple-name comparison since
            //   the entry record types are package-private.
            // Why is it important to test this test case?
            //   This is the single most important behavioural contract
            //   of the builder: every method is classified correctly.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            SyncServiceImpl target = new SyncServiceImpl();
            Method simple = SyncService.class.getDeclaredMethod("simple");
            Method decorated = SyncService.class.getDeclaredMethod("decorated");
            Method unoverridden = SyncService.class.getDeclaredMethod("defaultUnoverridden");

            // When
            Map<Method, MethodDispatchEntry> entries = ProxyBuilder.build(
                    pipeline, SyncService.class, target);

            // Then
            assertThat(entries.get(simple).getClass().getSimpleName())
                    .isEqualTo("PassThroughEntry");
            assertThat(entries.get(decorated).getClass().getSimpleName())
                    .isEqualTo("SyncCacheEntry");
            assertThat(entries.get(unoverridden).getClass().getSimpleName())
                    .isEqualTo("DefaultMethodEntry");
        }
    }

    @Nested
    class InputValidation {

        @Test
        void should_reject_a_concrete_class_as_service_interface() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();

            // When / Then
            assertThatThrownBy(() -> ProxyBuilder.build(
                    pipeline, NotAnInterface.class, new NotAnInterface()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be an interface");
        }

        @Test
        void should_reject_a_target_that_does_not_implement_the_service_interface() {
            // What is to be tested?
            //   The builder must catch the case where the target type
            //   does not implement the declared service interface, even
            //   if both arguments are otherwise well-formed. Without
            //   this check, the evaluator would proceed and produce a
            //   plan against the wrong implementation, leading to
            //   confusing downstream errors.
            // How will the test case be deemed successful and why?
            //   IllegalArgumentException with both type names in the
            //   message — the user needs to see what they passed in
            //   versus what was expected.
            // Why is it important to test this test case?
            //   Java generics erase, so a method called as
            //   build(pipeline, Foo.class, (Foo) someOtherThing) would
            //   compile but fail at the evaluator with an obscure
            //   error. This guard turns that into a clear early error.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();

            // When / Then — UnrelatedImpl does not implement SyncService
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class svc = SyncService.class;
            @SuppressWarnings("unchecked")
            Object misfit = new UnrelatedImpl();

            assertThatThrownBy(() -> ProxyBuilder.build(pipeline, svc, misfit))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not implement");
        }

        @Test
        void should_reject_null_pipeline() {
            // Given
            SyncServiceImpl target = new SyncServiceImpl();

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ProxyBuilder.build(null, SyncService.class, target))
                    .withMessage("pipeline");
        }

        @Test
        void should_reject_null_service_interface() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            SyncServiceImpl target = new SyncServiceImpl();

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ProxyBuilder.build(pipeline, null, target))
                    .withMessage("serviceInterface");
        }

        @Test
        void should_reject_null_target() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();

            // When / Then
            assertThatNullPointerException()
                    .isThrownBy(() -> ProxyBuilder.build(pipeline, SyncService.class, null))
                    .withMessage("target");
        }
    }

    @Nested
    class ErrorPropagation {

        @Test
        void should_propagate_inq_annotation_configuration_exception_from_evaluator() {
            // Given — pipeline does not carry the "missing" element name
            // that @InqRetry("missing") references on the impl
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new FakeDecorator("rt", InqElementType.RETRY))
                    .build();
            MissingNameImpl target = new MissingNameImpl();

            // When / Then
            assertThatThrownBy(() -> ProxyBuilder.build(
                    pipeline, MissingNameService.class, target))
                    .isInstanceOf(InqAnnotationConfigurationException.class);
        }

        @Test
        void should_propagate_unsupported_operation_exception_for_async_methods() {
            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            AsyncServiceImpl target = new AsyncServiceImpl();

            // When / Then
            assertThatThrownBy(() -> ProxyBuilder.build(
                    pipeline, AsyncService.class, target))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("3.11");
        }

        @Test
        void should_propagate_illegal_state_exception_for_paradigm_mismatch() {
            // Given — pipeline element does not implement InqDecorator
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new FakeElement("bh", InqElementType.BULKHEAD))
                    .build();
            ParadigmMismatchImpl target = new ParadigmMismatchImpl();

            // When / Then
            assertThatThrownBy(() -> ProxyBuilder.build(
                    pipeline, ParadigmMismatchService.class, target))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("InqDecorator");
        }
    }

    @Nested
    class MapImmutability {

        @Test
        void should_return_an_immutable_entries_map() throws NoSuchMethodException {
            // What is to be tested?
            //   The map returned by ProxyBuilder must be immutable. 3.9
            //   stores this map in PerProxyCache where any post-build
            //   mutation would break the cache's safety guarantees.
            // How will the test case be deemed successful and why?
            //   put() on the returned map raises
            //   UnsupportedOperationException. Map.copyOf produces
            //   exactly that, so a regression that returned the
            //   underlying HashMap would surface here.
            // Why is it important to test this test case?
            //   Without this guard, a future refactor could silently
            //   expose the HashMap and downstream code could mutate
            //   per-method dispatch — a real safety hazard.

            // Given
            InqPipeline pipeline = pipelineWithBulkhead();
            SyncServiceImpl target = new SyncServiceImpl();
            Method anyMethod = SyncService.class.getDeclaredMethod("simple");

            // When
            Map<Method, MethodDispatchEntry> entries = ProxyBuilder.build(
                    pipeline, SyncService.class, target);

            // Then
            assertThatThrownBy(() -> entries.put(anyMethod, entries.get(anyMethod)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
