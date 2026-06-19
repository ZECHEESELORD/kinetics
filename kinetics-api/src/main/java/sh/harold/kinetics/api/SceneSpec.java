package sh.harold.kinetics.api;

import java.util.Objects;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

/** Configuration for a bounded, world-backed physics scene. */
public record SceneSpec(
        String name,
        World world,
        BoundingBox bounds,
        long terrainCaptureBudgetNanos,
        int maximumBodies,
        boolean terrainCollision
) {
    public static final long DEFAULT_CAPTURE_BUDGET_NANOS = 2_000_000L;
    public static final int DEFAULT_MAXIMUM_BODIES = 10_000;

    public SceneSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(bounds, "bounds");
        bounds = bounds.clone();
        double width = bounds.getWidthX();
        double height = bounds.getHeight();
        double depth = bounds.getWidthZ();
        if (!finite(bounds) || !Double.isFinite(width) || !Double.isFinite(height)
                || !Double.isFinite(depth) || width <= 0.0 || height <= 0.0 || depth <= 0.0) {
            throw new IllegalArgumentException("bounds must be finite and have positive finite volume");
        }
        if (terrainCaptureBudgetNanos <= 0L) {
            throw new IllegalArgumentException("terrainCaptureBudgetNanos must be positive");
        }
        if (maximumBodies <= 0) {
            throw new IllegalArgumentException("maximumBodies must be positive");
        }
    }

    public static SceneSpec of(String name, World world, BoundingBox bounds) {
        return new SceneSpec(name, world, bounds, DEFAULT_CAPTURE_BUDGET_NANOS,
                DEFAULT_MAXIMUM_BODIES, true);
    }

    @Override
    public BoundingBox bounds() {
        return bounds.clone();
    }

    private static boolean finite(BoundingBox box) {
        return Double.isFinite(box.getMinX()) && Double.isFinite(box.getMinY())
                && Double.isFinite(box.getMinZ()) && Double.isFinite(box.getMaxX())
                && Double.isFinite(box.getMaxY()) && Double.isFinite(box.getMaxZ());
    }
}
