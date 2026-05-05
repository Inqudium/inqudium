package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.config.InqConfig;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.imperative.bulkhead.config.InqImperativeBulkheadConfig;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;

public interface CircuitBreaker<A, R>
        extends InqDecorator<A, R>,
        InqAsyncDecorator<A, R> {

    /**
     * Creates a bulkhead from a general {@link InqConfig} container.
     *
     * @param config the configuration container holding an {@link InqImperativeBulkheadConfig}
     * @param <A>    the argument type
     * @param <R>    the return type
     * @return a new bulkhead instance
     */
    static <A, R> CircuitBreaker<A, R> of(InqConfig config) {
        return null;//of(config.of(InqImperativeCircuitBreakerConfig.class).orElseThrow());
    }

}
