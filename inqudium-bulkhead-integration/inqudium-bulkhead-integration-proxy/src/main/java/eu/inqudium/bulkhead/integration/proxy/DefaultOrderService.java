package eu.inqudium.bulkhead.integration.proxy;

import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.element.bulkhead.event.BulkheadRollbackTraceEvent;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Default implementation of {@link OrderService}. The implementation is plain Java with no
 * Inqudium type, no annotation, and no framework hook in its method bodies — exactly as in
 * the function-based example. Resilience is layered on by the
 * {@code InqAsyncProxyFactory.of(InqPipeline)} factory that wraps an instance of this class
 * behind a JDK dynamic proxy; the proxied method calls flow through the pipeline before
 * reaching this implementation.
 *
 * <p>The constructor accepts an {@link InqRuntime} for one reason only: it subscribes
 * per-component bulkhead-event handlers that emit log records at the levels prescribed by
 * sub-step&nbsp;6.D of {@code REFACTORING_BULKHEAD_LOGGING_AND_RUNTIME_CONFIG.md} — TRACE for
 * acquire/release, WARN for reject, ERROR for rollback. Subscribing inside the implementation
 * keeps bulkhead-event logging close to the service that uses the bulkhead, parallel to the
 * function-based example. The implementation itself never observes that it is proxied — the
 * {@link OrderService} method bodies remain plain Java.
 */
public class DefaultOrderService implements OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultOrderService.class);

    public DefaultOrderService(InqRuntime runtime) {
        InqBulkhead<?, ?> bulkhead = (InqBulkhead<?, ?>) runtime.imperative()
                .bulkhead(BulkheadConfig.BULKHEAD_NAME);
        subscribeBulkheadEvents(bulkhead);
    }

    @Override
    public String placeOrder(String item) {
        return "ordered:" + item;
    }

    @Override
    public String placeOrderHolding(CountDownLatch acquired, CountDownLatch release) {
        acquired.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timeout: holder never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return "released";
    }

    @Override
    public CompletionStage<String> placeOrderAsync(String item) {
        return CompletableFuture.completedFuture("async-ordered:" + item);
    }

    @Override
    public CompletionStage<String> placeOrderHoldingAsync(CompletableFuture<Void> release) {
        return release.thenApply(ignored -> "async-released");
    }

    /**
     * Subscribe handlers for the four bulkhead event types this example opts into via
     * {@link BulkheadConfig}. Levels follow sub-step&nbsp;6.D decision&nbsp;3: TRACE for the
     * routine acquire/release pair, WARN for rejection (a back-pressure signal callers care
     * about), ERROR for rollback (a library-internal anomaly that should never occur on a
     * healthy bulkhead).
     */
    private static void subscribeBulkheadEvents(InqBulkhead<?, ?> bulkhead) {
        var publisher = bulkhead.eventPublisher();
        publisher.onEvent(BulkheadOnAcquireEvent.class, e ->
                LOG.trace("Permit acquired on bulkhead '{}' (chain-id {}, call-id {}, concurrent {})",
                        e.getElementName(), e.getChainId(), e.getCallId(), e.getConcurrentCalls()));
        publisher.onEvent(BulkheadOnReleaseEvent.class, e ->
                LOG.trace("Permit released on bulkhead '{}' (chain-id {}, call-id {}, concurrent {})",
                        e.getElementName(), e.getChainId(), e.getCallId(), e.getConcurrentCalls()));
        publisher.onEvent(BulkheadOnRejectEvent.class, e ->
                LOG.warn("Permit rejected on bulkhead '{}' (chain-id {}, call-id {}, reason {})",
                        e.getElementName(), e.getChainId(), e.getCallId(), e.getRejectionReason()));
        publisher.onEvent(BulkheadRollbackTraceEvent.class, e ->
                LOG.error("Permit rolled back on bulkhead '{}' (chain-id {}, call-id {}, cause {})",
                        e.getElementName(), e.getChainId(), e.getCallId(), e.getErrorType()));
    }
}
