package sh.harold.kinetics.plugin.physics;

import com.github.stephengold.joltjni.JobSystemThreadPool;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.TempAllocatorMalloc;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JoltRuntime implements AutoCloseable {
    private final TempAllocatorMalloc allocator;
    private final JobSystemThreadPool jobs;
    private final AtomicBoolean closed = new AtomicBoolean();

    public JoltRuntime(Path dataDirectory, int workerThreads) throws IOException {
        NativeLibraryLoader.load(dataDirectory);
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultTraceCallback();
        Jolt.installDefaultAssertCallback();
        if (!Jolt.newFactory()) {
            throw new IllegalStateException(
                    "Jolt factory is already initialized; restart after reloading Kinetics");
        }

        TempAllocatorMalloc createdAllocator = null;
        JobSystemThreadPool createdJobs = null;
        boolean typesRegistered = false;
        try {
            Jolt.registerTypes();
            typesRegistered = true;
            createdAllocator = new TempAllocatorMalloc();
            createdJobs = new JobSystemThreadPool(
                    Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, Math.max(1, workerThreads));
        } catch (Throwable failure) {
            closeOwners(failure,
                    createdJobs == null ? null : createdJobs::close,
                    createdAllocator == null ? null : createdAllocator::close,
                    typesRegistered ? Jolt::unregisterTypes : null,
                    Jolt::destroyFactory);
            throw failure;
        }
        allocator = createdAllocator;
        jobs = createdJobs;
    }

    public TempAllocatorMalloc allocator() {
        return allocator;
    }

    public JobSystemThreadPool jobs() {
        return jobs;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Throwable failure = closeOwners(null,
                jobs::close,
                allocator::close,
                Jolt::unregisterTypes,
                Jolt::destroyFactory);
        if (failure != null) rethrow(failure);
    }

    static Throwable closeOwners(Throwable primary, OwnerClose... owners) {
        Throwable failure = primary;
        for (OwnerClose owner : owners) {
            if (owner == null) continue;
            try {
                owner.close();
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("Jolt runtime cleanup failed", failure);
    }

    @FunctionalInterface
    interface OwnerClose {
        void close() throws Throwable;
    }
}