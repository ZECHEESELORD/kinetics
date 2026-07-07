package sh.harold.kinetics.plugin.paper;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import sh.harold.kinetics.api.BodySpec;
import sh.harold.kinetics.api.BodyState;
import sh.harold.kinetics.api.ColliderFidelity;
import sh.harold.kinetics.api.PhysicsBody;
import sh.harold.kinetics.api.PhysicsShape;
import sh.harold.kinetics.api.Pose;
import sh.harold.kinetics.api.Rotation;
import sh.harold.kinetics.api.SceneSpec;
import sh.harold.kinetics.api.Vec3;
import sh.harold.kinetics.plugin.binding.BodyBinding;
import sh.harold.kinetics.plugin.binding.MobBinding;
import sh.harold.kinetics.plugin.binding.SceneBridge;
import sh.harold.kinetics.plugin.binding.VirtualDisplayBinding;
import sh.harold.kinetics.plugin.physics.JoltScene;
import sh.harold.kinetics.plugin.render.ChunkViewerCache;
import sh.harold.kinetics.plugin.render.RenderedDisplay;
import sh.harold.kinetics.plugin.render.VirtualDisplayRenderer;
import sh.harold.kinetics.plugin.shape.ColliderInference;
import sh.harold.kinetics.plugin.shape.InferredCollider;
import sh.harold.kinetics.plugin.terrain.PaperTerrainRuntime;

/** Paper entity, display, and terrain integration for one bounded Jolt scene. */
public final class PaperSceneBridge implements SceneBridge {
    private static final Vec3 BLOCK_MODEL_CENTRE = new Vec3(0.5, 0.5, 0.5);

    private final JavaPlugin plugin;
    private final SceneSpec spec;
    private final VirtualDisplayRenderer renderer;
    private final ChunkViewerCache viewerCache;
    private final Runnable closedCallback;
    private final PaperTerrainRuntime terrain;

    private volatile JoltScene scene;
    private volatile boolean closed;

