package eu.inqudium.spring;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.core.element.InqElementRegistry;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InternalExecutor;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.JoinPointWrapper;
import eu.inqudium.core.pipeline.Wrapper;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for the introspection API on {@link InqShieldAspect}
 * (sub-step 6.B).
 *
 * <p>The tests pin contract properties — return types, idempotence, and
 * structural shape — rather than the runtime behaviour of the pipeline.
 * Behavioural coverage of the {@code @Around} hot path lives in
 * {@link InqShieldAspectPlainSpringTest}.</p>
 *
 * <p>This class deliberately constructs the aspect outside of a Spring
 * context. The diagnostic methods do not depend on AOP weaving — they
 * read directly from the aspect's internal cache — so a plain
 * {@code new InqShieldAspect(registry)} is sufficient and isolates these
 * tests from Spring's lifecycle.</p>
 */
@DisplayName("InqShieldAspect introspection API (6.B)")
class InqShieldAspectIntrospectionTest {

    private static final String BULKHEAD_NAME = "introspectionBh";
    private static final String CIRCUIT_BREAKER_NAME = "introspectionCb";
    private static final String RETRY_NAME = "introspectionRt";

    private InqShieldAspect aspect;

    private static Method methodOf(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Test fixture method missing: " + name, e);
        }
    }

    @BeforeEach
    void setUp() {
        // Given a registry that contains the two elements required by the
        // sub-step plan: one InqBulkhead-typed element and one further
        // resilience element. The TracingElement is a hand-built test double
        // (no mocks), enough for structural introspection because the
        // diagnostic API never executes the pipeline.
        InqElementRegistry registry = InqElementRegistry.builder()
                .register(BULKHEAD_NAME,
                        new TracingElement(BULKHEAD_NAME, InqElementType.BULKHEAD))
                .register(CIRCUIT_BREAKER_NAME,
                        new TracingElement(CIRCUIT_BREAKER_NAME,
                                InqElementType.CIRCUIT_BREAKER))
                .register(RETRY_NAME,
                        new TracingElement(RETRY_NAME, InqElementType.RETRY))
                .build();
        this.aspect = new InqShieldAspect(registry);
    }

    // =========================================================================
    // Test fixtures
    // =========================================================================

    /**
     * Test double — the same minimal pattern used in
     * {@link InqShieldAspectPlainSpringTest}. Captures the element name and
     * type that the pipeline introspection needs; the layer actions are
     * pass-throughs because the diagnostic API never executes them.
     */
    static final class TracingElement
            implements InqDecorator<Void, Object>, InqAsyncDecorator<Void, Object> {

        private final String name;
        private final InqElementType type;

        TracingElement(String name, InqElementType type) {
            this.name = name;
            this.type = type;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public InqElementType elementType() {
            return type;
        }

        @Override
        public InqEventPublisher eventPublisher() {
            return null;
        }

        @Override
        public Object execute(long chainId, long callId, Void arg,
                              InternalExecutor<Void, Object> next) {
            return next.execute(chainId, callId, arg);
        }

        @Override
        public CompletionStage<Object> executeAsync(long chainId, long callId, Void arg,
                                                    InternalAsyncExecutor<Void, Object> next) {
            return next.executeAsync(chainId, callId, arg);
        }
    }

    static class AnnotatedService {

        @InqBulkhead(BULKHEAD_NAME)
        public String onlyBulkhead(String item) {
            return "bh:" + item;
        }

        @InqCircuitBreaker(CIRCUIT_BREAKER_NAME)
        @InqBulkhead(BULKHEAD_NAME)
        @InqRetry(RETRY_NAME)
        public String fullPipeline(String item) {
            return "full:" + item;
        }

        @InqCircuitBreaker(CIRCUIT_BREAKER_NAME)
        @InqRetry(RETRY_NAME)
        public String circuitBreakerAndRetry(String item) {
            return "cbrt:" + item;
        }
    }

    // =========================================================================
    // 1. getResolvedPipeline(Method)
    // =========================================================================

    @Nested
    @DisplayName("getResolvedPipeline(Method)")
    class GetResolvedPipeline {

        @Test
        void getResolvedPipeline_returns_a_pipeline_for_a_method_carrying_an_inq_annotation() {
            // Given
            Method method = methodOf(AnnotatedService.class, "onlyBulkhead", String.class);

            // When
            ResolvedShieldPipeline resolved = aspect.getResolvedPipeline(method);

            // Then
            assertThat(resolved).isNotNull();
            assertThat(resolved.isPassthrough()).isFalse();
            assertThat(resolved.depth()).isEqualTo(1);
            assertThat(resolved.pipeline()).isNotNull();
        }

        @Test
        void getResolvedPipeline_returns_the_same_instance_on_repeated_calls() {
            // The cache must be idempotent: callers can correlate diagnostic
            // output across calls using object identity, and the cache must
            // never rebuild a pipeline that was already resolved.
            // Given
            Method method = methodOf(AnnotatedService.class, "fullPipeline", String.class);

            // When
            ResolvedShieldPipeline first = aspect.getResolvedPipeline(method);
            ResolvedShieldPipeline second = aspect.getResolvedPipeline(method);

            // Then — same instance, not just equal
            assertThat(first).isSameAs(second);
        }

        @Test
        void getResolvedPipeline_returns_distinct_instances_for_distinct_methods() {
            // Given
            Method first = methodOf(AnnotatedService.class, "onlyBulkhead", String.class);
            Method second = methodOf(AnnotatedService.class, "fullPipeline", String.class);

            // When
            ResolvedShieldPipeline firstResolved = aspect.getResolvedPipeline(first);
            ResolvedShieldPipeline secondResolved = aspect.getResolvedPipeline(second);

            // Then
            assertThat(firstResolved).isNotSameAs(secondResolved);
            assertThat(firstResolved.depth()).isEqualTo(1);
            assertThat(secondResolved.depth()).isEqualTo(3);
        }

        @Test
        void layer_names_match_the_elements_registered_for_the_method() {
            // Given — fullPipeline carries CB + BH + RT, ordered standard
            // (BULKHEAD=400, CIRCUIT_BREAKER=500, RETRY=600 — outermost first)
            Method method = methodOf(AnnotatedService.class, "fullPipeline", String.class);

            // When
            ResolvedShieldPipeline resolved = aspect.getResolvedPipeline(method);

            // Then
            assertThat(resolved.layerNames())
                    .containsExactly(BULKHEAD_NAME, CIRCUIT_BREAKER_NAME, RETRY_NAME);
        }

        @Test
        void dispatch_mode_is_sync_for_a_non_completionstage_return_type() {
            // Given
            Method method = methodOf(AnnotatedService.class, "onlyBulkhead", String.class);

            // When
            ResolvedShieldPipeline resolved = aspect.getResolvedPipeline(method);

            // Then
            assertThat(resolved.isAsync()).isFalse();
        }
    }

    // =========================================================================
    // 2. inspectPipeline(JoinPointExecutor)
    // =========================================================================

    @Nested
    @DisplayName("inspectPipeline(JoinPointExecutor) - no method")
    class InspectPipelineWithoutMethod {

        @Test
        void inspect_pipeline_without_method_returns_a_non_null_join_point_wrapper() {
            // What is to be tested: the no-method overload of inspectPipeline.
            // How will the test case be deemed successful: a non-null
            // JoinPointWrapper<Object> is returned without the executor being
            // invoked. Why is it important: the Spring aspect's pipeline is
            // per-method (driven by annotation scanning), so this overload
            // returns a passthrough rather than an unfiltered chain. The
            // contract is documented in JavaDoc; the test pins it.
            // Given
            JoinPointExecutor<Object> executor = () -> "deferred";

            // When
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(executor);

            // Then
            assertThat(chain).isNotNull();
        }

        @Test
        void returned_wrapper_has_a_positive_chain_id() {
            // Given
            JoinPointExecutor<Object> executor = () -> "value";

            // When
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(executor);

            // Then — JoinPointWrapper allocates a chainId via PipelineIds
            assertThat(chain.chainId()).isPositive();
        }

        @Test
        void returned_wrapper_implements_the_wrapper_contract() {
            // Given
            JoinPointExecutor<Object> executor = () -> "value";

            // When
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(executor);

            // Then
            assertThat(chain).isInstanceOf(Wrapper.class);
        }

        @Test
        void core_executor_is_not_invoked_during_chain_construction() throws Throwable {
            // Given
            int[] coreCalls = {0};
            JoinPointExecutor<Object> executor = () -> {
                coreCalls[0]++;
                return "value";
            };

            // When
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(executor);

            // Then — the chain exists but the core was never called
            assertThat(chain).isNotNull();
            assertThat(coreCalls[0]).isZero();
        }

        @Test
        void null_executor_is_rejected_with_an_illegal_argument_exception() {
            // When / Then
            assertThatThrownBy(() -> aspect.inspectPipeline((JoinPointExecutor<Object>) null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // 3. inspectPipeline(JoinPointExecutor, Method)
    // =========================================================================

    @Nested
    @DisplayName("inspectPipeline(JoinPointExecutor, Method)")
    class InspectPipelineWithMethod {

        @Test
        void hierarchy_string_reflects_the_layers_registered_for_the_method() {
            // Given
            Method method = methodOf(AnnotatedService.class, "fullPipeline", String.class);
            JoinPointExecutor<Object> executor = () -> "deferred";

            // When
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(executor, method);
            String hierarchy = chain.toStringHierarchy();

            // Then
            assertThat(hierarchy)
                    .contains(BULKHEAD_NAME)
                    .contains(CIRCUIT_BREAKER_NAME)
                    .contains(RETRY_NAME);
        }

        @Test
        void distinct_methods_can_produce_chains_of_different_shape() {
            // Given
            Method shallow = methodOf(AnnotatedService.class, "onlyBulkhead", String.class);
            Method deep = methodOf(AnnotatedService.class, "fullPipeline", String.class);
            JoinPointExecutor<Object> executor = () -> null;

            // When
            JoinPointWrapper<Object> shallowChain = aspect.inspectPipeline(executor, shallow);
            JoinPointWrapper<Object> deepChain = aspect.inspectPipeline(executor, deep);

            // Then — depth differs, so the hierarchy strings differ
            assertThat(shallowChain.toStringHierarchy())
                    .isNotEqualTo(deepChain.toStringHierarchy());
        }

        @Test
        void outermost_layer_description_matches_the_outermost_pipeline_element() {
            // Given — fullPipeline ordered: BH(400) -> CB(500) -> RT(600).
            // Therefore the outermost wrapper layer is the BULKHEAD.
            Method method = methodOf(AnnotatedService.class, "fullPipeline", String.class);
            JoinPointExecutor<Object> executor = () -> null;

            // When
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(executor, method);

            // Then
            assertThat(chain.layerDescription())
                    .contains("BULKHEAD")
                    .contains(BULKHEAD_NAME);
        }

        @Test
        void chain_has_a_positive_chain_id_propagated_through_the_layers() {
            // What is to be tested: the chainId-on-chain contract for the
            // method overload. How is it deemed successful: the chainId is
            // positive (allocated through PipelineIds). Why is it important:
            // event log lines use chainId for correlation; a non-positive
            // chainId indicates a broken construction path.
            // Given
            Method method = methodOf(AnnotatedService.class, "circuitBreakerAndRetry",
                    String.class);
            JoinPointExecutor<Object> executor = () -> null;

            // When
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(executor, method);

            // Then
            assertThat(chain.chainId()).isPositive();

            // And — the inner layer shares the chainId of the outermost
            // wrapper (chainId is propagated through the chain).
            JoinPointWrapper<Object> inner = chain.inner();
            assertThat(inner).isNotNull();
            assertThat(inner.chainId()).isEqualTo(chain.chainId());
        }

        @Test
        void core_executor_is_not_invoked_during_chain_construction() {
            // Given
            int[] coreCalls = {0};
            JoinPointExecutor<Object> executor = () -> {
                coreCalls[0]++;
                return "value";
            };
            Method method = methodOf(AnnotatedService.class, "fullPipeline", String.class);

            // When
            JoinPointWrapper<Object> chain = aspect.inspectPipeline(executor, method);

            // Then
            assertThat(chain).isNotNull();
            assertThat(coreCalls[0]).isZero();
        }

        @Test
        void null_arguments_are_rejected_with_an_illegal_argument_exception() {
            // Given
            Method method = methodOf(AnnotatedService.class, "onlyBulkhead", String.class);
            JoinPointExecutor<Object> executor = () -> null;

            // When / Then
            assertThatThrownBy(() -> aspect.inspectPipeline(null, method))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> aspect.inspectPipeline(executor, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // 4. ParallelToAbstractPipelineAspect
    // =========================================================================

    @Nested
    @DisplayName("Parallel to AbstractPipelineAspect API surface")
    class ParallelToAbstractPipelineAspect {

        /**
         * What is to be tested: the SHAPE of {@link InqShieldAspect}'s
         * diagnostic API mirrors {@link
         * eu.inqudium.aspect.pipeline.AbstractPipelineAspect}. The two
         * aspects have different construction shapes (Spring AOP vs
         * AspectJ CTW), so a side-by-side runtime comparison is not
         * meaningful — but the API surface that downstream code (example
         * modules, user diagnostic tooling) consumes must remain the same.
         *
         * <p>How will the test case be deemed successful: reflection finds
         * three public methods with the expected signatures and return-type
         * contracts. Why it is important: a future refactor could
         * accidentally narrow the API surface (private/package-private,
         * different return type) and break the example modules silently
         * — this test fails fast with a clear message instead.</p>
         */
        @Test
        void api_surface_matches_abstract_pipeline_aspect_in_shape() throws NoSuchMethodException {
            // Given / When
            Method getResolvedPipeline = InqShieldAspect.class.getMethod(
                    "getResolvedPipeline", Method.class);
            Method inspectPipeline = InqShieldAspect.class.getMethod(
                    "inspectPipeline", JoinPointExecutor.class);
            Method inspectPipelineWithMethod = InqShieldAspect.class.getMethod(
                    "inspectPipeline", JoinPointExecutor.class, Method.class);

            // Then — all three are public
            assertThat(Modifier.isPublic(getResolvedPipeline.getModifiers()))
                    .as("getResolvedPipeline must be public").isTrue();
            assertThat(Modifier.isPublic(inspectPipeline.getModifiers()))
                    .as("inspectPipeline(executor) must be public").isTrue();
            assertThat(Modifier.isPublic(inspectPipelineWithMethod.getModifiers()))
                    .as("inspectPipeline(executor, method) must be public").isTrue();

            // And — getResolvedPipeline returns the cached resolved type,
            // which exposes the layerNames-and-depth pair that downstream
            // tooling depends on.
            assertThat(getResolvedPipeline.getReturnType())
                    .isEqualTo(ResolvedShieldPipeline.class);
            assertThat(ResolvedShieldPipeline.class.getMethod("layerNames"))
                    .isNotNull();
            assertThat(ResolvedShieldPipeline.class.getMethod("depth"))
                    .isNotNull();

            // And — inspectPipeline overloads return the chain handle that
            // carries Wrapper introspection.
            assertThat(Wrapper.class.isAssignableFrom(inspectPipeline.getReturnType()))
                    .as("inspectPipeline(executor) must return a Wrapper subtype")
                    .isTrue();
            assertThat(Wrapper.class.isAssignableFrom(inspectPipelineWithMethod.getReturnType()))
                    .as("inspectPipeline(executor, method) must return a Wrapper subtype")
                    .isTrue();
        }

        @Test
        void resolved_pipeline_carries_the_dispatch_mode_flag_for_downstream_tooling() {
            // Documented deviation: ResolvedShieldPipeline does not expose a
            // chainId() (the Spring cache holds chain factories, not concrete
            // chains). Instead, it exposes the dispatch mode, which is the
            // piece of information example modules need when deciding whether
            // to subscribe to sync- or async-flavoured event publication.
            // Given
            Method method = methodOf(AnnotatedService.class, "onlyBulkhead", String.class);

            // When
            ResolvedShieldPipeline resolved = aspect.getResolvedPipeline(method);

            // Then
            assertThat(resolved.isAsync()).isFalse();
            assertThat(resolved.isPassthrough()).isFalse();
        }
    }
}
