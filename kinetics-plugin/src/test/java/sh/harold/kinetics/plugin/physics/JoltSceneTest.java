package sh.harold.kinetics.plugin.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

class JoltSceneTest {
    @Test
    void terrainCapacityUsesOnlyWorldHeightSections() {
        BoundingBox tallerThanWorld = new BoundingBox(0, -2_048, 0, 16, 2_048, 16);

        assertEquals(24, JoltScene.terrainSectionCapacity(tallerThanWorld, -64, 320));
    }

    @Test
    void terrainCapacityIsZeroOutsideWorldHeight() {
        BoundingBox belowWorld = new BoundingBox(0, -2_048, 0, 16, -1_024, 16);

        assertEquals(0, JoltScene.terrainSectionCapacity(belowWorld, -64, 320));
    }
}
