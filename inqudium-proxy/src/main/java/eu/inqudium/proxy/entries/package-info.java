/**
 * The sealed {@code MethodDispatchEntry} family with its five permits:
 * {@code PassThroughEntry}, {@code DefaultMethodEntry},
 * {@code SyncCacheEntry}, {@code ObjectMethodEntry}, and
 * {@code AsyncCacheEntry}. Each entry captures the per-method dispatch
 * strategy decided at proxy construction time and consumed by the
 * runtime handler.
 *
 * @see inqudium-proxy/docs/ARCHITECTURE.md
 */
package eu.inqudium.proxy.entries;
