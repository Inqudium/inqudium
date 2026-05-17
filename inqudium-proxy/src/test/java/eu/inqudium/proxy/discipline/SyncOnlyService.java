package eu.inqudium.proxy.discipline;

/**
 * Service interface used by {@link ModuleLoadingDisciplineTest}.
 * Contains <strong>only synchronous methods</strong> &mdash; no
 * {@code CompletionStage}-returning method. Loading this interface
 * (and its impl) into the discipline test&rsquo;s {@code URLClassLoader}
 * must not cause any async-related class to be initialised.
 */
public interface SyncOnlyService {

    String greet(String name);
}
