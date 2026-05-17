package eu.inqudium.proxy.discipline;

import eu.inqudium.annotation.InqBulkhead;

/**
 * Default implementation of {@link SyncOnlyService} for use by
 * {@link ModuleLoadingDisciplineTest}. The {@link InqBulkhead}
 * annotation routes {@code greet(...)} through the pipeline&rsquo;s
 * sync-only element under the same name.
 */
public class DefaultSyncOnlyService implements SyncOnlyService {

    @InqBulkhead("disc-bh")
    @Override
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
