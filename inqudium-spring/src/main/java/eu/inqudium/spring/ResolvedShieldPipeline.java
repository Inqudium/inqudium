package eu.inqudium.spring;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * The cached, pre-composed Spring-AOP pipeline for a single annotated method.
 *
 * <p>Spring-AOP equivalent of {@code ResolvedPipeline} (see
 * {@code inqudium-aspect}). Holds the chain factories that
 * {@link InqShieldAspect} uses on the hot path, plus the underlying
 * {@link InqPipeline} composition for diagnostics.</p>
 *
 * <h2>Structural deviation from {@code ResolvedPipeline}</h2>
 * <p>The Spring aspect resolves its pipeline from
 * {@link eu.inqudium.annotation.support.PipelineFactory} rather than from
 * {@code AspectLayerProvider} instances. The
 * artefact stored in the cache is therefore an {@link InqPipeline} (plus a
 * pre-composed chain factory per dispatch mode), not the layer-action array
 * that {@code ResolvedPipeline} carries. As a consequence:</p>
 * <ul>
 *   <li>This type does not expose a {@code chainId()}. The Spring aspect's
 *       hot path produces a fresh {@link eu.inqudium.core.pipeline.JoinPointWrapper}
 *       chain per invocation (with its own chain identifier from
 *       {@link eu.inqudium.core.pipeline.PipelineIds}); there is no stable
 *       per-method chain identifier in the cache. Adding one purely for
 *       diagnostics would constitute a forbidden cache-shape change.</li>
 *   <li>{@link #layerNames()} is derived from the underlying
 *       {@link InqPipeline#elements()} rather than maintained as a separate
 *       array.</li>
 * </ul>
 *
 * <h2>Hot-path / cold-path split</h2>
 * <p>The chain factories are package-private and consumed only by
 * {@code InqShieldAspect.around}. All public accessors on this type are
 * cold-path diagnostics: layer-name introspection, depth, dispatch-mode
 * inspection, and hierarchy rendering.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Instances are immutable after construction and safe for concurrent use.</p>
 *
 * @since 0.9.0
 */
public final class ResolvedShieldPipeline {

    /**
     * Sentinel for methods matched by the pointcut but with no effective
     * Inqudium annotations after the {@code TYPE+METHOD} merge. The hot path
     * short-circuits to {@code pjp.proceed()} when this sentinel is found.
     */
    static final ResolvedShieldPipeline PASSTHROUGH =
            new ResolvedShieldPipeline(false, true, null, null, null);

    private final boolean async;
    private final boolean passthrough;
    private final InqPipeline pipeline;
    private final Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>>
            syncFactory;
    private final Function<JoinPointExecutor<CompletionStage<Object>>,
            JoinPointExecutor<CompletionStage<Object>>> asyncFactory;

    private ResolvedShieldPipeline(
            boolean async,
            boolean passthrough,
            InqPipeline pipeline,
            Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> syncFactory,
            Function<JoinPointExecutor<CompletionStage<Object>>,
                    JoinPointExecutor<CompletionStage<Object>>> asyncFactory) {
        this.async = async;
        this.passthrough = passthrough;
        this.pipeline = pipeline;
        this.syncFactory = syncFactory;
        this.asyncFactory = asyncFactory;
    }

    // ======================== Factories ========================

    static ResolvedShieldPipeline sync(
            InqPipeline pipeline,
            Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> factory) {
        return new ResolvedShieldPipeline(false, false, pipeline, factory, null);
    }

    static ResolvedShieldPipeline async(
            InqPipeline pipeline,
            Function<JoinPointExecutor<CompletionStage<Object>>,
                    JoinPointExecutor<CompletionStage<Object>>> factory) {
        return new ResolvedShieldPipeline(true, false, pipeline, null, factory);
    }

    // ======================== Public diagnostics ========================

    /**
     * Returns the underlying {@link InqPipeline} composition.
     *
     * <p>Exposes the immutable, ordered element list and the
     * {@link eu.inqudium.core.pipeline.PipelineOrdering} that produced it.
     * The returned pipeline is the one that was used to build the cached
     * chain factory — modifying its element list is not possible because
     * {@code InqPipeline.elements()} is immutable.</p>
     *
     * @return the cached pipeline, or {@code null} if this entry is the
     *         {@linkplain #isPassthrough() passthrough} sentinel
     */
    public InqPipeline pipeline() {
        return pipeline;
    }

    /**
     * Returns the layer names for this pipeline in execution order
     * (outermost first).
     *
     * <p>Each entry is the {@link InqElement#name()} of the corresponding
     * cached element. For a passthrough sentinel, an empty list is returned.</p>
     *
     * @return immutable list of layer names, never {@code null}
     */
    public List<String> layerNames() {
        if (pipeline == null) {
            return List.of();
        }
        return pipeline.elements().stream()
                .map(InqElement::name)
                .toList();
    }

    /**
     * Returns the number of elements in this pipeline.
     *
     * @return the element count, or {@code 0} for a passthrough sentinel
     */
    public int depth() {
        return pipeline == null ? 0 : pipeline.depth();
    }

    /**
     * Returns {@code true} if this entry dispatches through the async chain.
     *
     * <p>Determined at cache-construction time from the proxied method's
     * return type — methods returning {@link CompletionStage} dispatch
     * asynchronously; all other methods dispatch synchronously.</p>
     *
     * @return {@code true} if async, {@code false} for sync or passthrough
     */
    public boolean isAsync() {
        return async;
    }

    /**
     * Returns {@code true} if this entry is the passthrough sentinel for a
     * method with no effective Inqudium annotations.
     *
     * @return {@code true} for the passthrough sentinel
     */
    public boolean isPassthrough() {
        return passthrough;
    }

    /**
     * Renders a diagnostic hierarchy string for this pipeline.
     *
     * <p>Format mirrors
     * {@link eu.inqudium.core.pipeline.Wrapper#toStringHierarchy()} —
     * one element per line, indented to convey nesting depth. The first
     * line names the dispatch mode rather than a chain ID, since this
     * type does not carry one (see the type-level JavaDoc).</p>
     *
     * @return a multi-line hierarchy string
     */
    public String toStringHierarchy() {
        StringBuilder sb = new StringBuilder();
        if (passthrough) {
            sb.append("Dispatch: passthrough\n(empty - no layers)\n");
            return sb.toString();
        }
        sb.append("Dispatch: ").append(async ? "async" : "sync").append("\n");
        List<String> names = layerNames();
        if (names.isEmpty()) {
            sb.append("(empty - no layers)\n");
            return sb.toString();
        }
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append("  ".repeat(i - 1)).append("  └── ");
            }
            sb.append(names.get(i)).append("\n");
        }
        return sb.toString();
    }

    // ======================== Internal accessors (hot path) ========================

    Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>> syncFactory() {
        return syncFactory;
    }

    Function<JoinPointExecutor<CompletionStage<Object>>,
            JoinPointExecutor<CompletionStage<Object>>> asyncFactory() {
        return asyncFactory;
    }
}
