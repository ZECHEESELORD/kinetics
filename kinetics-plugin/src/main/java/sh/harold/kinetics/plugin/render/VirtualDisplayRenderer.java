package sh.harold.kinetics.plugin.render;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import sh.harold.kinetics.api.BodyState;
import sh.harold.kinetics.api.Rotation;
import sh.harold.kinetics.api.Vec3;
import sh.harold.kinetics.plugin.render.RenderedDisplay.MetadataSlots;
import sh.harold.kinetics.plugin.render.RenderedDisplay.ViewerState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** PacketEvents-backed BlockDisplay and ItemDisplay renderer. All methods run on the Paper thread. */
public final class VirtualDisplayRenderer {
    private static final AtomicInteger NEXT_VIRTUAL_ID = new AtomicInteger(2_000_000_000);
    private static final Set<Integer> LIVE_VIRTUAL_IDS = ConcurrentHashMap.newKeySet();
    private static final Vec3 BLOCK_MODEL_ORIGIN = new Vec3(-0.5, -0.5, -0.5);
    private static final double MIN_SCALE = 0.01;
    private static final double MAX_SCALE = 64.0;

    private final Map<RenderedDisplay.Kind, MetadataSlots> slotsByKind =
            new EnumMap<>(RenderedDisplay.Kind.class);
    private volatile long packetsSent;

    public long packetsSent() {
        return packetsSent;
    }

    public RenderedDisplay createBlock(World world, BlockData blockData, BodyState state) {
        return createBlock(world, blockData, state, BLOCK_MODEL_ORIGIN);
    }

    /**
     * Creates a virtual block display. {@code modelOriginFromBody} is the unscaled model-origin offset
     * from the body's centre of mass; the default full-cube value is {@code (-0.5, -0.5, -0.5)}.
     */
    public RenderedDisplay createBlock(
            World world,
            BlockData blockData,
            BodyState state,
            Vec3 modelOriginFromBody
    ) {
        requirePrimaryThread();
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(blockData, "blockData");
        validate(state, modelOriginFromBody);

        BlockDisplay probe = world.createEntity(location(world, anchor(state, modelOriginFromBody)), BlockDisplay.class);
        probe.setBlock(blockData.clone());
        configureProbe(probe, state);
        return fromProbe(probe, state, modelOriginFromBody, null);
    }

    public RenderedDisplay createItem(World world, ItemStack itemStack, BodyState state) {
        return createItem(world, itemStack, ItemDisplay.ItemDisplayTransform.FIXED, state, Vec3.ZERO);
    }

    public RenderedDisplay createItem(
            World world,
            ItemStack itemStack,
            ItemDisplay.ItemDisplayTransform itemTransform,
            BodyState state,
            Vec3 modelOriginFromBody
    ) {
        requirePrimaryThread();
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(itemStack, "itemStack");
        Objects.requireNonNull(itemTransform, "itemTransform");
        validate(state, modelOriginFromBody);

        ItemDisplay probe = world.createEntity(location(world, anchor(state, modelOriginFromBody)), ItemDisplay.class);
        probe.setItemStack(itemStack.clone());
        probe.setItemDisplayTransform(itemTransform);
        configureProbe(probe, state);
        return fromProbe(probe, state, modelOriginFromBody, null);
    }

    public RenderedDisplay adopt(Display display, BodyState state) {
        Vec3 origin = display instanceof BlockDisplay ? BLOCK_MODEL_ORIGIN : Vec3.ZERO;
        return adopt(display, state, origin);
    }

    /** Consumes an existing block/item display, retaining its snapshot for a later {@link #release}. */
    public RenderedDisplay adopt(Display display, BodyState state, Vec3 modelOriginFromBody) {
        requirePrimaryThread();
        Objects.requireNonNull(display, "display");
        validate(state, modelOriginFromBody);
        if (!(display instanceof BlockDisplay) && !(display instanceof ItemDisplay)) {
            throw new IllegalArgumentException("Only BlockDisplay and ItemDisplay can be adopted");
        }

        EntitySnapshot snapshot = Objects.requireNonNull(display.createSnapshot(),
                "Paper could not snapshot the adopted display");
        Transformation original = display.getTransformation();
        World world = display.getWorld();
        Entity metadataEntity = snapshot.createEntity(world);
        if (!(metadataEntity instanceof Display metadataProbe))
            throw new IllegalStateException("Adopted snapshot no longer creates a Display");
        configureProbe(metadataProbe, state, original.getTranslation(), original.getRightRotation());
        List<EntityData<?>> metadata = SpigotConversionUtil.getEntityMetadata(metadataProbe);

        RenderedDisplay rendered = model(
                display,
                nextVirtualId(),
                UUID.randomUUID(),
                metadata,
                state,
                modelOriginFromBody,
                snapshot
        );
        display.remove();
        return rendered;
    }

