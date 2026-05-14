package eu.inqudium.imperative.core.pipeline.function;

import eu.inqudium.core.pipeline.function.RunnableWrapper;
import eu.inqudium.imperative.core.pipeline.AsyncBaseWrapper;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.imperative.core.pipeline.InternalAsyncExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Async wrapper for fire-and-forget operations.
 *
 * <p>The async counterpart to {@link RunnableWrapper}. The core executes the Runnable
 * synchronously and wraps the result in an already-completed {@link CompletionStage}.</p>
 */
public class AsyncRunnableWrapper
        extends AsyncBaseWrapper<Runnable, Void, Void, AsyncRunnableWrapper>
        implements Supplier<CompletionStage<Void>>, Runnable {

    public AsyncRunnableWrapper(InqAsyncDecorator<Void, Void> decorator, Runnable delegate) {
        super(decorator, delegate, coreFor(delegate));
    }

    public AsyncRunnableWrapper(String name, Runnable delegate,
                                AsyncLayerAction<Void, Void> layerAction) {
        super(name, delegate, coreFor(delegate), layerAction);
    }

    public AsyncRunnableWrapper(String name, Runnable delegate) {
        this(name, delegate, AsyncLayerAction.passThrough());
    }

    private static InternalAsyncExecutor<Void, Void> coreFor(Runnable delegate) {
        return (chainId, callId, arg) -> {
            delegate.run();
            return CompletableFuture.completedFuture(null);
        };
    }

    @Override
    public CompletionStage<Void> get() {
        return initiateChain(null);
    }

    @Override
    public void run() {
        initiateChain(null);
    }
}
