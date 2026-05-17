package eu.inqudium.proxy.folding;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class FoldedAsyncChainTest {

    @Test
    void should_be_marked_as_a_functional_interface() {
        // What is to be tested?
        //   FoldedAsyncChain carries the @FunctionalInterface
        //   annotation. This is the contract that lets the folder
        //   return a lambda — and that lets test fixtures provide one.
        // How will the test case be deemed successful and why?
        //   getAnnotation(FunctionalInterface.class) is non-null.
        // Why is it important to test this test case?
        //   The annotation is enforced by the compiler when present;
        //   it makes the SAM contract part of the type's identity.

        // Given / When
        Annotation functional =
                FoldedAsyncChain.class.getAnnotation(FunctionalInterface.class);

        // Then
        assertThat(functional).isNotNull();
    }

    @Test
    void should_have_a_single_abstract_run_method_returning_completion_stage() throws NoSuchMethodException {
        // What is to be tested?
        //   The functional interface declares exactly one abstract
        //   method, `run`, returning CompletionStage<Object>.
        // How will the test case be deemed successful and why?
        //   getDeclaredMethods() returns one abstract method named
        //   "run"; its return type is CompletionStage.
        // Why is it important to test this test case?
        //   Pins the SAM signature — a change here would break every
        //   AsyncChainFolder result silently.

        // Given / When
        Method[] abstractMethods = Arrays.stream(
                        FoldedAsyncChain.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isAbstract(m.getModifiers()))
                .toArray(Method[]::new);

        // Then
        assertThat(abstractMethods).hasSize(1);
        Method run = FoldedAsyncChain.class.getDeclaredMethod(
                "run", long.class, long.class, Object[].class);
        assertThat(run.getReturnType()).isEqualTo(CompletionStage.class);
    }

    @Test
    void should_return_the_lambda_s_completion_stage_from_run() {
        // What is to be tested?
        //   A lambda providing FoldedAsyncChain returns its
        //   CompletionStage verbatim when run is called.
        // How will the test case be deemed successful and why?
        //   The stage returned is the same instance the lambda
        //   produced.
        // Why is it important to test this test case?
        //   Sanity check that the SAM type works as expected before
        //   the chain folder relies on it.

        // Given
        CompletableFuture<Object> stage = CompletableFuture.completedFuture("v");
        FoldedAsyncChain chain = (stackId, callId, args) -> stage;

        // When
        CompletionStage<Object> result = chain.run(1L, 2L, new Object[]{"x"});

        // Then
        assertThat(result).isSameAs(stage);
    }

    @Test
    void should_not_declare_throws_on_run() throws NoSuchMethodException {
        // What is to be tested?
        //   The run method declares no checked exceptions. The
        //   underlying AsyncLayerAction.executeAsync from
        //   inqudium-imperative similarly does not throw; checked
        //   exceptions from the target are wrapped in
        //   CompletableFuture.failedFuture by the folder. The contract
        //   here is that async callers always observe a stage.
        // How will the test case be deemed successful and why?
        //   getExceptionTypes() returns an empty array.
        // Why is it important to test this test case?
        //   Documents the no-throws contract that makes the async
        //   error model uniform; a regression that added a throws
        //   declaration would expose two error channels to callers.

        // Given / When
        Method run = FoldedAsyncChain.class.getDeclaredMethod(
                "run", long.class, long.class, Object[].class);

        // Then
        assertThat(run.getExceptionTypes()).isEmpty();
    }
}
