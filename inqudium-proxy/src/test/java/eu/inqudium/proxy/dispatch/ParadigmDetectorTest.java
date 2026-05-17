package eu.inqudium.proxy.dispatch;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class ParadigmDetectorTest {

    /**
     * Fixture interface exposing one method per relevant return type.
     * Kept tiny on purpose: ParadigmDetector reads only the return
     * type, so a single representative method per type is enough.
     */
    interface TestService {

        CompletableFuture<String> returnsCompletableFuture();

        CompletionStage<String> returnsCompletionStage();

        String returnsString();

        void returnsVoid();

        int returnsInt();
    }

    private static Method method(String name) throws NoSuchMethodException {
        return TestService.class.getDeclaredMethod(name);
    }

    @Test
    void should_classify_a_completable_future_returning_method_as_async() throws NoSuchMethodException {
        // Given
        Method method = method("returnsCompletableFuture");

        // When
        boolean isAsync = ParadigmDetector.isAsyncMethod(method);

        // Then
        assertThat(isAsync).isTrue();
    }

    @Test
    void should_classify_a_completion_stage_returning_method_as_async() throws NoSuchMethodException {
        // Given
        Method method = method("returnsCompletionStage");

        // When
        boolean isAsync = ParadigmDetector.isAsyncMethod(method);

        // Then
        assertThat(isAsync).isTrue();
    }

    @Test
    void should_classify_a_string_returning_method_as_sync() throws NoSuchMethodException {
        // Given
        Method method = method("returnsString");

        // When
        boolean isAsync = ParadigmDetector.isAsyncMethod(method);

        // Then
        assertThat(isAsync).isFalse();
    }

    @Test
    void should_classify_a_void_returning_method_as_sync() throws NoSuchMethodException {
        // Given
        Method method = method("returnsVoid");

        // When
        boolean isAsync = ParadigmDetector.isAsyncMethod(method);

        // Then
        assertThat(isAsync).isFalse();
    }

    @Test
    void should_classify_an_int_returning_method_as_sync() throws NoSuchMethodException {
        // Given
        Method method = method("returnsInt");

        // When
        boolean isAsync = ParadigmDetector.isAsyncMethod(method);

        // Then
        assertThat(isAsync).isFalse();
    }
}
