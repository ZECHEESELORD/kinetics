package sh.harold.kinetics.plugin.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirtySectionCoordinatorTest {
    @Test
    void onlyLatestInvalidationCanAdvanceThroughCaptureAndBuild() {
        DirtySectionCoordinator<String> coordinator = new DirtySectionCoordinator<>();

        long firstRevision = coordinator.markDirty("section", 0);
        assertEquals(firstRevision, coordinator.pollDirty().orElseThrow().revision());
        assertTrue(coordinator.markCaptured("section", firstRevision));

        long secondRevision = coordinator.markDirty("section", 0);
        assertEquals(secondRevision, coordinator.pollDirty().orElseThrow().revision());
        assertFalse(coordinator.markBuilt("section", firstRevision));

        long latestRevision = coordinator.markDirty("section", 0);
        assertEquals(latestRevision, coordinator.pollDirty().orElseThrow().revision());
        assertFalse(coordinator.markCaptured("section", secondRevision));
        assertTrue(coordinator.markCaptured("section", latestRevision));
        assertTrue(coordinator.markBuilt("section", latestRevision));
        assertEquals(
                new DirtySectionCoordinator.Status(latestRevision, DirtySectionCoordinator.Stage.BUILT),
                coordinator.status("section").orElseThrow()
        );
    }

    @Test
    void failedActivationCanBeRequeuedWithoutBecomingActive() {
        DirtySectionCoordinator<String> coordinator = new DirtySectionCoordinator<>();

        long revision = coordinator.markDirty("section", 0);
        assertEquals(revision, coordinator.pollDirty().orElseThrow().revision());
        assertTrue(coordinator.markCaptured("section", revision));
        assertTrue(coordinator.markBuilt("section", revision));

        long retry = coordinator.markDirty("section", 1);
        assertEquals(
                new DirtySectionCoordinator.Status(retry, DirtySectionCoordinator.Stage.DIRTY),
                coordinator.status("section").orElseThrow()
        );
        assertEquals(retry, coordinator.pollDirty().orElseThrow().revision());
        assertFalse(coordinator.markActive("section", revision));
    }
}