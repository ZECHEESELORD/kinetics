package sh.harold.kinetics.plugin.shape;

import java.util.Objects;
import sh.harold.kinetics.api.ColliderFidelity;
import sh.harold.kinetics.api.PhysicsShape;

public record InferredCollider(PhysicsShape shape, ColliderFidelity fidelity) {
    public InferredCollider {
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(fidelity, "fidelity");
    }
}
