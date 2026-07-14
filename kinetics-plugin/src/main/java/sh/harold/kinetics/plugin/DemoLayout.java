package sh.harold.kinetics.plugin;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.util.BoundingBox;
import sh.harold.kinetics.api.Vec3;

final class DemoLayout {
    static final int TOWER_SIZE = 8;
    static final int TOWER_HEIGHT = 9;

    private DemoLayout() {
    }

    static BoundingBox bounds(Mode mode, Vec3 floor) {
        return switch (mode) {
            case SAMPLER -> new BoundingBox(
                    floor.x() - 16.0, floor.y() - 4.0, floor.z() - 12.0,
                    floor.x() + 16.0, floor.y() + 16.0, floor.z() + 12.0);
            case SPECTACLE -> new BoundingBox(
                    floor.x() - 24.0, floor.y() - 4.0, floor.z() - 20.0,
                    floor.x() + 24.0, floor.y() + 28.0, floor.z() + 20.0);
        };
    }

    static Footprint footprint(Mode mode) {
        return switch (mode) {
            case SAMPLER -> new Footprint(-13, 13, -9, 9, 10);
            case SPECTACLE -> new Footprint(-19, 11, -8, 8, 13);
        };
    }

    static int maximumBodies(Mode mode) {
        return mode == Mode.SAMPLER ? 64 : 320;
    }

    static List<TowerCell> towerCells() {
        List<TowerCell> cells = new ArrayList<>(252);
        for (int y = 0; y < TOWER_HEIGHT; y++) {
            for (int x = 0; x < TOWER_SIZE; x++) {
                for (int z = 0; z < TOWER_SIZE; z++) {
                    if (x == 0 || x == TOWER_SIZE - 1 || z == 0 || z == TOWER_SIZE - 1) {
                        cells.add(new TowerCell(x, y, z));
                    }
                }
            }
        }
        return List.copyOf(cells);
    }

    enum Mode {
        SAMPLER("sampler"),
        SPECTACLE("spectacle");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    record Footprint(int minimumX, int maximumX, int minimumZ, int maximumZ,
            int clearanceHeight) {
    }

    record TowerCell(int x, int y, int z) {
    }
}
