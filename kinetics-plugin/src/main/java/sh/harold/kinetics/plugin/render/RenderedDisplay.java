package sh.harold.kinetics.plugin.render;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import sh.harold.kinetics.api.BodyState;
import sh.harold.kinetics.api.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Mutable renderer-owned model for one client-side display entity. */
public final class RenderedDisplay {
    public enum Kind {
        BLOCK,
        ITEM
    }

    final Kind kind;
    final int entityId;
    final UUID entityUuid;
    final World world;
    final List<EntityData<?>> baseMetadata;
    final MetadataSlots metadataSlots;
    final EntitySnapshot adoptedSnapshot;
    final BlockData blockData;
    final ItemStack itemStack;
    final ItemDisplay.ItemDisplayTransform itemTransform;
    final Vector3f modelTranslation;
    final Quaternionf modelRightRotation;
    final Vec3 modelOriginFromBody;
    final Map<UUID, ViewerState> viewers = new HashMap<>();

    BodyState lastState;
    long poseRevision = 1L;
    long transformRevision = 1L;
    long viewerPass;
    boolean destroyed;

    RenderedDisplay(
            Kind kind,
            int entityId,
            UUID entityUuid,
            World world,
            List<EntityData<?>> baseMetadata,
            MetadataSlots metadataSlots,
            EntitySnapshot adoptedSnapshot,
            BlockData blockData,
            ItemStack itemStack,
            ItemDisplay.ItemDisplayTransform itemTransform,
            Vector3f modelTranslation,
            Quaternionf modelRightRotation,
            Vec3 modelOriginFromBody,
            BodyState lastState
    ) {
        this.kind = kind;
        this.entityId = entityId;
        this.entityUuid = entityUuid;
        this.world = world;
        this.baseMetadata = List.copyOf(baseMetadata);
        this.metadataSlots = metadataSlots;
        this.adoptedSnapshot = adoptedSnapshot;
        this.blockData = blockData;
        this.itemStack = itemStack;
        this.itemTransform = itemTransform;
        this.modelTranslation = new Vector3f(modelTranslation);
        this.modelRightRotation = new Quaternionf(modelRightRotation);
        this.modelOriginFromBody = modelOriginFromBody;
        this.lastState = lastState;
    }

    public Kind kind() {
        return kind;
    }

    public int entityId() {
        return entityId;
    }

    public UUID entityUuid() {
        return entityUuid;
    }

    public World world() {
        return world;
    }

    public BodyState lastState() {
        return lastState;
    }

    public Set<UUID> viewerIds() {
        return Set.copyOf(viewers.keySet());
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    static final class ViewerState {
        DisplayLod lod;
        long lastPoseRevision;
        long lastTransformRevision;
        long seenPass;

        ViewerState(DisplayLod lod, long lastPoseRevision,
                long lastTransformRevision, long seenPass) {
            this.lod = lod;
            this.lastPoseRevision = lastPoseRevision;
            this.lastTransformRevision = lastTransformRevision;
            this.seenPass = seenPass;
        }
    }

    record MetadataSlots(
            int interpolationDuration,
            int teleportDuration,
            int translation,
            int scale,
            int leftRotation,
            int rightRotation
    ) {
    }
}
