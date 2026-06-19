package sh.harold.kinetics.api;

import java.util.Objects;

/** Contact notification. In v0.1, normalImpulse is zero because Jolt JNI exposes no post-solve impulse callback. */
public record ContactEvent(
        ContactPhase phase,
        ColliderRef first,
        ColliderRef second,
        Vec3 point,
        Vec3 normal,
        double normalImpulse,
        double relativeSpeed
) {
    public ContactEvent {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(normal, "normal");
        Vec3.requireFinite(normalImpulse, "normalImpulse");
        Vec3.requireFinite(relativeSpeed, "relativeSpeed");
    }
}
