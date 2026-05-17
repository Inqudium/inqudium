package eu.inqudium.proxy.handler;

import eu.inqudium.proxy.entries.MethodDispatchEntry;
import eu.inqudium.proxy.invocation.MethodInvoker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerProxyCacheTest {

    public interface TestService {
        String greet(String name);

        int sum(int a, int b);
    }

    public static final class TestServiceImpl implements TestService {
        @Override
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        @Override
        public int sum(int a, int b) {
            return a + b;
        }
    }

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return TestService.class.getDeclaredMethod(name, params);
    }

    private static MethodDispatchEntry passThrough(Object target, Method method) {
        return MethodDispatchEntry.passThrough(MethodInvoker.create(target, method));
    }

    @Test
    void should_return_the_entry_for_a_known_method() throws NoSuchMethodException {
        // Given
        TestServiceImpl target = new TestServiceImpl();
        Method greet = method("greet", String.class);
        MethodDispatchEntry entry = passThrough(target, greet);
        Map<Method, MethodDispatchEntry> map = new HashMap<>();
        map.put(greet, entry);
        PerProxyCache cache = new PerProxyCache(map);

        // When
        MethodDispatchEntry resolved = cache.entryFor(greet);

        // Then
        assertThat(resolved).isSameAs(entry);
    }

    @Test
    void should_throw_illegal_state_when_no_entry_exists_for_a_method() throws NoSuchMethodException {
        // Given — cache built without an entry for sum(...)
        TestServiceImpl target = new TestServiceImpl();
        Method greet = method("greet", String.class);
        Method sum = method("sum", int.class, int.class);
        Map<Method, MethodDispatchEntry> map = new HashMap<>();
        map.put(greet, passThrough(target, greet));
        PerProxyCache cache = new PerProxyCache(map);

        // When / Then
        assertThatThrownBy(() -> cache.entryFor(sum))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No dispatch entry")
                .hasMessageContaining("sum");
    }

    @Test
    void should_be_immutable_to_external_modification_of_the_input_map() throws NoSuchMethodException {
        // What is to be tested?
        //   The cache must defensively copy the entries map so a
        //   caller that retains the original map and mutates it
        //   cannot affect the cache's lookups.
        // How will the test case be deemed successful and why?
        //   We construct a cache from a mutable HashMap, then add a
        //   new entry to the original HashMap. The cache must not
        //   see the new entry — entryFor(...) for the late-added
        //   key must throw IllegalStateException.
        // Why is it important to test this test case?
        //   PerProxyCache is on the hot path of every proxied call;
        //   if it shared state with construction code, a future
        //   refactor that retains the construction map could
        //   accidentally mutate dispatch state at runtime.

        // Given
        TestServiceImpl target = new TestServiceImpl();
        Method greet = method("greet", String.class);
        Method sum = method("sum", int.class, int.class);
        Map<Method, MethodDispatchEntry> source = new HashMap<>();
        source.put(greet, passThrough(target, greet));
        PerProxyCache cache = new PerProxyCache(source);

        // When — mutate the source map post-construction
        source.put(sum, passThrough(target, sum));

        // Then — the cache does not observe the late insertion
        assertThatThrownBy(() -> cache.entryFor(sum))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_reject_null_entries_map_with_npe() {
        // Given / When / Then
        assertThatNullPointerException()
                .isThrownBy(() -> new PerProxyCache(null))
                .withMessage("entries");
    }
}
