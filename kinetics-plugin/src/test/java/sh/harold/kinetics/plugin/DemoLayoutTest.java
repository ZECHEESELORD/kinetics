package sh.harold.kinetics.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import sh.harold.kinetics.api.Vec3;

class DemoLayoutTest {
    @Test
    void towerIsAUniqueEightByEightPerimeterAcrossNineLayers() {
        List<DemoLayout.TowerCell> cells = DemoLayout.towerCells();

        assertEquals(252, cells.size());
        assertEquals(cells.size(), new HashSet<>(cells).size());
        assertTrue(cells.stream().allMatch(cell ->
                cell.x() == 0 || cell.x() == 7 || cell.z() == 0 || cell.z() == 7));
        assertTrue(cells.stream().allMatch(cell -> cell.y() >= 0 && cell.y() < 9));
    }

    @Test
    void spectacleLayoutFitsItsSceneAndBodyBudget() {
        Vec3 floor = new Vec3(0.0, 64.0, 0.0);
        BoundingBox bounds = DemoLayout.bounds(DemoLayout.Mode.SPECTACLE, floor);

        for (DemoLayout.TowerCell cell : DemoLayout.towerCells()) {
            double x = 4.0 + cell.x() - 3.5;
            double y = 64.0 + cell.y() + 1.05;
            double z = cell.z() - 3.5;
            assertTrue(bounds.contains(x - 0.48, y - 0.48, z - 0.48));
            assertTrue(bounds.contains(x + 0.48, y + 0.48, z + 0.48));
        }
        assertTrue(bounds.contains(-17.75, 66.75, -1.75));
        assertTrue(bounds.contains(-14.25, 70.25, 1.75));
        assertTrue(DemoLayout.maximumBodies(DemoLayout.Mode.SPECTACLE) >= 254);
    }
}
