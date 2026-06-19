package sh.harold.kinetics.api;

import java.util.Objects;

public record RaycastHit(
        ColliderRef collider,
        Vec3 point,
        Vec3 normal,
        double distance
) {
    public RaycastHit {
        Objects.requireNonNull(collider, "collider");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(normal, "normal");
        Vec3.requireFinite(distance, "distance");
        if (distance < 0.0) {
            throw new IllegalArgumentException("distance must not be negative");
        }
    }
}
