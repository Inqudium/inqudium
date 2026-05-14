package eu.inqudium.bulkhead.integration.proxy;

import eu.inqudium.config.event.ComponentBecameHotEvent;
import eu.inqudium.config.event.RuntimeComponentAddedEvent;
import eu.inqudium.config.event.RuntimeComponentPatchedEvent;
import eu.inqudium.config.event.RuntimeComponentRemovedEvent;
import eu.inqudium.config.event.RuntimeComponentVetoedEvent;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.bulkhead.InqBulkheadFullException;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.Wrapper;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import eu.inqudium.imperative.core.pipeline.proxy.InqAsyncProxyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * End-to-end demonstration of the proxy-based integration style with practice-grade logging
 * and a runtime-configuration-change demo.
 *
 * <p>The flow follows the natural structure of a proxy-based application that takes
 * operability seriously:
 * <ol>
 *   <li>build an {@link InqRuntime} via {@link BulkheadConfig#newRuntime()},</li>
 *   <li>install a bootstrap-side lifecycle-event subscriber on the runtime's event publisher
 *       so topology changes (added / patched / removed / vetoed / became-hot) appear in the
 *       log,</li>
 *   <li>compose an {@link InqPipeline} containing the bulkhead, lift it through
 *       {@link InqAsyncProxyFactory#of(InqPipeline)}, and {@code protect(...)} a
 *       {@link DefaultOrderService} instance — that constructor pulls the bulkhead from the
 *       runtime and subscribes per-component bulkhead-event handlers,</li>
 *   <li>log topology lines for every method on the {@link OrderService} interface — all
 *       four routes through the same proxy share one {@code chainId},</li>
 *   <li>build {@link AdminService} — the operator surface for the runtime patch demo,</li>
 *   <li>run the three-phase demo: normal operation → sell promotion (patched) → after
 *       promotion (patched back).</li>
 * </ol>
 *
 * <p>Decision deviation from sub-step&nbsp;6.C decision&nbsp;3 (topology logging in the
 * service): the proxy idiom builds the proxy externally — the {@link OrderService} interface
 * cannot self-log topology, and putting topology logging in {@link DefaultOrderService} would
 * violate the proxy pattern's principle that the implementation knows nothing about being
 * proxied. Topology logging therefore lives here in {@link Main}, immediately after the proxy
 * is constructed.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
        // entry point only
    }

    public static void main(String[] args) {
        try (InqRuntime runtime = BulkheadConfig.newRuntime()) {
            subscribeLifecycleEvents(runtime.general().eventPublisher());

            InqBulkhead<Object, Object> bulkhead = orderBulkhead(runtime);
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(bulkhead)
                    .build();
            OrderService service = InqAsyncProxyFactory.of(pipeline)
                    .protect(OrderService.class, new DefaultOrderService(runtime));

            // Topology logging — happens once per service method. The proxy implements both
            // OrderService and Wrapper, so the cast is safe (ProxyWrapper.createProxy adds
            // Wrapper.class to the implemented-interfaces array). The pipeline's elements are
            // read directly to surface the bulkhead's element-type and name; the proxy's own
            // layerDescription would only return "InqHybridPipelineProxy" — the wrapper-layer
            // name, not the pipeline content.
            logTopology(pipeline, (Wrapper<?>) service);

            AdminService admin = new AdminService(runtime);

            phase1NormalOperation(runtime, service);
            phase2SellPromotion(service, admin);
            phase3AfterPromotion(runtime, service, admin);
        }
    }

    /**
     * Phase 1: balanced/2 permits. Two normal calls succeed; saturation with two holders +
     * one extra rejects the third call synchronously with
     * {@link InqBulkheadFullException}.
     */
    private static void phase1NormalOperation(InqRuntime runtime, OrderService service) {
        System.out.println();
        System.out.println("=== Phase 1: Normal operation (balanced/2) ===");

        System.out.println(service.placeOrder("Widget"));
        System.out.println(service.placeOrder("Sprocket"));
        demonstrateSaturation(runtime, service, 2);
    }

    /**
     * Phase 2: patch to permissive/50, then demonstrate that 5 concurrent holders all succeed
     * — none rejected. Releases all holders cleanly before returning.
     */
    private static void phase2SellPromotion(OrderService service, AdminService admin) {
        System.out.println();
        System.out.println("=== Phase 2: Sell promotion (permissive/50) ===");

        admin.startSellPromotion();
        runFiveConcurrentHolders(service);
    }

    /**
     * Phase 3: patch back to balanced/2 and re-run the saturation pattern from phase 1. The
     * third call is rejected again — proof the patch reversed cleanly.
     */
    private static void phase3AfterPromotion(InqRuntime runtime, OrderService service,
                                             AdminService admin) {
        System.out.println();
        System.out.println("=== Phase 3: After promotion (balanced/2) ===");

        admin.endSellPromotion();
        demonstrateSaturation(runtime, service, 2);
    }

    /**
     * Log one topology line per service-interface method. All four methods route through the
     * same proxy — the format is
     * {@code "{methodName} protected by {layers} (chain-id {N})"} where {@code {layers}}
     * is the comma-joined element-description list from {@link InqPipeline#elements()}
     * formatted as {@code ELEMENT_TYPE(name)}. A reader inspecting the log sees four lines
     * that share one {@code chain-id}, accurately reflecting the proxy pattern's "all methods
     * on this proxy share one protection topology".
     */
    private static void logTopology(InqPipeline pipeline, Wrapper<?> proxy) {
        String layers = pipeline.elements().stream()
                .map(e -> e.elementType() + "(" + e.name() + ")")
                .collect(Collectors.joining(", "));
        long chainId = proxy.chainId();
        for (Method m : OrderService.class.getDeclaredMethods()) {
            LOG.info("{} protected by {} (chain-id {})",
                    m.getName(), layers, chainId);
        }
    }

    /**
     * Subscribe handlers for the five runtime-lifecycle event types. Levels follow sub-step
     * 6.D decision&nbsp;4: INFO for the four "normal" lifecycle events, WARN for vetoes
     * (a policy rejection is louder than a routine topology change).
     */
    private static void subscribeLifecycleEvents(InqEventPublisher publisher) {
        publisher.onEvent(ComponentBecameHotEvent.class, e ->
                LOG.info("Component became hot: '{}' ({})",
                        e.getElementName(), e.getElementType()));
        publisher.onEvent(RuntimeComponentAddedEvent.class, e ->
                LOG.info("Runtime component added: '{}' ({})",
                        e.getElementName(), e.getElementType()));
        publisher.onEvent(RuntimeComponentPatchedEvent.class, e ->
                LOG.info("Runtime component patched: '{}' ({}) — touched {}",
                        e.getElementName(), e.getElementType(), e.touchedFields()));
        publisher.onEvent(RuntimeComponentRemovedEvent.class, e ->
                LOG.info("Runtime component removed: '{}' ({})",
                        e.getElementName(), e.getElementType()));
        publisher.onEvent(RuntimeComponentVetoedEvent.class, e ->
                LOG.warn("Runtime component vetoed: '{}' ({}) — finding {}",
                        e.getElementName(), e.getElementType(), e.vetoFinding()));
    }

    /**
     * Saturate the bulkhead with {@code limit} virtual-thread holders, attempt one extra call
     * from the main thread, and observe synchronous rejection. Used by phases 1 and 3.
     */
    private static void demonstrateSaturation(InqRuntime runtime, OrderService service,
                                              int limit) {
        InqBulkhead<?, ?> bulkhead = orderBulkhead(runtime);

        CountDownLatch[] acquired = new CountDownLatch[limit];
        CountDownLatch release = new CountDownLatch(1);
        Thread[] holders = new Thread[limit];
        for (int i = 0; i < limit; i++) {
            acquired[i] = new CountDownLatch(1);
            CountDownLatch acq = acquired[i];
            holders[i] = Thread.startVirtualThread(() -> service.placeOrderHolding(acq, release));
        }

        try {
            for (CountDownLatch a : acquired) {
                a.await();
            }

            System.out.println("available permits while saturated: " + bulkhead.availablePermits());
            try {
                service.placeOrder("Saturated");
                System.out.println("unexpected: extra call returned");
            } catch (InqBulkheadFullException rejected) {
                System.out.println("extra call rejected: " + rejected.getRejectionReason());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            release.countDown();
            for (Thread t : holders) {
                joinQuietly(t);
            }
        }
    }

    /**
     * Five concurrent async holders — exercises the post-patch capacity (50 permits). All five
     * succeed; no rejection. The async holding shape needs no "acquired" latch because the
     * bulkhead acquires synchronously on the calling thread.
     */
    private static void runFiveConcurrentHolders(OrderService service) {
        CompletableFuture<Void> release = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletionStage<String>[] holders = new CompletionStage[5];
        for (int i = 0; i < 5; i++) {
            holders[i] = service.placeOrderHoldingAsync(release);
        }

        System.out.println("five concurrent async holders accepted under permissive/50");

        release.complete(null);
        for (CompletionStage<String> h : holders) {
            System.out.println(h.toCompletableFuture().join());
        }
    }

    /**
     * Look up the example bulkhead by name and cast to the typed surface so it can be fed to
     * {@link InqPipeline.Builder#shield(eu.inqudium.core.element.InqElement)}. The cast is
     * safe because the runtime registry stores the same instance under both views.
     *
     * <p>The proxy style does not need a per-call-site type witness — the proxy dispatches
     * by method return type. A single {@code <Object, Object>} witness is enough to wire the
     * bulkhead into the pipeline.
     */
    @SuppressWarnings("unchecked")
    private static InqBulkhead<Object, Object> orderBulkhead(InqRuntime runtime) {
        return (InqBulkhead<Object, Object>) runtime.imperative()
                .bulkhead(BulkheadConfig.BULKHEAD_NAME);
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
