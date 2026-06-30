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
}