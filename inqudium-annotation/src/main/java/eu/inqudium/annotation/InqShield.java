package eu.inqudium.annotation;

import eu.inqudium.core.element.InqElementType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls the pipeline ordering for Inqudium element annotations on this
 * method or class.
 *
 * <p>{@code @InqShield} is <strong>optional</strong> for the default case.
 * The presence of any Inqudium element annotation (e.g. {@link InqCircuitBreaker},
 * {@link InqRetry}) on a method or class activates the pipeline with
 * {@code INQUDIUM} ordering by default. {@code @InqShield} is only required
 * when a non-default ordering is needed.</p>
 *
 * <h3>When @InqShield is needed vs. optional</h3>
 * <table>
 *   <tr><th>Scenario</th><th>{@code @InqShield} needed?</th></tr>
 *   <tr><td>Canonical order (default)</td><td>No — element annotations alone are sufficient</td></tr>
 *   <tr><td>Canonical order (explicit)</td><td>Optional — equivalent to absent</td></tr>
 *   <tr><td>Resilience4J order</td><td>Yes — {@code @InqShield(order = "RESILIENCE4J")}</td></tr>
 *   <tr><td>Custom order</td><td>Yes — {@code @InqShield(customOrder = {...})} with an explicit
 *       {@link InqElementType} array</td></tr>
 * </table>
 *
 * <h3>Usage examples</h3>
 * <pre>{@code
 * // Simplest form — no @InqShield needed for canonical order:
 * @InqCircuitBreaker("paymentCb")
 * @InqRetry("paymentRetry")
 * public PaymentResult processPayment(PaymentRequest request) { ... }
 *
 * // Explicit Resilience4J ordering:
 * @InqShield(order = "RESILIENCE4J")
 * @InqCircuitBreaker("paymentCb")
 * @InqRetry("paymentRetry")
 * public PaymentResult processPayment(PaymentRequest request) { ... }
 *
 * // Custom ordering — outermost first:
 * @InqShield(customOrder = {InqElementType.RETRY, InqElementType.CIRCUIT_BREAKER})
 * @InqRetry("rt")
 * @InqCircuitBreaker("cb")
 * public PaymentResult processPayment(PaymentRequest request) { ... }
 * }</pre>
 *
 * <h3>TYPE-level usage</h3>
 * <p>When placed on a class, applies to methods of that class that fall onto
 * the class-level-only path of the evaluator — that is, methods that declare
 * no method-level resilience annotations of their own. A method that carries
 * any method-level resilience annotation reads its ordering from the method's
 * own {@code @InqShield} (or the {@code "INQUDIUM"} default if absent); the
 * class-level {@code @InqShield} does not apply in that case. See ADR-036 §6.</p>
 *
 * @see InqCircuitBreaker
 * @see InqRetry
 * @see InqRateLimiter
 * @see InqBulkhead
 * @see InqTimeLimiter
 * @see InqTrafficShaper
 * @since 0.8.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Documented
@Inherited
public @interface InqShield {

    /**
     * Named pipeline-ordering strategy.
     *
     * <p>Recognised values:
     * <ul>
     *   <li>{@code "INQUDIUM"} (default) — elements sorted into canonical order
     *       per {@link InqElementType#defaultPipelineOrder()}:
     *       TimeLimiter → TrafficShaper → RateLimiter → Bulkhead →
     *       CircuitBreaker → Retry, outermost to innermost (ADR-017).</li>
     *   <li>{@code "RESILIENCE4J"} — elements sorted into Resilience4j-compatible
     *       order: Retry → CircuitBreaker → TrafficShaper → RateLimiter →
     *       TimeLimiter → Bulkhead, outermost to innermost.</li>
     * </ul>
     *
     * <p>Mutually exclusive with {@link #customOrder()}. The two attributes
     * must not both be set; the evaluator rejects such combinations at
     * construction time.</p>
     *
     * @return the ordering strategy name
     */
    String order() default "INQUDIUM";

    /**
     * Explicit ordering by element type, outermost first.
     *
     * <p>When non-empty, this attribute selects an explicit per-method
     * composition order and overrides the named strategy in {@link #order()}.
     * The resolved order must be unambiguous for every annotated source to
     * which this {@code @InqShield} applies. Concretely: every
     * resilience-element annotation declared on the source must appear in
     * {@code customOrder}. The array may additionally list element types
     * that the source does not carry — those extra entries are silently
     * filtered out during projection, which lets a single shared constant
     * be reused across sources whose annotation subsets differ. The
     * evaluator enforces these rules at construction time.</p>
     *
     * <p>Authors who want the same composition order across many methods
     * declare the same {@code customOrder = {...}} array literal at each
     * site. Java's annotation grammar does not allow a
     * {@code static final InqElementType[]} reference as a {@code customOrder}
     * value, so the reuse is source-level rather than
     * constant-reference-level:</p>
     *
     * <pre>{@code
     * @InqShield(customOrder = {InqElementType.BULKHEAD, InqElementType.CIRCUIT_BREAKER})
     * @InqBulkhead("orderBh")
     * @InqCircuitBreaker("orderCb")
     * public Order placeOrder(Cart cart) { ... }
     * }</pre>
     *
     * <p>An empty array (the default) means "not set" — Java annotation
     * defaults cannot represent {@code null}. Mutually exclusive with
     * {@link #order()}.</p>
     *
     * @return the explicit ordering, or an empty array if not set
     */
    InqElementType[] customOrder() default {};
}