    PaperSceneBridge(JavaPlugin plugin, SceneSpec spec, VirtualDisplayRenderer renderer,
            Runnable closedCallback) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.viewerCache = new ChunkViewerCache(spec.world());
        this.closedCallback = Objects.requireNonNull(closedCallback, "closedCallback");
        this.terrain = new PaperTerrainRuntime(plugin, spec, new PaperTerrainRuntime.ActivationSink() {
            @Override
            public CompletionStage<Void> replaceSection(long sectionKey, PhysicsShape shape, Pose worldPose) {
                JoltScene attached = PaperSceneBridge.this.scene;
                if (attached == null || attached.closed()) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Scene is not available"));
                }
                return attached.replaceTerrainSection(sectionKey, shape, worldPose);
            }

            @Override
            public void quarantine(BoundingBox worldBounds, boolean quarantined) {
                JoltScene attached = PaperSceneBridge.this.scene;
                if (attached != null && !attached.closed()) {
                    attached.quarantine(worldBounds, quarantined);
                }
            }
        });
    }

    SceneSpec sceneSpec() {
        return spec;
    }

    boolean attached() {
        return scene != null;
    }

    boolean closed() {
        return closed;
    }

    CompletionStage<Void> prepare(JoltScene scene) {
        requirePrimaryThread();
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Paper scene bridge is closed"));
        }
        if (this.scene != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Paper scene bridge is already attached"));
        }
        if (scene.spec() != spec) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Scene specification mismatch"));
        }
        this.scene = scene;
        return terrain.start();
    }

    void tick() {
        if (!closed && scene != null && !scene.closed()) {
            terrain.tick();
        }
    }

    @Override
    public CompletionStage<PhysicsBody> createBlockDisplay(JoltScene scene, BlockData data, BodySpec bodySpec) {
        Objects.requireNonNull(data, "blockData");
        Objects.requireNonNull(bodySpec, "bodySpec");
        return onMain(() -> createBlockOnMain(scene, data.clone(), bodySpec));
    }

    @Override
    public CompletionStage<PhysicsBody> createItemDisplay(JoltScene scene, ItemStack item, BodySpec bodySpec) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(bodySpec, "bodySpec");
        ItemStack copy = item.clone();
        return onMain(() -> createItemOnMain(scene, copy, bodySpec));
    }

    @Override
    public CompletionStage<PhysicsBody> adopt(JoltScene scene, Display display, BodySpec bodySpec) {
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(bodySpec, "bodySpec");
        return onMain(() -> adoptOnMain(scene, display, bodySpec));
    }

    @Override
    public CompletionStage<PhysicsBody> attach(JoltScene scene, Mob mob, BodySpec bodySpec) {
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(bodySpec, "bodySpec");
        return onMain(() -> attachOnMain(scene, mob, bodySpec));
    }

    @Override
    public void invalidate(BoundingBox worldBounds) {
        terrain.invalidate(worldBounds);
    }

    @Override
    public int dirtySections() {
        return terrain.dirtySections();
    }

    @Override
    public long packetsSent() {
        return renderer.packetsSent();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        terrain.close();
        closedCallback.run();
    }

    private CompletionStage<PhysicsBody> createBlockOnMain(
            JoltScene requestedScene, BlockData data, BodySpec bodySpec) {
        requireUsable(requestedScene);
        InferredCollider inferred = collider(data, bodySpec,
                new Location(spec.world(), bodySpec.pose().position().x(), bodySpec.pose().position().y(),
                        bodySpec.pose().position().z()));
        BodyState initial = initialState(bodySpec, bodySpec.pose());
        RenderedDisplay rendered = renderer.createBlock(spec.world(), data, initial);
        VirtualDisplayBinding binding = new VirtualDisplayBinding(renderer, rendered, data.getMaterial(), viewerCache, false);
        return createBound(requestedScene, bodySpec, bodySpec.pose(), inferred, binding, false);
    }

    private CompletionStage<PhysicsBody> createItemOnMain(
            JoltScene requestedScene, ItemStack item, BodySpec bodySpec) {
        requireUsable(requestedScene);
        InferredCollider inferred = collider(item, bodySpec);
        BodyState initial = initialState(bodySpec, bodySpec.pose());
        RenderedDisplay rendered = renderer.createItem(spec.world(), item, initial);
        VirtualDisplayBinding binding = new VirtualDisplayBinding(renderer, rendered, item.getType(), viewerCache, false);
        return createBound(requestedScene, bodySpec, bodySpec.pose(), inferred, binding, false);
    }

    private CompletionStage<PhysicsBody> adoptOnMain(
            JoltScene requestedScene, Display display, BodySpec requestedSpec) {
        requireUsable(requestedScene);
        if (!display.isValid() || display.getWorld() != spec.world()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Display must be valid and belong to the scene world"));
        }
        if (!(display instanceof BlockDisplay) && !(display instanceof ItemDisplay)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Only block and item displays can be adopted"));
        }
        if (display instanceof ItemDisplay itemDisplay
                && itemDisplay.getItemDisplayTransform() != ItemDisplay.ItemDisplayTransform.FIXED) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Adopted item displays must use the FIXED item transform"));
        }
        Location location = display.getLocation();
        if (Math.abs(location.getYaw()) > 1.0e-5 || Math.abs(location.getPitch()) > 1.0e-5) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Adopted displays must encode rotation in their Transformation, not entity yaw/pitch"));
        }

        Transformation transform = display.getTransformation();
        Vec3 scale;
        Rotation left;
        Rotation right;
        Vec3 translation;
        try {
            scale = positiveScale(transform.getScale());
            left = rotation(transform.getLeftRotation());
            right = rotation(transform.getRightRotation());
            translation = vector(transform.getTranslation(), "translation");
            if (hasShear(scale, right)) {
                throw new IllegalArgumentException(
                        "Nonuniform scale with a right rotation is an unsupported shear transform");
            }
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.failedFuture(failure);
        }

        InferredCollider base;
        Material material;
        Vec3 modelCentre;
        if (display instanceof BlockDisplay block) {
            base = collider(block.getBlock(), requestedSpec, location);
            material = block.getBlock().getMaterial();
            modelCentre = rotate(right, BLOCK_MODEL_CENTRE);
        } else {
            ItemStack item = ((ItemDisplay) display).getItemStack();
            base = collider(item, requestedSpec);
            material = item.getType();
            modelCentre = Vec3.ZERO;
        }

        PhysicsShape shape = rotatedShape(base.shape(), right);
        Vec3 scaledCentre = multiply(scale, modelCentre);
        Vec3 position = Vec3.of(location).add(translation).add(rotate(left, scaledCentre));
        Pose pose = new Pose(position, left);
        Vec3 modelOrigin = modelCentre.multiply(-1.0);
        BodySpec bodySpec = requestedSpec.toBuilder().pose(pose).scale(scale).build();
        BodyState initial = initialState(bodySpec, pose);

        RenderedDisplay rendered = renderer.adopt(display, initial, modelOrigin);
        VirtualDisplayBinding binding = new VirtualDisplayBinding(renderer, rendered, material, viewerCache, true);
        InferredCollider transformed = new InferredCollider(shape, base.fidelity());
        return createBound(requestedScene, bodySpec, pose, transformed, binding, false);
    }

    private CompletionStage<PhysicsBody> attachOnMain(
            JoltScene requestedScene, Mob mob, BodySpec requestedSpec) {
        requireUsable(requestedScene);
        if (!mob.isValid() || mob.getWorld() != spec.world()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Mob must be valid and belong to the scene world"));
        }
        try {
            MobBinding.requireUniformScale(requestedSpec.scale());
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.failedFuture(failure);
        }

        BoundingBox bounds = mob.getBoundingBox();
        PhysicsShape shape = requestedSpec.shape().orElseGet(() -> PhysicsShape.box(
                bounds.getWidthX(), bounds.getHeight(), bounds.getWidthZ()));
        Vec3 centre = new Vec3(
                (bounds.getMinX() + bounds.getMaxX()) * 0.5,
                (bounds.getMinY() + bounds.getMaxY()) * 0.5,
                (bounds.getMinZ() + bounds.getMaxZ()) * 0.5);
        double yaw = -Math.toRadians(mob.getYaw());
        Pose pose = new Pose(centre, new Rotation(0.0, Math.sin(yaw * 0.5), 0.0, Math.cos(yaw * 0.5)));
        BodySpec bodySpec = requestedSpec.toBuilder().pose(pose).build();
        MobBinding binding = new MobBinding(mob);
        InferredCollider collider = new InferredCollider(shape, ColliderFidelity.EXACT);
        return createBound(requestedScene, bodySpec, pose, collider, binding, true);
    }

    private CompletionStage<PhysicsBody> createBound(JoltScene requestedScene, BodySpec bodySpec, Pose pose,
            InferredCollider collider, BodyBinding binding, boolean yawOnly) {
        CompletionStage<PhysicsBody> creation;
        try {
            creation = requestedScene.createBoundBody(bodySpec, pose, collider.shape(),
                    collider.fidelity(), binding, yawOnly);
        } catch (Throwable failure) {
            binding.ownerCleanup();
            return CompletableFuture.failedFuture(failure);
        }
        return creation.whenComplete((body, failure) -> {
            if (failure != null) {
                binding.ownerCleanup();
            }
        });
    }

    private InferredCollider collider(BlockData data, BodySpec bodySpec, Location reference) {
        return bodySpec.shape().map(shape -> new InferredCollider(shape, ColliderFidelity.EXACT))
                .orElseGet(() -> ColliderInference.infer(data, reference));
    }

    private static InferredCollider collider(ItemStack item, BodySpec bodySpec) {
        return bodySpec.shape().map(shape -> new InferredCollider(shape, ColliderFidelity.EXACT))
                .orElseGet(() -> ColliderInference.infer(item));
    }

    private static BodyState initialState(BodySpec spec, Pose pose) {
        return new BodyState(pose, Vec3.ZERO, Vec3.ZERO, spec.scale(),
                spec.massKilograms().orElse(0.0), false, 0L);
    }

    private <T> CompletionStage<T> onMain(Supplier<CompletionStage<T>> operation) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return operation.get();
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed || !plugin.isEnabled()) {
            result.completeExceptionally(new IllegalStateException("Paper scene bridge is closed"));
            return result;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                operation.get().whenComplete((value, failure) -> {
                    if (failure == null) result.complete(value);
                    else result.completeExceptionally(failure);
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void requireUsable(JoltScene requestedScene) {
        requirePrimaryThread();
        if (closed || requestedScene != scene || requestedScene.closed()) {
            throw new IllegalStateException("Scene is not available through this Paper bridge");
        }
    }

    private static Vec3 positiveScale(Vector3f value) {
        Vec3 scale = vector(value, "scale");
        if (scale.x() < BodySpec.MIN_SCALE || scale.y() < BodySpec.MIN_SCALE
                || scale.z() < BodySpec.MIN_SCALE || scale.x() > BodySpec.MAX_SCALE
                || scale.y() > BodySpec.MAX_SCALE || scale.z() > BodySpec.MAX_SCALE) {
            throw new IllegalArgumentException("Display scale must be positive and between 0.01 and 64");
        }
        return scale;
    }

    private static Vec3 vector(Vector3f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)) {
            throw new IllegalArgumentException("Display " + name + " must be finite");
        }
        return new Vec3(value.x, value.y, value.z);
    }

    private static Rotation rotation(Quaternionf value) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y)
                || !Float.isFinite(value.z) || !Float.isFinite(value.w)) {
            throw new IllegalArgumentException("Display rotation must be finite");
        }
        return new Rotation(value.x, value.y, value.z, value.w);
    }

    private static boolean hasShear(Vec3 scale, Rotation right) {
        Vec3 first = multiply(scale, rotate(right, new Vec3(1, 0, 0)));
        Vec3 second = multiply(scale, rotate(right, new Vec3(0, 1, 0)));
        Vec3 third = multiply(scale, rotate(right, new Vec3(0, 0, 1)));
        return notOrthogonal(first, second) || notOrthogonal(first, third)
                || notOrthogonal(second, third);
    }

    private static boolean notOrthogonal(Vec3 first, Vec3 second) {
        double dot = first.x() * second.x() + first.y() * second.y() + first.z() * second.z();
        return Math.abs(dot) > 1.0e-6 * first.length() * second.length();
    }

    private static boolean identity(Rotation rotation) {
        return Math.abs(rotation.x()) < 1.0e-6 && Math.abs(rotation.y()) < 1.0e-6
                && Math.abs(rotation.z()) < 1.0e-6 && Math.abs(Math.abs(rotation.w()) - 1.0) < 1.0e-6;
    }

    private static PhysicsShape rotatedShape(PhysicsShape shape, Rotation rotation) {
        return identity(rotation) ? shape : PhysicsShape.compound(List.of(
                new PhysicsShape.Child(shape, new Pose(Vec3.ZERO, rotation))));
    }

    private static Vec3 multiply(Vec3 first, Vec3 second) {
        return new Vec3(first.x() * second.x(), first.y() * second.y(), first.z() * second.z());
    }

    private static Vec3 rotate(Rotation rotation, Vec3 vector) {
        double tx = 2.0 * (rotation.y() * vector.z() - rotation.z() * vector.y());
        double ty = 2.0 * (rotation.z() * vector.x() - rotation.x() * vector.z());
        double tz = 2.0 * (rotation.x() * vector.y() - rotation.y() * vector.x());
        return new Vec3(
                vector.x() + rotation.w() * tx + rotation.y() * tz - rotation.z() * ty,
                vector.y() + rotation.w() * ty + rotation.z() * tx - rotation.x() * tz,
                vector.z() + rotation.w() * tz + rotation.x() * ty - rotation.y() * tx);
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Paper scene bridge operation must run on the Paper thread");
        }
    }
}
