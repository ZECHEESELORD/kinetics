package sh.harold.kinetics.api;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Entity;

public interface PhysicsBody {
    BodyId id();

    PhysicsScene scene();

    BodyState state();

    ColliderFidelity colliderFidelity();

    boolean destroyed();

    void applyForce(Vec3 force);

    void applyForceAtPoint(Vec3 force, Vec3 worldPoint);

    void applyTorque(Vec3 torque);

    void applyImpulse(Vec3 impulse);

    void applyImpulseAtPoint(Vec3 impulse, Vec3 worldPoint);

    void setLinearVelocity(Vec3 velocity);

    void setAngularVelocity(Vec3 velocity);

    void teleport(Pose pose);

    void setMaterial(PhysicsMaterial material);

    void wake();

    void sleep();

    CompletionStage<ResizeResult> resize(Vec3 scale);

    CompletionStage<Void> destroy();

    /** Releases a bound Bukkit entity, or returns empty for a headless body. */
    CompletionStage<Optional<Entity>> release();
}
