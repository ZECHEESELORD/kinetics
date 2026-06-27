package sh.harold.kinetics.plugin.physics;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PhysicsCoordinator implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Kinetics coordinator");
        thread.setDaemon(true);
        return thread;
    });
    private final CopyOnWriteArrayList<JoltScene> scenes = new CopyOnWriteArrayList<>();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<Throwable> errorHandler;

    public PhysicsCoordinator(Consumer<Throwable> errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    public Runnable stepRequester() {
        return () -> { };
    }

    public void reportFailure(Throwable failure) {
        errorHandler.accept(failure);
    }


    public void add(JoltScene scene) {
        scenes.add(scene);
    }

    public void remove(JoltScene scene) {
        scenes.remove(scene);
    }

    public <T> CompletionStage<T> submit(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try { future.complete(supplier.get()); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
        });
        return future;
    }

    public void requestStep() {
        if (closed.get()) return;
        if (!inFlight.compareAndSet(false, true)) {
            for (JoltScene scene : scenes) scene.markSkippedTick();
            return;
        }
        executor.execute(() -> {
            try {
                for (JoltScene scene : scenes) {
                    try {
                        scene.stepNative();
                    } catch (Throwable failure) {
                        errorHandler.accept(failure);
                    }
                }
                scenes.removeIf(JoltScene::closed);
            } finally {
                inFlight.set(false);
            }
        });
    }

    public void dispose(JoltScene scene) {
        if (closed.get()) throw new IllegalStateException("Physics coordinator is closed");
        executor.execute(scene::shutdownNowNative);
    }

    public void flush() {
        if (closed.get()) return;
        try {
            executor.submit(() -> { }).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failure) {
            errorHandler.accept(failure);
        }
    }

    public List<JoltScene> scenes() {
        return List.copyOf(scenes);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (JoltScene scene : scenes) scene.cleanupBindingsOnMain(errorHandler);
        Future<?> cleanup = executor.submit(() -> {
            for (JoltScene scene : scenes) {
                try {
                    scene.shutdownNowNative();
                } catch (Throwable failure) {
                    errorHandler.accept(failure);
                }
            }
        });
        try {
            cleanup.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failure) {
            errorHandler.accept(failure);
        }
        scenes.clear();
        executor.shutdownNow();
    }
}
