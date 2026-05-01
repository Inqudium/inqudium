/**
 * Library-end-to-end tests for the imperative bulkhead.
 *
 * <p>The tests in this module exercise bulkhead library behaviour end-to-end under
 * realistic conditions — concurrency races, lifecycle transitions (cold-to-hot, strategy
 * hot-swap, structural removal), wrapper-family compatibility across the
 * {@code decorateXxx} surface, and aspect-pipeline integration with a real bulkhead.
 * Each test class addresses one variant; method names describe one user scenario.
 * Reading the suite top-to-bottom is intended to feel like a tutorial of the library's
 * end-to-end behaviour.
 *
 * <p><strong>These are NOT examples of how to test a user's application.</strong> For
 * application-level test patterns, see the example modules under
 * {@code inqudium-bulkhead-integration/}, each of which exercises a tiny webshop scenario
 * through one integration style (function-based, JDK proxy, AspectJ, Spring Framework,
 * Spring Boot) and tests its own behaviour the way a real application would. The
 * library-end-to-end tests live here so a future reader is not tempted to copy them into
 * their own test suite as a template — they are the library's safety net, not application
 * test patterns.
 *
 * <p>The module produces no production artifact — it is the test-only counterpart to the
 * existing {@code inqudium-aspect-integration-tests} module, applied to the bulkhead's
 * complete stack. Module-internal collaborators (synthetic strategies, throwing closeables,
 * tiny test-only services) live as static nested types on the test classes that need them.
 *
 * <p>Closes the following carried-forward audit findings:
 * <ul>
 *   <li>2.12.3 — race between {@code markRemoved} and {@code onSnapshotChange} during
 *       hot-swap.</li>
 *   <li>2.12.4 — strategy construction failure on cold-to-hot, and {@code closeStrategy}
 *       throw on hot-swap.</li>
 *   <li>2.17.3 — wrapper compatibility across the cold/hot/removed transitions.</li>
 *   <li>2.17.4 — wrapper and proxy tests against real bulkheads (post ADR-033).</li>
 *   <li>F-2.18-3 — AspectJ integration against a real bulkhead.</li>
 *   <li>F-2.19-7 — Spring Boot integration against a real bulkhead.</li>
 * </ul>
 */
package eu.inqudium.bulkhead.integration;
