package sh.harold.kinetics.plugin.paper;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import sh.harold.kinetics.api.ColliderRef;
import sh.harold.kinetics.api.InteractionAction;
import sh.harold.kinetics.api.InteractionEvent;
import sh.harold.kinetics.api.RaycastHit;
import sh.harold.kinetics.api.RaycastQuery;
import sh.harold.kinetics.api.Vec3;
import sh.harold.kinetics.plugin.physics.JoltScene;
import sh.harold.kinetics.plugin.render.VirtualDisplayRenderer;

/** Bridges observable Paper world mutations and player input into active scenes. */
public final class PaperEventRouter implements Listener, PacketListener {
    private static final double REACH = 6.0;
    private static final int BUKKIT_INPUT = 1;
    private static final int PACKET_INPUT = 2;

    private final JavaPlugin plugin;
    private final Supplier<List<JoltScene>> scenes;
    private final Map<InputKey, InputStamp> recentInput = new HashMap<>();

    public PaperEventRouter(JavaPlugin plugin, Supplier<List<JoltScene>> scenes) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) { invalidate(event.getBlockPlaced()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { invalidate(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) { invalidate(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) { invalidate(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) { invalidate(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) { invalidate(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) { invalidate(event.getSource(), event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) { invalidateStates(event.getBlocks()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) { invalidateStates(event.getBlocks()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidMove(BlockFromToEvent event) { invalidate(event.getBlock(), event.getToBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidLevel(FluidLevelChangeEvent event) { invalidate(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) { invalidate(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) { invalidate(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpenablePhysics(BlockPhysicsEvent event) {
        if (event.getBlock().getBlockData() instanceof Openable) {
            invalidate(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpenableRedstone(BlockRedstoneEvent event) {
        if (event.getBlock().getBlockData() instanceof Openable) {
            invalidate(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpenableInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (event.getAction().isRightClick() && clicked != null
                && clicked.getBlockData() instanceof Openable) {
            invalidate(clicked);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        List<Block> changed = new ArrayList<>(event.getBlocks());
        changed.add(event.getBlock());
        for (Block moved : event.getBlocks()) changed.add(moved.getRelative(event.getDirection()));
        invalidate(changed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        List<Block> changed = new ArrayList<>(event.getBlocks());
        changed.add(event.getBlock());
        for (Block moved : event.getBlocks()) changed.add(moved.getRelative(event.getDirection().getOppositeFace()));
        invalidate(changed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        List<Block> changed = new ArrayList<>(event.blockList());
        changed.add(event.getBlock());
        invalidate(changed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) { invalidate(event.blockList()); }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        int entityId;
        InteractionAction action;
        EquipmentSlot hand;
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            entityId = packet.getEntityId();
            action = packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK
                    ? InteractionAction.ATTACK : InteractionAction.INTERACT;
            hand = packet.getHand() == InteractionHand.OFF_HAND
                    ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        } else if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            entityId = new WrapperPlayClientAttack(event).getEntityId();
            action = InteractionAction.ATTACK;
            hand = EquipmentSlot.HAND;
        } else {
            return;
        }
        if (!VirtualDisplayRenderer.ownsEntityId(entityId)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player == null || !plugin.isEnabled()) return;
        int inputTick = Bukkit.getCurrentTick();
        Bukkit.getScheduler().runTask(plugin,
                () -> routeInteraction(player, hand, action, inputTick, PACKET_INPUT));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING
                && event.getAnimationType() != PlayerAnimationType.OFF_ARM_SWING) return;
        EquipmentSlot hand = event.getAnimationType() == PlayerAnimationType.OFF_ARM_SWING
                ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        routeInteraction(event.getPlayer(), hand, InteractionAction.ATTACK,
                Bukkit.getCurrentTick(), BUKKIT_INPUT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()
                || (event.isCancelled() && (event.getAction() != Action.RIGHT_CLICK_AIR
                || event.useItemInHand() == Event.Result.DENY))) return;
        routeInteraction(event.getPlayer(), event.getHand() == null ? EquipmentSlot.HAND : event.getHand(),
                InteractionAction.INTERACT, Bukkit.getCurrentTick(), BUKKIT_INPUT);
    }

    private void routeInteraction(Player player, EquipmentSlot hand, InteractionAction action,
            int inputTick, int inputSource) {
        int currentTick = Bukkit.getCurrentTick();
        if ((currentTick & 255) == 0)
            recentInput.entrySet().removeIf(entry -> entry.getValue().tick < currentTick - 2);
        InputKey inputKey = new InputKey(player.getUniqueId(), hand, action);
        InputStamp previous = recentInput.get(inputKey);
        if (previous != null) {
            int separation = Math.abs(previous.tick - inputTick);
            if (separation <= 1 && (previous.sources & inputSource) == 0) {
                recentInput.put(inputKey, new InputStamp(
                        Math.max(previous.tick, inputTick), previous.sources | inputSource));
                return;
            }
        }
        recentInput.put(inputKey, new InputStamp(inputTick, inputSource));

        AttributeInstance rangeAttribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        double reach = rangeAttribute == null ? REACH : rangeAttribute.getValue();
        if (!Double.isFinite(reach) || reach <= 0.0) {
            return;
        }

        World inputWorld = player.getWorld();
        Location eye = player.getEyeLocation();
        Vec3 origin = Vec3.of(eye);
        Vec3 direction = Vec3.of(eye.getDirection());
        List<PendingHit> pending = new ArrayList<>();
        for (JoltScene scene : scenes.get()) {
            if (scene.closed() || scene.world() != inputWorld
                    || !rayIntersects(scene.spec().bounds(), origin, direction, reach)) continue;
            try {
                CompletableFuture<Optional<RaycastHit>> future = scene
                        .raycast(RaycastQuery.all(origin, direction, reach))
                        .toCompletableFuture()
                        .exceptionally(ignored -> Optional.empty());
                pending.add(new PendingHit(scene, future));
            } catch (Throwable ignored) {
                // A failed or concurrently closing scene must not suppress hits from other scenes.
            }
        }
        if (pending.isEmpty()) return;

        CompletableFuture<?>[] futures = pending.stream().map(PendingHit::future)
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).thenRun(() -> runOnMain(() ->
                dispatchInteraction(player, inputWorld, hand, action, pending)));
    }

    private void dispatchInteraction(Player player, World inputWorld, EquipmentSlot hand,
            InteractionAction action, List<PendingHit> pending) {
        if (!player.isOnline() || player.getWorld() != inputWorld) {
            return;
        }
        PendingResult winner = null;
        for (PendingHit candidate : pending) {
            if (candidate.scene.closed() || candidate.scene.world() != inputWorld) {
                continue;
            }
            Optional<RaycastHit> optional = candidate.future.getNow(Optional.empty());
            if (optional.isEmpty()) continue;
            if (winner == null || optional.get().distance() < winner.hit.distance())
                winner = new PendingResult(candidate.scene, optional.get());
        }
        if (winner == null || !(winner.hit.collider() instanceof ColliderRef.Body body)
                || winner.scene.closed() || !winner.scene.interactable(body.id())) return;
        winner.scene.dispatchInteraction(new InteractionEvent(player, body.id(),
                winner.hit.point(), winner.hit.normal(), hand, action));
    }

    private void runOnMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private void invalidateStates(List<BlockState> states) {
        invalidate(states.stream().map(BlockState::getBlock).toList());
    }

    private void invalidate(Block... blocks) { invalidate(List.of(blocks)); }

    private void invalidate(List<Block> blocks) {
        if (blocks.isEmpty()) return;
        World world = blocks.getFirst().getWorld();
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Block block : blocks) {
            if (block.getWorld() != world) continue;
            minX = Math.min(minX, block.getX()); minY = Math.min(minY, block.getY()); minZ = Math.min(minZ, block.getZ());
            maxX = Math.max(maxX, block.getX() + 1); maxY = Math.max(maxY, block.getY() + 1); maxZ = Math.max(maxZ, block.getZ() + 1);
        }
        if (!Double.isFinite(minX)) return;
        BoundingBox dirty = new BoundingBox(minX, minY, minZ,
                maxX, maxY, maxZ);
        for (JoltScene scene : scenes.get()) {
            if (!scene.closed() && scene.world() == world && scene.spec().bounds().overlaps(dirty))
                scene.invalidate(dirty);
        }
    }

    private static boolean rayIntersects(BoundingBox box, Vec3 origin, Vec3 direction, double distance) {
        double low = 0, high = distance;
        double[] starts = {origin.x(), origin.y(), origin.z()};
        double[] deltas = {direction.x(), direction.y(), direction.z()};
        double[] minima = {box.getMinX(), box.getMinY(), box.getMinZ()};
        double[] maxima = {box.getMaxX(), box.getMaxY(), box.getMaxZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(deltas[axis]) < 1e-12) {
                if (starts[axis] < minima[axis] || starts[axis] > maxima[axis]) return false;
                continue;
            }
            double inverse = 1 / deltas[axis];
            double near = (minima[axis] - starts[axis]) * inverse;
            double far = (maxima[axis] - starts[axis]) * inverse;
            if (near > far) { double swap = near; near = far; far = swap; }
            low = Math.max(low, near); high = Math.min(high, far);
            if (low > high) return false;
        }
        return true;
    }

    private record InputKey(UUID playerId, EquipmentSlot hand, InteractionAction action) {}
    private record InputStamp(int tick, int sources) {}
    private record PendingHit(JoltScene scene, CompletableFuture<Optional<RaycastHit>> future) {}
    private record PendingResult(JoltScene scene, RaycastHit hit) {}
}