    /**
     * Reconciles viewers and publishes the latest pose. The supplied collection is the complete desired
     * viewer set for this display for this pass.
     */
    public void publish(
            RenderedDisplay display,
            BodyState state,
            Collection<? extends Player> desiredViewers,
            long serverTick
    ) {
        requirePrimaryThread();
        requireLive(display);
        Objects.requireNonNull(desiredViewers, "desiredViewers");
        validate(state, display.modelOriginFromBody);

        BodyState previousState = display.lastState;
        boolean transformChanged = !previousState.pose().rotation().equals(state.pose().rotation())
                || !previousState.scale().equals(state.scale());
        boolean positionChanged = !previousState.pose().position().equals(state.pose().position());
        if (positionChanged || transformChanged) display.poseRevision++;
        if (transformChanged) display.transformRevision++;
        display.lastState = state;
        long pass = ++display.viewerPass;
        Vec3 position = state.pose().position();

        for (Player player : desiredViewers) {
            if (player == null || !player.isOnline() || !player.getWorld().equals(display.world)) {
                continue;
            }

            double dx = player.getX() - position.x();
            double dy = player.getY() - position.y();
            double dz = player.getZ() - position.z();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            UUID playerId = player.getUniqueId();
            ViewerState viewer = display.viewers.get(playerId);
            if (viewer == null) {
                DisplayLod lod = DisplayLod.select(distanceSquared, null);
                sendSpawn(player, display, state, lod);
                display.viewers.put(playerId, new ViewerState(lod, display.poseRevision,
                        display.transformRevision, pass));
                continue;
            }

            DisplayLod previous = viewer.lod;
            DisplayLod lod = DisplayLod.select(distanceSquared, previous);
            viewer.lod = lod;
            viewer.seenPass = pass;
            boolean lodChanged = lod != previous;
            if ((lodChanged || viewer.lastPoseRevision != display.poseRevision)
                    && (lodChanged || lod.shouldPublish(serverTick, display.entityId))) {
                boolean sendTransform = lodChanged
                        || viewer.lastTransformRevision != display.transformRevision;
                sendUpdate(player, display, state, lod, sendTransform);
                viewer.lastPoseRevision = display.poseRevision;
                if (sendTransform) viewer.lastTransformRevision = display.transformRevision;
            }
        }

        Iterator<Map.Entry<UUID, ViewerState>> iterator = display.viewers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ViewerState> entry = iterator.next();
            if (entry.getValue().seenPass == pass) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                send(player, new WrapperPlayServerDestroyEntities(display.entityId));
            }
            iterator.remove();
        }
    }

    /** Despawns this virtual ID for every current viewer. This method is idempotent. */
    public void destroy(RenderedDisplay display) {
        requirePrimaryThread();
        Objects.requireNonNull(display, "display");
        if (display.destroyed) {
            return;
        }

        WrapperPlayServerDestroyEntities packet = new WrapperPlayServerDestroyEntities(display.entityId);
        Throwable firstFailure = null;
        for (UUID viewerId : display.viewers.keySet()) {
            Player player = Bukkit.getPlayer(viewerId);
            if (player == null || !player.isOnline()) continue;
            try {
                send(player, packet);
            } catch (Throwable failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        display.viewers.clear();
        LIVE_VIRTUAL_IDS.remove(display.entityId);
        display.destroyed = true;
        if (firstFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (firstFailure instanceof Error error) throw error;
        if (firstFailure != null) throw new IllegalStateException(firstFailure);
    }

    /** Materializes the display at an explicit final physics snapshot. */
    public Display release(RenderedDisplay display, BodyState state) {
        requirePrimaryThread();
        requireLive(display);
        validate(state, display.modelOriginFromBody);
        display.lastState = state;
        return release(display);
    }

    /** Despawns the virtual display and materializes a Bukkit display at its latest physical pose. */
    public Display release(RenderedDisplay display) {
        requirePrimaryThread();
        requireLive(display);
        BodyState state = display.lastState;
        Location at = location(display.world, anchor(state, display.modelOriginFromBody,
                display.modelTranslation));

        Display materialized;
        if (display.adoptedSnapshot != null) {
            Entity entity = display.adoptedSnapshot.createEntity(at);
            if (!(entity instanceof Display restored)) {
                entity.remove();
                throw new IllegalStateException("Adopted snapshot no longer creates a Display");
            }
            materialized = restored;
        } else if (display.kind == RenderedDisplay.Kind.BLOCK) {
            materialized = display.world.spawn(at, BlockDisplay.class, block -> {
                block.setBlock(display.blockData.clone());
                normalize(block);
            });
        } else {
            materialized = display.world.spawn(at, ItemDisplay.class, item -> {
                item.setItemStack(display.itemStack.clone());
                item.setItemDisplayTransform(display.itemTransform);
                normalize(item);
            });
        }
        applyTransform(materialized, state, display.modelTranslation, display.modelRightRotation);
        destroy(display);
        return materialized;
    }

    private RenderedDisplay fromProbe(
            Display probe,
            BodyState state,
            Vec3 modelOriginFromBody,
            EntitySnapshot snapshot
    ) {
        List<EntityData<?>> metadata = SpigotConversionUtil.getEntityMetadata(probe);
        return model(
                probe,
                nextVirtualId(),
                UUID.randomUUID(),
                metadata,
                state,
                modelOriginFromBody,
                snapshot
        );
    }

    private RenderedDisplay model(
            Display source,
            int entityId,
            UUID entityUuid,
            List<EntityData<?>> metadata,
            BodyState state,
            Vec3 modelOriginFromBody,
            EntitySnapshot snapshot
    ) {
        RenderedDisplay.Kind kind = source instanceof BlockDisplay
                ? RenderedDisplay.Kind.BLOCK : RenderedDisplay.Kind.ITEM;
        MetadataSlots slots = slotsByKind.computeIfAbsent(kind, ignored -> discoverSlots(metadata));
        Transformation transformation = source.getTransformation();
        BlockData blockData = source instanceof BlockDisplay block ? block.getBlock().clone() : null;
        ItemStack itemStack = source instanceof ItemDisplay item ? item.getItemStack().clone() : null;
        ItemDisplay.ItemDisplayTransform itemTransform = source instanceof ItemDisplay item
                ? item.getItemDisplayTransform() : null;
        RenderedDisplay display = new RenderedDisplay(
                kind,
                entityId,
                entityUuid,
                source.getWorld(),
                metadata,
                slots,
                snapshot,
                blockData,
                itemStack,
                itemTransform,
                transformation.getTranslation(),
                transformation.getRightRotation(),
                modelOriginFromBody,
                state
        );
        LIVE_VIRTUAL_IDS.add(entityId);
        return display;
    }

    private static void configureProbe(Display display, BodyState state) {
        Transformation transformation = display.getTransformation();
        configureProbe(display, state, transformation.getTranslation(), transformation.getRightRotation());
    }

    private static void configureProbe(
            Display display,
            BodyState state,
            Vector3f modelTranslation,
            Quaternionf modelRightRotation
    ) {
        normalize(display);
        applyTransform(display, state, modelTranslation, modelRightRotation);
    }

    private static void normalize(Display display) {
        display.setBillboard(Display.Billboard.FIXED);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
    }

    private static void applyTransform(
            Display display,
            BodyState state,
            Vector3f modelTranslation,
            Quaternionf modelRightRotation
    ) {
        Rotation rotation = state.pose().rotation();
        Vec3 scale = state.scale();
        display.setTransformation(new Transformation(
                new Vector3f(modelTranslation),
                new Quaternionf((float) rotation.x(), (float) rotation.y(), (float) rotation.z(), (float) rotation.w()),
                new Vector3f((float) scale.x(), (float) scale.y(), (float) scale.z()),
                new Quaternionf(modelRightRotation)
        ));
    }

    private static MetadataSlots discoverSlots(List<EntityData<?>> metadata) {
        int translation = -1;
        int scale = -1;
        int leftRotation = -1;
        int rightRotation = -1;
        Map<Integer, EntityDataType<?>> types = new java.util.HashMap<>();

        for (EntityData<?> data : metadata) {
            types.put(data.getIndex(), data.getType());
            if (data.getType() == EntityDataTypes.VECTOR3F) {
                if (translation == -1) {
                    translation = data.getIndex();
                } else if (scale == -1) {
                    scale = data.getIndex();
                }
            } else if (data.getType() == EntityDataTypes.QUATERNION) {
                if (leftRotation == -1) {
                    leftRotation = data.getIndex();
                } else if (rightRotation == -1) {
                    rightRotation = data.getIndex();
                }
            }
        }

        if (translation < 0 || scale < 0 || leftRotation < 0 || rightRotation < 0) {
            throw new IllegalStateException("PacketEvents did not expose the expected display transform metadata");
        }
        int interpolationDuration = types.get(translation - 2) == EntityDataTypes.INT ? translation - 2 : -1;
        int teleportDuration = types.get(translation - 1) == EntityDataTypes.INT ? translation - 1 : -1;
        return new MetadataSlots(
                interpolationDuration,
                teleportDuration,
                translation,
                scale,
                leftRotation,
                rightRotation
        );
    }

    private void sendSpawn(
            Player player,
            RenderedDisplay display,
            BodyState state,
            DisplayLod lod
    ) {
        EntityType type = display.kind == RenderedDisplay.Kind.BLOCK
                ? EntityTypes.BLOCK_DISPLAY : EntityTypes.ITEM_DISPLAY;
        Vec3 anchor = anchor(state, display.modelOriginFromBody, display.modelTranslation);
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                display.entityId,
                Optional.of(display.entityUuid),
                type,
                packetVector(anchor),
                0.0f,
                0.0f,
                0.0f,
                0,
                Optional.empty()
        );
        send(player, spawn);
        send(player, new WrapperPlayServerEntityMetadata(
                display.entityId,
                fullMetadata(display, state, lod.intervalTicks())
        ));
    }

    private void sendUpdate(
            Player player,
            RenderedDisplay display,
            BodyState state,
            DisplayLod lod,
            boolean sendTransform
    ) {
        send(player, new WrapperPlayServerEntityTeleport(
                display.entityId,
                packetVector(anchor(state, display.modelOriginFromBody, display.modelTranslation)),
                0.0f,
                0.0f,
                false
        ));
        if (sendTransform) {
            send(player, new WrapperPlayServerEntityMetadata(
                    display.entityId,
                    transformMetadata(display, state, lod.intervalTicks())
            ));
        }
    }

    private static List<EntityData<?>> fullMetadata(
            RenderedDisplay display,
            BodyState state,
            int interpolationTicks
    ) {
        ArrayList<EntityData<?>> metadata = new ArrayList<>(display.baseMetadata.size());
        for (EntityData<?> data : display.baseMetadata) {
            EntityData<?> replacement = replacement(display, state, interpolationTicks, data.getIndex());
            metadata.add(replacement == null ? data : replacement);
        }
        return metadata;
    }

    private static List<EntityData<?>> transformMetadata(
            RenderedDisplay display,
            BodyState state,
            int interpolationTicks
    ) {
        MetadataSlots slots = display.metadataSlots;
        ArrayList<EntityData<?>> metadata = new ArrayList<>(6);
        addIfPresent(metadata, durationData(slots.interpolationDuration(), interpolationTicks));
        addIfPresent(metadata, durationData(slots.teleportDuration(), interpolationTicks));
        metadata.add(replacement(display, state, interpolationTicks, slots.translation()));
        metadata.add(replacement(display, state, interpolationTicks, slots.scale()));
        metadata.add(replacement(display, state, interpolationTicks, slots.leftRotation()));
        metadata.add(replacement(display, state, interpolationTicks, slots.rightRotation()));
        return metadata;
    }

    private static EntityData<?> replacement(
            RenderedDisplay display,
            BodyState state,
            int interpolationTicks,
            int index
    ) {
        MetadataSlots slots = display.metadataSlots;
        if (index == slots.interpolationDuration() || index == slots.teleportDuration()) {
            return durationData(index, interpolationTicks);
        }
        if (index == slots.translation()) {
            Vector3f value = display.modelTranslation;
            return new EntityData<>(index, EntityDataTypes.VECTOR3F,
                    new com.github.retrooper.packetevents.util.Vector3f(value.x, value.y, value.z));
        }
        if (index == slots.scale()) {
            Vec3 value = state.scale();
            return new EntityData<>(index, EntityDataTypes.VECTOR3F,
                    new com.github.retrooper.packetevents.util.Vector3f(
                            (float) value.x(), (float) value.y(), (float) value.z()));
        }
        if (index == slots.leftRotation()) {
            Rotation value = state.pose().rotation();
            return new EntityData<>(index, EntityDataTypes.QUATERNION,
                    new Quaternion4f(
                            (float) value.x(), (float) value.y(), (float) value.z(), (float) value.w()));
        }
        if (index == slots.rightRotation()) {
            Quaternionf value = display.modelRightRotation;
            return new EntityData<>(index, EntityDataTypes.QUATERNION,
                    new Quaternion4f(value.x, value.y, value.z, value.w));
        }
        return null;
    }

    private static EntityData<Integer> durationData(int index, int interpolationTicks) {
        return index < 0 ? null : new EntityData<>(index, EntityDataTypes.INT, interpolationTicks);
    }

    private static void addIfPresent(List<EntityData<?>> target, EntityData<?> value) {
        if (value != null) {
            target.add(value);
        }
    }

    private static Vec3 anchor(BodyState state, Vec3 modelOriginFromBody) {
        return anchor(state, modelOriginFromBody, new Vector3f());
    }

    private static Vec3 anchor(BodyState state, Vec3 modelOriginFromBody, Vector3f modelTranslation) {
        Vec3 scale = state.scale();
        double x = modelOriginFromBody.x() * scale.x();
        double y = modelOriginFromBody.y() * scale.y();
        double z = modelOriginFromBody.z() * scale.z();
        Rotation q = state.pose().rotation();

        double tx = 2.0 * (q.y() * z - q.z() * y);
        double ty = 2.0 * (q.z() * x - q.x() * z);
        double tz = 2.0 * (q.x() * y - q.y() * x);
        double rx = x + q.w() * tx + q.y() * tz - q.z() * ty;
        double ry = y + q.w() * ty + q.z() * tx - q.x() * tz;
        double rz = z + q.w() * tz + q.x() * ty - q.y() * tx;
        return state.pose().position().add(new Vec3(
                rx - modelTranslation.x, ry - modelTranslation.y, rz - modelTranslation.z));
    }

    private static Vector3d packetVector(Vec3 value) {
        return new Vector3d(value.x(), value.y(), value.z());
    }

    private static Location location(World world, Vec3 value) {
        return new Location(world, value.x(), value.y(), value.z());
    }

    private static void validate(BodyState state, Vec3 modelOriginFromBody) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(modelOriginFromBody, "modelOriginFromBody");
        Vec3 scale = state.scale();
        if (scale.x() < MIN_SCALE || scale.y() < MIN_SCALE || scale.z() < MIN_SCALE
                || scale.x() > MAX_SCALE || scale.y() > MAX_SCALE || scale.z() > MAX_SCALE) {
            throw new IllegalArgumentException("display scale components must be between 0.01 and 64");
        }
    }

    private static void requireLive(RenderedDisplay display) {
        Objects.requireNonNull(display, "display");
        if (display.destroyed) {
            throw new IllegalStateException("RenderedDisplay is destroyed");
        }
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Virtual display rendering must run on the Paper thread");
        }
    }

    public static boolean ownsEntityId(int entityId) {
        return LIVE_VIRTUAL_IDS.contains(entityId);
    }

    private static int nextVirtualId() {
        int id = NEXT_VIRTUAL_ID.getAndDecrement();
        if (id <= 1_000_000_000) {
            throw new IllegalStateException("Kinetics exhausted its virtual entity ID range");
        }
        return id;
    }

    private void send(Player player, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        PlayerManager manager = PacketEvents.getAPI().getPlayerManager();
        manager.sendPacket(player, packet);
        packetsSent++;
    }
}
