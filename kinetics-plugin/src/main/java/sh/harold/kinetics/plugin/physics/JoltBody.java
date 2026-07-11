package sh.harold.kinetics.plugin.physics;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Entity;
import sh.harold.kinetics.api.BodyId;
import sh.harold.kinetics.api.BodySpec;
import sh.harold.kinetics.api.BodyState;
import sh.harold.kinetics.api.ColliderFidelity;
import sh.harold.kinetics.api.PhysicsBody;
import sh.harold.kinetics.api.PhysicsMaterial;
import sh.harold.kinetics.api.PhysicsScene;
import sh.harold.kinetics.api.PhysicsShape;
import sh.harold.kinetics.api.Pose;
import sh.harold.kinetics.api.ResizeResult;
import sh.harold.kinetics.api.Vec3;
import sh.harold.kinetics.plugin.binding.BodyBinding;
import sh.harold.kinetics.plugin.material.ResolvedMaterial;

public final class JoltBody implements PhysicsBody {
    final JoltScene scene;
    final BodyId id;
    final BodySpec spec;
    final PhysicsShape definition;
    final ColliderFidelity fidelity;
    final BodyBinding binding;
    final boolean yawOnly;
    final AtomicBoolean destroyRequested = new AtomicBoolean();
    final AtomicReference<CompletableFuture<Optional<Entity>>> terminal = new AtomicReference<>();

    volatile BodyState state;
    volatile boolean destroyed;
    volatile int joltId;
    volatile Vec3 scale;
    volatile PhysicsMaterial requestedMaterial;
    volatile ResolvedMaterial material;
    volatile ShapeFactory.ShapeLease shapeLease;
    volatile ShapeFactory.CachedShape cachedShape;
    volatile double mass;

    // Coordinator-thread hints for commands that changed motion after the last published snapshot.
    boolean motionDirty;
    boolean poseDirty;
    boolean forceContinuousThisStep;

    JoltBody(JoltScene scene, BodyId id, BodySpec spec, PhysicsShape definition,
            ColliderFidelity fidelity, BodyBinding binding, boolean yawOnly, Pose initialPose) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.id = Objects.requireNonNull(id, "id");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.fidelity = Objects.requireNonNull(fidelity, "fidelity");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.yawOnly = yawOnly;
        this.scale = spec.scale();
        this.requestedMaterial = spec.material();
        this.state = new BodyState(initialPose, Vec3.ZERO, Vec3.ZERO, spec.scale(), 0.0,
                spec.motionType() == sh.harold.kinetics.api.MotionType.STATIC, 0L);
    }

    @Override
    public BodyId id() {
        return id;
    }

    @Override
    public PhysicsScene scene() {
        return scene;
    }

    @Override
    public BodyState state() {
        return state;
    }

    @Override
    public ColliderFidelity colliderFidelity() {
        return fidelity;
    }

    @Override
    public boolean destroyed() {
        return destroyed || destroyRequested.get();
    }

    @Override
    public void applyForce(Vec3 force) {
        requireDynamic("applyForce");
        scene.applyForce(this, Objects.requireNonNull(force, "force"), null);
    }

    @Override
    public void applyForceAtPoint(Vec3 force, Vec3 worldPoint) {
        requireDynamic("applyForceAtPoint");
        scene.applyForce(this, Objects.requireNonNull(force, "force"),
                Objects.requireNonNull(worldPoint, "worldPoint"));
    }

    @Override
    public void applyTorque(Vec3 torque) {
        requireDynamic("applyTorque");
        scene.applyTorque(this, Objects.requireNonNull(torque, "torque"));
    }

    @Override
    public void applyImpulse(Vec3 impulse) {
        requireDynamic("applyImpulse");
        scene.applyImpulse(this, Objects.requireNonNull(impulse, "impulse"), null);
    }

    @Override
    public void applyImpulseAtPoint(Vec3 impulse, Vec3 worldPoint) {
        requireDynamic("applyImpulseAtPoint");
        scene.applyImpulse(this, Objects.requireNonNull(impulse, "impulse"),
                Objects.requireNonNull(worldPoint, "worldPoint"));
    }

    @Override
    public void setLinearVelocity(Vec3 velocity) {
        requireMovable("setLinearVelocity");
        scene.setLinearVelocity(this, Objects.requireNonNull(velocity, "velocity"));
    }

    @Override
    public void setAngularVelocity(Vec3 velocity) {
        requireMovable("setAngularVelocity");
        scene.setAngularVelocity(this, Objects.requireNonNull(velocity, "velocity"));
    }

    @Override
    public void teleport(Pose pose) {
        scene.teleport(this, Objects.requireNonNull(pose, "pose"));
    }

    @Override
    public void setMaterial(PhysicsMaterial material) {
        scene.setMaterial(this, Objects.requireNonNull(material, "material"));
    }

    @Override
    public void wake() {
        requireMovable("wake");
        scene.wake(this, true);
    }

    @Override
    public void sleep() {
        requireMovable("sleep");
        scene.wake(this, false);
    }

    @Override
    public CompletionStage<ResizeResult> resize(Vec3 scale) {
        return scene.resize(this, Objects.requireNonNull(scale, "scale"));
    }

    @Override
    public CompletionStage<Void> destroy() {
        return scene.destroy(this);
    }

    @Override
    public CompletionStage<Optional<Entity>> release() {
        return scene.release(this);
    }

    private void requireDynamic(String operation) {
        if (spec.motionType() != sh.harold.kinetics.api.MotionType.DYNAMIC)
            throw new IllegalStateException(operation + " requires a dynamic body");
    }

    private void requireMovable(String operation) {
        if (spec.motionType() == sh.harold.kinetics.api.MotionType.STATIC)
            throw new IllegalStateException(operation + " is unavailable for static bodies");
    }
}
