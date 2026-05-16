/**
 * Pipeline composition primitive and its builder, per ADR-040.
 *
 * <p>The {@link eu.inqudium.pipeline.InqPipeline} interface and its
 * {@link eu.inqudium.pipeline.InqPipelineBuilder} are the new
 * composition layer that integration modules (proxy, future
 * functional dispatch, future aspect adapters) consume. They live in
 * this module rather than in {@code inqudium-core} so that the
 * dependency graph from ADR-037 can be realised over time.</p>
 *
 * <p>Detection classes for integrations and the bridge to the
 * existing annotation evaluator (ADR-036) are added in sub-step 3.3.</p>
 */
package eu.inqudium.pipeline;
