package sh.harold.kinetics.api;

import java.util.Objects;

public record Pose(Vec3 position, Rotation rotation) {
    public static final Pose IDENTITY = new Pose(Vec3.ZERO, Rotation.IDENTITY);

    public Pose {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotation, "rotation");
    }

    public static Pose at(Vec3 position) {
        return new Pose(position, Rotation.IDENTITY);
    }
}
