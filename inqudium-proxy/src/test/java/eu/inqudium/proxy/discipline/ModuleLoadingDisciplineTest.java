package eu.inqudium.proxy.discipline;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical verification of the ADR-037 §6 class-loading discipline:
 * when a sync-only service interface is protected by the proxy
 * machinery, no async-related class is loaded.
 *
 * <p>Discipline contract empirically verified after the
 * {@code AsyncEntryBuilder} extraction (sub-step 3.13a) resolved the
 * leak documented in
 * {@code inqudium-proxy/docs/ADR-037-DISCIPLINE-FINDING.md}. Both
 * methods serve as permanent regression guards: any future change
 * that re-introduces an async type reference into a class loaded on
 * the sync path will trip these assertions.</p>
 *
 * <p><strong>Why URLClassLoader isolation.</strong> By the time
 * these tests run in a normal Surefire run, other test classes
 * (e.g. {@code AsyncChainFolderTest}, {@code DetectionAsyncTest})
 * may have already loaded async classes via the system classloader.
 * A fresh {@link URLClassLoader} with its parent set to the system
 * classloader&rsquo;s parent (the platform loader) sees a clean
 * class-loading state for every {@code eu.inqudium.*} type:
 * {@link ClassLoader#findLoadedClass(String)} on the isolated loader
 * reports only classes <strong>that loader</strong> has loaded,
 * regardless of system classloader state.</p>
 */
final class ModuleLoadingDisciplineTest {

    private static final List<String> ASYNC_CLASS_NAMES = List.of(
            "eu.inqudium.proxy.folding.AsyncChainFolder",
            "eu.inqudium.proxy.folding.FoldedAsyncChain",
            "eu.inqudium.proxy.entries.AsyncCacheEntry",
            "eu.inqudium.proxy.construction.AsyncParadigmValidator",
            "eu.inqudium.imperative.core.pipeline.InqAsyncDecorator",
            "eu.inqudium.imperative.core.pipeline.AsyncLayerAction",
            "eu.inqudium.imperative.core.pipeline.AsyncLayerTerminal"
    );

    @Test
    void should_protect_sync_only_service_without_inqudium_imperative() throws Exception {
        // What is to be tested? — A URLClassLoader that excludes
        //   inqudium-imperative from its classpath can still build
        //   and run a proxy for a sync-only service. This is the
        //   strongest form of the ADR-037 §6 discipline contract:
        //   deployment without inqudium-imperative works for
        //   purely-sync services.
        // Successful when? — pipeline.protect(...) returns a working
        //   proxy and greet(...) returns "Hello, World!", proving
        //   that the construction path did not touch any imperative
        //   class.
        // Why important? — This complements the loaded-class probe
        //   below: the probe asserts no async type was loaded; this
        //   assertion asserts that nothing would break if the
        //   types were absent.

        URL[] withoutImperative = currentClasspathURLs(true);
        ClassLoader bootstrapParent = ClassLoader.getSystemClassLoader().getParent();

        try (URLClassLoader noImperative =
                     new URLClassLoader(withoutImperative, bootstrapParent)) {
            Class<?> pipelineClass = noImperative.loadClass(
                    "eu.inqudium.pipeline.InqPipeline");
            Class<?> serviceClass = noImperative.loadClass(
                    "eu.inqudium.proxy.discipline.SyncOnlyService");
            Class<?> implClass = noImperative.loadClass(
                    "eu.inqudium.proxy.discipline.DefaultSyncOnlyService");
            Class<?> elementClass = noImperative.loadClass(
                    "eu.inqudium.proxy.discipline.SyncOnlyFakeBulkhead");
            Class<?> inqElementClass = noImperative.loadClass(
                    "eu.inqudium.core.element.InqElement");

            Object builder = pipelineClass.getMethod("builder").invoke(null);
            Object element = elementClass.getConstructor(String.class)
                    .newInstance("disc-bh");
            builder.getClass().getMethod("shield", inqElementClass)
                    .invoke(builder, element);
            Object pipeline = builder.getClass().getMethod("build")
                    .invoke(builder);
            Object target = implClass.getConstructor().newInstance();
            Method protectMethod = pipelineClass.getMethod(
                    "protect", Class.class, Object.class);
            Object proxy = protectMethod.invoke(pipeline, serviceClass, target);

            Object result = serviceClass.getMethod("greet", String.class)
                    .invoke(proxy, "World");
            assertThat(result).isEqualTo("Hello, World!");
        }
    }

    @Test
    void should_not_load_async_classes_when_building_a_sync_only_proxy() throws Exception {
        // What is to be tested? — Constructing a sync-only proxy
        //   through pipeline.protect must not load any async-related
        //   class. This pins ADR-037 §6's class-loading contract
        //   empirically.
        // Successful when? — All seven async class names return null
        //   from findLoadedClass on the isolated URLClassLoader, AND
        //   the proxy itself dispatches correctly (greet returns the
        //   expected string).
        // Why important? — Module-loading discipline is not
        //   compiler-enforced. One stray class-literal in a sync-path
        //   class would silently break the optional-dependency
        //   contract. This test catches that.

        URL[] classpath = currentClasspathURLs();
        // parent = system classloader's parent (the platform loader);
        // this prevents any eu.inqudium.* class from being inherited
        // from the parent and skipping our load-state observation.
        ClassLoader bootstrapParent = ClassLoader.getSystemClassLoader().getParent();

        try (IntrospectingClassLoader isolated =
                     new IntrospectingClassLoader(classpath, bootstrapParent)) {

            // Given — load all required types via the isolated loader
            Class<?> pipelineClass = isolated.loadClass(
                    "eu.inqudium.pipeline.InqPipeline");
            Class<?> serviceClass = isolated.loadClass(
                    "eu.inqudium.proxy.discipline.SyncOnlyService");
            Class<?> implClass = isolated.loadClass(
                    "eu.inqudium.proxy.discipline.DefaultSyncOnlyService");
            Class<?> elementClass = isolated.loadClass(
                    "eu.inqudium.proxy.discipline.SyncOnlyFakeBulkhead");
            Class<?> inqElementClass = isolated.loadClass(
                    "eu.inqudium.core.element.InqElement");

            // When — build the pipeline: InqPipeline.builder()
            //   .shield(new SyncOnlyFakeBulkhead("disc-bh")).build()
            Method builderMethod = pipelineClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            Class<?> builderClass = builder.getClass();

            Object element = elementClass.getConstructor(String.class)
                    .newInstance("disc-bh");
            Method shieldMethod = builderClass.getMethod("shield", inqElementClass);
            shieldMethod.invoke(builder, element);

            Method buildMethod = builderClass.getMethod("build");
            Object pipeline = buildMethod.invoke(builder);

            // proxy = pipeline.protect(SyncOnlyService.class, target)
            Object target = implClass.getConstructor().newInstance();
            Method protectMethod = pipelineClass.getMethod(
                    "protect", Class.class, Object.class);
            Object proxy = protectMethod.invoke(pipeline, serviceClass, target);

            // Exercise dispatch to ensure the full sync path is walked
            Method greetMethod = serviceClass.getMethod("greet", String.class);
            Object result = greetMethod.invoke(proxy, "World");
            assertThat(result).isEqualTo("Hello, World!");

            // Then — no async class was loaded by the isolated loader.
            //   peekLoadedClass exposes ClassLoader#findLoadedClass on
            //   a subclass we control, which sidesteps the
            //   InaccessibleObjectException that setAccessible(true)
            //   on java.lang.ClassLoader raises under the standard
            //   module access rules.
            for (String name : ASYNC_CLASS_NAMES) {
                Class<?> loaded = isolated.peekLoadedClass(name);
                assertThat(loaded)
                        .as("Async-related class %s must not be loaded "
                                + "by the URLClassLoader when only a "
                                + "sync-only proxy was constructed", name)
                        .isNull();
            }
        }
    }

    /**
     * URL-classloader subclass that exposes
     * {@link ClassLoader#findLoadedClass(String)} as a package-private
     * accessor. Lets the test peek at the loader&rsquo;s own load
     * state without needing
     * {@code --add-opens java.base/java.lang=ALL-UNNAMED} on the
     * Surefire JVM.
     */
    private static final class IntrospectingClassLoader extends URLClassLoader {

        IntrospectingClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        Class<?> peekLoadedClass(String name) {
            return findLoadedClass(name);
        }
    }

    private static URL[] currentClasspathURLs() {
        return currentClasspathURLs(false);
    }

    private static URL[] currentClasspathURLs(boolean excludeImperative) {
        String classpath = System.getProperty("java.class.path");
        String[] entries = classpath.split(File.pathSeparator);
        return Arrays.stream(entries)
                .filter(p -> !excludeImperative
                        || !p.contains("inqudium-imperative"))
                .map(File::new)
                .map(f -> {
                    try {
                        return f.toURI().toURL();
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Cannot convert classpath entry to URL: " + f, e);
                    }
                })
                .toArray(URL[]::new);
    }
}
