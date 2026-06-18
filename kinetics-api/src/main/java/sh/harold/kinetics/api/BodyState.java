package sh.harold.kinetics.api;

import java.util.Objects;

/** Latest completed immutable simulation snapshot for a body. */
public record BodyState(
        Pose pose,
        Vec3 linearVelocity,
        Vec3 angularVelocity,
        Vec3 scale,
        double massKilograms,
        boolean sleeping,
        long sequence
) {
    public BodyState {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(linearVelocity, "linearVelocity");
        Objects.requireNonNull(angularVelocity, "angularVelocity");
        Objects.requireNonNull(scale, "scale");
        Vec3.requireFinite(massKilograms, "massKilograms");
        if (massKilograms < 0.0) {
            throw new IllegalArgumentException("massKilograms must not be negative");
        }
    }
}
