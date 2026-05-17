package eu.inqudium.proxy.smoke;

import eu.inqudium.config.live.LiveContainer;
import eu.inqudium.config.snapshot.BulkheadEventConfig;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.snapshot.SemaphoreStrategyConfig;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventExporterRegistry;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.event.InqPublisherConfig;
import eu.inqudium.core.log.LoggerFactory;
import eu.inqudium.core.time.InqClock;
import eu.inqudium.core.time.InqNanoTimeSource;
import eu.inqudium.imperative.bulkhead.InqBulkhead;

import java.time.Duration;
import java.util.Set;

/**
 * Test-only helper for constructing real {@link InqBulkhead}
 * instances against the production configuration architecture.
 *
 * <p>Mirrors the helper pattern in
 * {@code inqudium-imperative}'s {@code InqBulkheadTest}. Used by
 * sub-step 3.13's smoke tests to exercise the proxy machinery
 * against actual production resilience components rather than the
 * {@code FakeBulkhead} fixtures of 3.5&ndash;3.12.</p>
 */
public final class RealBulkheadFixture {

    private RealBulkheadFixture() {
        // utility
    }

    /**
     * Constructs a real {@link InqBulkhead} with the given name and
     * concurrency configuration. {@code maxWait} of
     * {@link Duration#ZERO} means &ldquo;fail-fast on permit
     * unavailability&rdquo; (no queuing).
     *
     * <p>Each call mints a fresh runtime
     * {@link InqEventPublisher}. The publisher is intentionally
     * not closed by the helper &mdash; smoke tests are short-lived
     * and the JVM&rsquo;s teardown is sufficient. Tests that need
     * deterministic publisher disposal should wrap the helper in
     * their own lifecycle.</p>
     *
     * @param name          the bulkhead name; must match the
     *                      {@code @InqBulkhead} annotation value
     *                      on the protected service&rsquo;s methods
     * @param maxConcurrent the maximum number of concurrent permits
     * @param maxWait       the wait budget for permit acquisition;
     *                      {@link Duration#ZERO} for fail-fast
     */
    public static <A, R> InqBulkhead<A, R> newBulkhead(
            String name, int maxConcurrent, Duration maxWait) {
        BulkheadSnapshot snapshot = new BulkheadSnapshot(
                name, maxConcurrent, maxWait, Set.of(), null,
                BulkheadEventConfig.disabled(),
                new SemaphoreStrategyConfig());
        LiveContainer<BulkheadSnapshot> live = new LiveContainer<>(snapshot);
        GeneralSnapshot general = newGeneralSnapshot(name);
        return new InqBulkhead<>(live, general);
    }

    private static GeneralSnapshot newGeneralSnapshot(String name) {
        InqEventPublisher runtimePublisher = InqEventPublisher.create(
                name,
                InqElementType.BULKHEAD,
                new InqEventExporterRegistry(),
                InqPublisherConfig.defaultConfig());
        return new GeneralSnapshot(
                InqClock.system(),
                InqNanoTimeSource.system(),
                runtimePublisher,
                InqEventPublisher::create,
                LoggerFactory.NO_OP_LOGGER_FACTORY,
                true);
    }
}
