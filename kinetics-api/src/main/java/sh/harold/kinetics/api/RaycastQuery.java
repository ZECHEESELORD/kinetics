package sh.harold.kinetics.api;

import java.util.Objects;

public record RaycastQuery(
        Vec3 origin,
        Vec3 direction,
        double maximumDistance,
        int collisionMask,
        boolean includeTerrain
) {
    public RaycastQuery {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(direction, "direction");
        Vec3.requireFinite(maximumDistance, "maximumDistance");
        if (maximumDistance <= 0.0) {
            throw new IllegalArgumentException("maximumDistance must be greater than zero");
        }
        direction = direction.normalized();
    }

    public static RaycastQuery all(Vec3 origin, Vec3 direction, double maximumDistance) {
        return new RaycastQuery(origin, direction, maximumDistance, -1, true);
    }
}
