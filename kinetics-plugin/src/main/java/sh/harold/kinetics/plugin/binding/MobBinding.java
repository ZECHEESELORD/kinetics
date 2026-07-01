package sh.harold.kinetics.plugin.binding;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import sh.harold.kinetics.api.BodyState;
import sh.harold.kinetics.api.Rotation;
import sh.harold.kinetics.api.Vec3;

/** Paper-thread binding for a yaw-constrained, physics-authoritative Bukkit mob. */
public final class MobBinding implements BodyBinding {
    private final Mob mob;
    private final World world;
    private final Material materialHint;
    private final boolean originalAi;
    private final boolean originalAware;
    private final boolean originalGravity;
    private final boolean originalCollidable;
    private final Vector originalVelocity;
    private final AttributeInstance scaleAttribute;
    private final double originalScaleBase;
    private final double centreAboveFeet;

    private BodyState lastState;
    private double appliedScale = Double.NaN;
    private Status status = Status.LIVE;

    public MobBinding(Mob mob) {
        this(mob, Material.BEEF);
    }

    public MobBinding(Mob mob, Material materialHint) {
        requirePrimaryThread();
        this.mob = Objects.requireNonNull(mob, "mob");
        this.materialHint = Objects.requireNonNull(materialHint, "materialHint");
        world = mob.getWorld();
        originalAi = mob.hasAI();
        originalAware = mob.isAware();
        originalGravity = mob.hasGravity();
        originalCollidable = mob.isCollidable();
        originalVelocity = mob.getVelocity().clone();
        scaleAttribute = mob.getAttribute(Attribute.SCALE);
        originalScaleBase = scaleAttribute == null ? 1.0 : scaleAttribute.getBaseValue();
        BoundingBox bounds = mob.getBoundingBox();
        centreAboveFeet = (bounds.getMinY() + bounds.getMaxY()) * 0.5 - mob.getY();

        mob.setVelocity(new Vector());
        mob.setAI(false);
        mob.setAware(false);
        mob.setGravity(false);
        mob.setCollidable(false);
    }

    @Override
    public Material materialHint() {
        return materialHint;
    }

    @Override
    public void publish(BodyState state) {
        requirePrimaryThread();
        if (status != Status.LIVE || !mob.isValid()) {
            return;
        }
        requireUniformScale(state.scale());
        applyPose(state);
        lastState = state;
    }

    @Override
    public CompletionStage<Optional<Entity>> release(BodyState state) {
        requirePrimaryThread();
        if (status == Status.DESTROYED || !mob.isValid()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (status == Status.LIVE) {
            requireUniformScale(state.scale());
            applyPose(state);
            restore();
            status = Status.RELEASED;
        }
        return CompletableFuture.completedFuture(Optional.of(mob));
    }

    @Override
    public void destroy() {
        requirePrimaryThread();
        if (status == Status.LIVE) {
            status = Status.DESTROYED;
            mob.remove();
        }
    }

    @Override
    public void ownerCleanup() {
        requirePrimaryThread();
        if (status != Status.LIVE) {
            return;
        }
        if (lastState != null && mob.isValid()) {
            applyPose(lastState);
        }
        restore();
        status = Status.RELEASED;
    }

    public static double requireUniformScale(Vec3 scale) {
        Objects.requireNonNull(scale, "scale");
        if (Double.compare(scale.x(), scale.y()) != 0 || Double.compare(scale.x(), scale.z()) != 0) {
            throw new IllegalArgumentException("Mob bodies require uniform scale");
        }
        return scale.x();
    }

    private void applyPose(BodyState state) {
        double scale = requireUniformScale(state.scale());
        if (scaleAttribute != null && Double.compare(appliedScale, scale) != 0) {
            scaleAttribute.setBaseValue(originalScaleBase * scale);
            appliedScale = scale;
        }

        Rotation rotation = state.pose().rotation();
        double yawRadians = Math.atan2(
                2.0 * (rotation.w() * rotation.y() + rotation.x() * rotation.z()),
                1.0 - 2.0 * (rotation.y() * rotation.y() + rotation.z() * rotation.z())
        );
        Location location = new Location(
                world,
                state.pose().position().x(),
                state.pose().position().y() - centreAboveFeet * scale,
                state.pose().position().z(),
                (float) -Math.toDegrees(yawRadians),
                0.0f
        );
        mob.teleport(location);
        mob.setVelocity(new Vector());
    }

    private void restore() {
        if (!mob.isValid()) {
            return;
        }
        if (scaleAttribute != null) {
            scaleAttribute.setBaseValue(originalScaleBase);
        }
        mob.setAI(originalAi);
        mob.setAware(originalAware);
        mob.setGravity(originalGravity);
        mob.setCollidable(originalCollidable);
        mob.setVelocity(originalVelocity);
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Mob bindings must run on the Paper thread");
        }
    }

    private enum Status {
        LIVE,
        RELEASED,
        DESTROYED
    }
}
