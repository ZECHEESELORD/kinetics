package sh.harold.kinetics.plugin.physics;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PhysicsCoordinator implements AutoCloseable {
    private static final long DEFAULT_TIMEOUT_SECONDS = 15;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Kinetics coordinator");
        thread.setDaemon(true);
        return thread;
    });
    private final CopyOnWriteArrayList<JoltScene> scenes = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean nativeCleanupFailed = new AtomicBoolean();
    private final CompletableFuture<Void> nativeCleanup = new CompletableFuture<>();
    private final Consumer<Throwable> errorHandler;

    public PhysicsCoordinator(Consumer<Throwable> errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    public Runnable stepRequester() {
        return () -> { };
    }

    public void reportFailure(Throwable failure) {
        try {
            errorHandler.accept(failure);
        } catch (Throwable handlerFailure) {
            if (failure != null && handlerFailure != failure) failure.addSuppressed(handlerFailure);
        }
    }

    public void add(JoltScene scene) {
        Objects.requireNonNull(scene, "scene");
        synchronized (lifecycleLock) {
            if (!closed.get()) {
                scenes.add(scene);
                return;
            }
        }
        scene.shutdownNowNative();
        throw new IllegalStateException("Physics coordinator is closed");
    }

    public void remove(JoltScene scene) {
        scenes.remove(scene);
    }

    public <T> CompletionStage<T> submit(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed.get()) {
            future.completeExceptionally(new IllegalStateException("Physics coordinator is closed"));
            return future;
        }
        try {
            executor.execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    public void requestStep() {
        if (closed.get()) return;
        if (!inFlight.compareAndSet(false, true)) {
            for (JoltScene scene : scenes) scene.markSkippedTick();
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    for (JoltScene scene : scenes) {
                        try {
                            scene.stepNative();
                        } catch (Throwable failure) {
                            reportFailure(failure);
                        }
                    }
                    scenes.removeIf(JoltScene::closed);
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (RejectedExecutionException rejected) {
            inFlight.set(false);
            if (!closed.get()) reportFailure(rejected);
        }
    }

    public void dispose(JoltScene scene) {
        if (closed.get()) throw new IllegalStateException("Physics coordinator is closed");
        executor.execute(scene::shutdownNowNative);
    }

    public boolean flush() {
        if (closed.get()) return false;
        try {
            executor.submit(() -> {
                for (JoltScene scene : scenes) {
                    try {
                        scene.drainCommandsForShutdown();
                    } catch (Throwable failure) {
                        reportFailure(failure);
                    }
                }
            }).get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | RejectedExecutionException | TimeoutException failure) {
            reportFailure(failure);
            return false;
        }
    }

    public List<JoltScene> scenes() {
        return List.copyOf(scenes);
    }

    @Override
    public void close() {
        if (!closeAndAwait(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            reportFailure(new TimeoutException("Physics coordinator did not terminate cleanly"));
        }
    }

    public boolean closeAndAwait(long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("timeout cannot be negative");

        synchronized (lifecycleLock) {
            if (closed.compareAndSet(false, true)) beginClose();
        }

        long remaining = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remaining;
        try {
            if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
                remaining = Math.max(0L, deadline - System.nanoTime());
                executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
        return executor.isTerminated() && nativeCleanup.isDone() && !nativeCleanupFailed.get();
    }

    private void beginClose() {
        for (JoltScene scene : scenes) {
            try {
                scene.cleanupBindingsOnMain(this::reportFailure);
            } catch (Throwable failure) {
                reportFailure(failure);
            }
        }
        try {
            executor.execute(() -> {
                try {
                    for (JoltScene scene : scenes) {
                        try {
                            scene.shutdownNowNative();
                        } catch (Throwable failure) {
                            nativeCleanupFailed.set(true);
                            reportFailure(failure);
                        }
                    }
                    scenes.clear();
                } finally {
                    nativeCleanup.complete(null);
                }
            });
        } catch (RejectedExecutionException rejected) {
            nativeCleanupFailed.set(true);
            reportFailure(rejected);
        } finally {
            executor.shutdown();
        }
    }
}