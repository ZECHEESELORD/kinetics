package sh.harold.kinetics.plugin.physics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JoltRuntimeTest {
    @Test
    void attemptsEveryOwnerAndSuppressesCleanupFailures() {
        RuntimeException primary = new RuntimeException("startup");
        RuntimeException jobs = new RuntimeException("jobs");
        RuntimeException allocator = new RuntimeException("allocator");
        AtomicInteger attempts = new AtomicInteger();

        Throwable result = JoltRuntime.closeOwners(primary,
                () -> {
                    attempts.incrementAndGet();
                    throw jobs;
                },
                () -> {
                    attempts.incrementAndGet();
                    throw allocator;
                },
                attempts::incrementAndGet);

        assertSame(primary, result);
        assertEquals(3, attempts.get());
        assertArrayEquals(new Throwable[] {jobs, allocator}, primary.getSuppressed());
    }

    @Test
    void makesTheFirstCloseFailurePrimaryWhenShutdownInitiatesCleanup() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");

        Throwable result = JoltRuntime.closeOwners(null,
                () -> {
                    throw first;
                },
                () -> {
                    throw second;
                });

        assertSame(first, result);
        assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
    }
}