package sh.harold.kinetics.plugin.physics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class PhysicsCoordinatorTest {
    @Test
    void confirmsCleanTerminationAndRejectsLaterWork() {
        PhysicsCoordinator coordinator = new PhysicsCoordinator(failure -> fail(failure));
        assertTrue(coordinator.closeAndAwait(1, TimeUnit.SECONDS));

        var rejected = coordinator.submit(() -> 1).toCompletableFuture();
        assertTrue(rejected.isCompletedExceptionally());
        assertTrue(coordinator.closeAndAwait(1, TimeUnit.SECONDS));
    }

    @Test
    void doesNotClaimQuiescenceWhenCleanupCouldNotRun() throws Exception {
        PhysicsCoordinator coordinator = new PhysicsCoordinator(failure -> { });
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean();
        var work = coordinator.submit(() -> {
            entered.countDown();
            while (!release.get()) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            return null;
        }).toCompletableFuture();

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertFalse(coordinator.closeAndAwait(20, TimeUnit.MILLISECONDS));
        release.set(true);
        work.get(1, TimeUnit.SECONDS);
        assertFalse(coordinator.closeAndAwait(1, TimeUnit.SECONDS));
    }
}