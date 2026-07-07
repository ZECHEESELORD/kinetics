package sh.harold.kinetics.plugin.render;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import sh.harold.kinetics.api.Vec3;

/** Resolves players once per occupied chunk and Paper tick for one scene. */
public final class ChunkViewerCache {
    private final World world;
    private final Map<Long, Entry> chunks = new HashMap<>();

    private long tick = Long.MIN_VALUE;
    private List<Player> worldPlayers = List.of();

    public ChunkViewerCache(World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    /**
     * Returns viewers for every chunk conservatively occupied by the scaled, rotated display.
     * {@code union} is used when the model bounds and tracking anchor need multiple chunks.
     */
    public Collection<? extends Player> viewers(Vec3 anchor, BoundingBox bounds, ArrayList<Player> union) {
        requirePrimaryThread();
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(union, "union");
        refreshTick();

        int minimumX = block(bounds.getMinX()) >> 4;
        int maximumX = block(bounds.getMaxX()) >> 4;
        int minimumZ = block(bounds.getMinZ()) >> 4;
        int maximumZ = block(bounds.getMaxZ()) >> 4;
        int anchorX = block(anchor.x()) >> 4;
        int anchorZ = block(anchor.z()) >> 4;
        if (minimumX == maximumX && minimumZ == maximumZ
                && minimumX == anchorX && minimumZ == anchorZ) {
            return viewers(Chunk.getChunkKey(minimumX, minimumZ));
        }

        union.clear();
        for (int chunkX = minimumX; chunkX <= maximumX; chunkX++) {
            for (int chunkZ = minimumZ; chunkZ <= maximumZ; chunkZ++) {
                addViewers(union, Chunk.getChunkKey(chunkX, chunkZ));
            }
        }
        if (anchorX < minimumX || anchorX > maximumX || anchorZ < minimumZ || anchorZ > maximumZ) {
            addViewers(union, Chunk.getChunkKey(anchorX, anchorZ));
        }
        return union;
    }

    private void addViewers(ArrayList<Player> union, long chunkKey) {
        for (Player player : viewers(chunkKey)) {
            if (!union.contains(player)) {
                union.add(player);
            }
        }
    }

    private List<Player> viewers(long chunkKey) {
        Entry entry = chunks.computeIfAbsent(chunkKey, ignored -> new Entry());
        if (entry.tick == tick) {
            return entry.viewers;
        }
        entry.viewers.clear();
        for (Player player : worldPlayers) {
            if (player.isOnline() && player.getWorld() == world && player.isChunkSent(chunkKey)) {
                entry.viewers.add(player);
            }
        }
        entry.tick = tick;
        return entry.viewers;
    }

    private void refreshTick() {
        long current = Bukkit.getCurrentTick();
        if (tick != current) {
            tick = current;
            worldPlayers = world.getPlayers();
        }
    }

    private static int block(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Chunk viewer resolution must run on the Paper thread");
        }
    }

    private static final class Entry {
        private final ArrayList<Player> viewers = new ArrayList<>();
        private long tick = Long.MIN_VALUE;
    }
}
