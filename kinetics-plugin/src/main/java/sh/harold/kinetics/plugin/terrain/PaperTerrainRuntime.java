package sh.harold.kinetics.plugin.terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import sh.harold.kinetics.api.PhysicsShape;
import sh.harold.kinetics.api.Pose;
import sh.harold.kinetics.api.SceneSpec;
import sh.harold.kinetics.api.Vec3;

/**
 * Incrementally snapshots Paper terrain and asynchronously converts it into section compounds.
 * Bukkit world access is confined to {@link #tick()} on the primary thread.
 */
public final class PaperTerrainRuntime implements AutoCloseable {
    private static final double EPSILON = 1.0e-7;
    private static final int MAX_CHUNK_LOADS = 8;

    private final JavaPlugin plugin;
    private final SceneSpec spec;
    private final World world;
    private final BoundingBox sceneBounds;
    private final ActivationSink sink;
    private final Map<Long, SectionKey> sections = new HashMap<>();
    private final Map<Long, Chunk> ticketedChunks = new HashMap<>();
    private final Set<Long> initialSections = new HashSet<>();
    private final DirtySectionCoordinator<SectionKey> revisions;
    private final ExecutorService builder;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger buildsInFlight = new AtomicInteger();
    private final AtomicInteger activationsInFlight = new AtomicInteger();
    private final ArrayDeque<Long> pendingChunkLoads = new ArrayDeque<>();

    private volatile CaptureCursor capture;
    private volatile boolean closed;
    private int chunkLoadsInFlight;
    private Throwable chunkLoadFailure;
    private boolean pumpingChunkLoads;

    public PaperTerrainRuntime(JavaPlugin plugin, SceneSpec spec, ActivationSink sink) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.world = spec.world();
        this.sceneBounds = spec.bounds();
        this.sink = Objects.requireNonNull(sink, "sink");
        enumerateSections();
        this.revisions = new DirtySectionCoordinator<>(Math.max(
                DirtySectionCoordinator.DEFAULT_MAX_PENDING, sections.size() + 1));
        this.builder = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Kinetics-terrain-" + spec.name());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Loads and tickets the bounded chunk set without blocking the Paper thread. */
    public CompletionStage<Void> start() {
        if (!started.compareAndSet(false, true)) {
            return ready;
        }
        if (!spec.terrainCollision() || sections.isEmpty()) {
            ready.complete(null);
            return ready;
        }
        if (!Bukkit.isPrimaryThread()) {
            completeOnMain(this::beginLoading);
        } else {
            beginLoading();
        }
        return ready;
    }

    /** Performs no more than the configured capture budget of Bukkit-only work. */
    public void tick() {
        if (closed || !started.get() || !Bukkit.isPrimaryThread()) {
            return;
        }
        long deadline = System.nanoTime() + spec.terrainCaptureBudgetNanos();
        do {
            if (capture == null) {
                Optional<DirtySectionCoordinator.Work<SectionKey>> work = revisions.pollDirty();
                if (work.isEmpty()) {
                    return;
                }
                capture = new CaptureCursor(work.get(), sceneBounds);
            }
            if (!capture.advance(world, deadline)) {
                return;
            }
            CaptureCursor completed = capture;
            capture = null;
            if (!revisions.markCaptured(completed.work.section(), completed.work.revision())) {
                continue;
            }
            buildAsync(completed.snapshot());
        } while (System.nanoTime() < deadline);
    }

    /** Invalidates intersecting sections plus a one-block collision-neighbour halo. */
    public void invalidate(BoundingBox changedWorldBounds) {
        Objects.requireNonNull(changedWorldBounds, "changedWorldBounds");
        BoundingBox copy = changedWorldBounds.clone();
        Runnable action = () -> invalidateOnMain(copy);
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            completeOnMain(action);
        }
    }

    public int dirtySections() {
        return revisions.pendingCount() + buildsInFlight.get() + activationsInFlight.get()
                + (capture == null ? 0 : 1);
    }

    public CompletionStage<Void> ready() {
        return ready;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pendingChunkLoads.clear();
        builder.shutdownNow();
        if (!ready.isDone()) {
            ready.completeExceptionally(new IllegalStateException("Terrain runtime closed"));
        }
        Runnable release = this::releaseTickets;
        if (Bukkit.isPrimaryThread()) {
            release.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, release);
        }
    }

    private void beginLoading() {
        if (closed) {
            return;
        }
        Set<Long> chunks = new HashSet<>();
        for (SectionKey section : sections.values()) {
            chunks.add(Chunk.getChunkKey(section.chunkX, section.chunkZ));
        }
        pendingChunkLoads.addAll(chunks);
        pumpChunkLoads();
    }

    private void pumpChunkLoads() {
        if (pumpingChunkLoads) {
            return;
        }
        pumpingChunkLoads = true;
        try {
            while (!closed && chunkLoadFailure == null && chunkLoadsInFlight < MAX_CHUNK_LOADS
                    && !pendingChunkLoads.isEmpty()) {
                long key = pendingChunkLoads.removeFirst();
                int chunkX = (int) key;
                int chunkZ = (int) (key >> 32);
                chunkLoadsInFlight++;
                loadAndTicket(chunkX, chunkZ).whenComplete((chunk, failure) -> {
                    chunkLoadsInFlight--;
                    if (closed) {
                        return;
                    }
                    if (failure != null) {
                        if (chunkLoadFailure == null) {
                            chunkLoadFailure = failure;
                            pendingChunkLoads.clear();
                            releaseTickets();
                            ready.completeExceptionally(failure);
                        }
                        return;
                    }
                    if (pendingChunkLoads.isEmpty() && chunkLoadsInFlight == 0) {
                        beginInitialBuilds();
                    } else {
                        pumpChunkLoads();
                    }
                });
            }
        } finally {
            pumpingChunkLoads = false;
        }
    }

    private void beginInitialBuilds() {
        for (SectionKey section : sections.values()) {
            initialSections.add(section.packed());
            markDirty(section, 0);
        }
    }

    private CompletableFuture<Chunk> loadAndTicket(int chunkX, int chunkZ) {
        CompletableFuture<Chunk> result = new CompletableFuture<>();
        try {
            world.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, failure) -> {
                try {
                    completeOnMain(() -> {
                        try {
                            if (failure != null) {
                                result.completeExceptionally(failure);
                            } else if (closed || chunkLoadFailure != null) {
                                result.completeExceptionally(closed
                                        ? new IllegalStateException("Terrain runtime closed")
                                        : chunkLoadFailure);
                            } else {
                                chunk.addPluginChunkTicket(plugin);
                                ticketedChunks.put(Chunk.getChunkKey(chunkX, chunkZ), chunk);
                                result.complete(chunk);
                            }
                        } catch (Throwable callbackFailure) {
                            result.completeExceptionally(callbackFailure);
                        }
                    });
                } catch (Throwable callbackFailure) {
                    result.completeExceptionally(callbackFailure);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private void invalidateOnMain(BoundingBox changed) {
        if (closed || !spec.terrainCollision()) {
            return;
        }
        BoundingBox halo = changed.clone().expand(1.0);
        double minX = Math.max(sceneBounds.getMinX(), halo.getMinX());
        double minY = Math.max(sceneBounds.getMinY(), halo.getMinY());
        double minZ = Math.max(sceneBounds.getMinZ(), halo.getMinZ());
        double maxX = Math.min(sceneBounds.getMaxX(), halo.getMaxX());
        double maxY = Math.min(sceneBounds.getMaxY(), halo.getMaxY());
        double maxZ = Math.min(sceneBounds.getMaxZ(), halo.getMaxZ());
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) {
            return;
        }
        int minimumChunkX = floorToInt(minX) >> 4;
        int minimumSectionY = floorToInt(minY) >> 4;
        int minimumChunkZ = floorToInt(minZ) >> 4;
        int maximumChunkX = (ceilToInt(maxX) - 1) >> 4;
        int maximumSectionY = (ceilToInt(maxY) - 1) >> 4;
        int maximumChunkZ = (ceilToInt(maxZ) - 1) >> 4;
        for (int sectionY = minimumSectionY; sectionY <= maximumSectionY; sectionY++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
                    SectionKey section = sections.get(new SectionKey(chunkX, sectionY, chunkZ).packed());
                    if (section != null) {
                        markDirty(section, 1);
                    }
                }
            }
        }
    }

    private void markDirty(SectionKey section, int priority) {
        boolean alreadyQuarantined = revisions.status(section)
                .map(status -> status.stage() != DirtySectionCoordinator.Stage.ACTIVE)
                .orElse(false);
        revisions.markDirty(section, priority);
        if (!alreadyQuarantined) {
            sink.quarantine(section.bounds(sceneBounds), true);
        }
    }

    private void buildAsync(CapturedSection captured) {
        buildsInFlight.incrementAndGet();
        CompletableFuture.supplyAsync(() -> build(captured), builder)
                .whenComplete((built, failure) -> completeOnMain(() -> {
                    buildsInFlight.decrementAndGet();
                    if (closed) {
                        return;
                    }
                    SectionKey key = captured.work.section();
                    long revision = captured.work.revision();
                    if (failure != null) {
                        revisions.markDirty(key, captured.work.priority());
                        return;
                    }
                    if (!revisions.markBuilt(key, revision)) {
                        return; // A newer invalidation owns this section now.
                    }
                    CompletionStage<Void> activation;
                    try {
                        activation = Objects.requireNonNull(
                                sink.replaceSection(key.packed(), built.shape, built.pose),
                                "Activation sink returned null");
                    } catch (Throwable activationFailure) {
                        revisions.markDirty(key, captured.work.priority());
                        return;
                    }
                    activationsInFlight.incrementAndGet();
                    activation.whenComplete((ignored, activationFailure) -> completeOnMain(() -> {
                        activationsInFlight.decrementAndGet();
                        if (closed) {
                            return;
                        }
                        if (activationFailure != null) {
                            revisions.markDirty(key, captured.work.priority());
                            return;
                        }
                        if (!revisions.markActive(key, revision)) {
                            return;
                        }
                        sink.quarantine(key.bounds(sceneBounds), false);
                        initialSections.remove(key.packed());
                        if (initialSections.isEmpty() && !ready.isDone()) {
                            ready.complete(null);
                        }
                    }));
                }));
    }

    private static BuiltSection build(CapturedSection captured) {
        List<BoxVolume> boxes = new ArrayList<>(captured.irregular);
        mergeFullCubes(captured, boxes);
        if (boxes.isEmpty()) {
            return new BuiltSection(null, Pose.at(captured.anchor));
        }
        List<PhysicsShape.Child> children = new ArrayList<>(boxes.size());
        for (BoxVolume box : boxes) {
            Vec3 dimensions = new Vec3(box.maxX - box.minX, box.maxY - box.minY, box.maxZ - box.minZ);
            Vec3 centre = new Vec3(
                    (box.minX + box.maxX) * 0.5 - captured.anchor.x(),
                    (box.minY + box.maxY) * 0.5 - captured.anchor.y(),
                    (box.minZ + box.maxZ) * 0.5 - captured.anchor.z());
            children.add(new PhysicsShape.Child(
                    PhysicsShape.box(dimensions.x(), dimensions.y(), dimensions.z()), Pose.at(centre)));
        }
        return new BuiltSection(PhysicsShape.compound(children), Pose.at(captured.anchor));
    }

    private static void mergeFullCubes(CapturedSection captured, List<BoxVolume> boxes) {
        BitSet full = captured.full;
        BitSet used = new BitSet(full.length());
        int sx = captured.sizeX;
        int sy = captured.sizeY;
        int sz = captured.sizeZ;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int first = index(x, y, z, sx, sz);
                    if (!full.get(first) || used.get(first)) {
                        continue;
                    }
                    int endX = x + 1;
                    while (endX < sx && available(full, used, endX, y, z, sx, sz)) endX++;
                    int endZ = z + 1;
                    while (endZ < sz && planeAvailable(full, used, x, endX, y, endZ, sx, sz)) endZ++;
                    int endY = y + 1;
                    while (endY < sy && layerAvailable(full, used, x, endX, z, endZ, endY, sx, sz)) endY++;
                    for (int yy = y; yy < endY; yy++) {
                        for (int zz = z; zz < endZ; zz++) {
                            for (int xx = x; xx < endX; xx++) used.set(index(xx, yy, zz, sx, sz));
                        }
                    }
                    boxes.add(new BoxVolume(
                            captured.minX + x, captured.minY + y, captured.minZ + z,
                            captured.minX + endX, captured.minY + endY, captured.minZ + endZ));
                }
            }
        }
    }

    private static boolean available(BitSet full, BitSet used, int x, int y, int z, int sx, int sz) {
        int i = index(x, y, z, sx, sz);
        return full.get(i) && !used.get(i);
    }

    private static boolean planeAvailable(BitSet full, BitSet used, int startX, int endX,
                                          int y, int z, int sx, int sz) {
        for (int x = startX; x < endX; x++) {
            if (!available(full, used, x, y, z, sx, sz)) return false;
        }
        return true;
    }

    private static boolean layerAvailable(BitSet full, BitSet used, int startX, int endX,
                                          int startZ, int endZ, int y, int sx, int sz) {
        for (int z = startZ; z < endZ; z++) {
            if (!planeAvailable(full, used, startX, endX, y, z, sx, sz)) return false;
        }
        return true;
    }

    private static int index(int x, int y, int z, int sizeX, int sizeZ) {
        return (y * sizeZ + z) * sizeX + x;
    }

    private void enumerateSections() {
        int minX = floorToInt(sceneBounds.getMinX());
        int maxX = ceilToInt(sceneBounds.getMaxX()) - 1;
        int minY = Math.max(world.getMinHeight(), floorToInt(sceneBounds.getMinY()));
        int maxY = Math.min(world.getMaxHeight() - 1, ceilToInt(sceneBounds.getMaxY()) - 1);
        int minZ = floorToInt(sceneBounds.getMinZ());
        int maxZ = ceilToInt(sceneBounds.getMaxZ()) - 1;
        if (minY > maxY) {
            return;
        }
        for (int cy = minY >> 4; cy <= maxY >> 4; cy++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                    SectionKey key = new SectionKey(cx, cy, cz);
                    sections.put(key.packed(), key);
                }
            }
        }
    }

    private void completeOnMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else if (!closed && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private void releaseTickets() {
        for (Chunk chunk : ticketedChunks.values()) {
            chunk.removePluginChunkTicket(plugin);
        }
        ticketedChunks.clear();
    }

    private static int floorToInt(double value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Scene bounds exceed integer world coordinates");
        }
        return (int) Math.floor(value);
    }

    private static int ceilToInt(double value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Scene bounds exceed integer world coordinates");
        }
        return (int) Math.ceil(value);
    }

    public interface ActivationSink {
        /** A null shape removes the static section. */
        CompletionStage<Void> replaceSection(long sectionKey, PhysicsShape shape, Pose worldPose);

        /** Called while an intersecting body's terrain is not authoritative. */
        default void quarantine(BoundingBox worldBounds, boolean quarantined) {
        }
    }

    private static final class CaptureCursor {
        private final DirtySectionCoordinator.Work<SectionKey> work;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final Vec3 anchor;
        private final BoundingBox clip;
        private final BitSet full;
        private final List<BoxVolume> irregular = new ArrayList<>();
        private int cursor;

        private CaptureCursor(DirtySectionCoordinator.Work<SectionKey> work, BoundingBox sceneBounds) {
            this.work = work;
            BoundingBox bounds = work.section().bounds(sceneBounds);
            this.minX = floorToInt(bounds.getMinX());
            this.minY = floorToInt(bounds.getMinY());
            this.minZ = floorToInt(bounds.getMinZ());
            this.sizeX = ceilToInt(bounds.getMaxX()) - minX;
            this.sizeY = ceilToInt(bounds.getMaxY()) - minY;
            this.sizeZ = ceilToInt(bounds.getMaxZ()) - minZ;
            this.anchor = new Vec3(minX, minY, minZ);
            this.clip = sceneBounds.clone();
            this.full = new BitSet(sizeX * sizeY * sizeZ);
        }

        private boolean advance(World world, long deadline) {
            int total = sizeX * sizeY * sizeZ;
            Location location = new Location(world, 0, 0, 0);
            while (cursor < total) {
                int xOffset = cursor % sizeX;
                int yz = cursor / sizeX;
                int zOffset = yz % sizeZ;
                int yOffset = yz / sizeZ;
                cursor++;
                int x = minX + xOffset;
                int y = minY + yOffset;
                int z = minZ + zOffset;
                captureBlock(world.getBlockAt(x, y, z), location, xOffset, yOffset, zOffset);
                if (System.nanoTime() >= deadline) {
                    return false;
                }
            }
            return true;
        }

        private void captureBlock(Block block, Location location, int xOffset, int yOffset, int zOffset) {
            BlockData data = block.getBlockData();
            location.set(block.getX(), block.getY(), block.getZ());
            VoxelShape shape = data.getCollisionShape(location);
            Collection<BoundingBox> boxes = shape.getBoundingBoxes();
            if (boxes.isEmpty()) {
                return;
            }
            if (boxes.size() == 1) {
                BoundingBox box = boxes.iterator().next();
                if (isUnitCube(box) && block.getX() >= clip.getMinX() && block.getY() >= clip.getMinY()
                        && block.getZ() >= clip.getMinZ() && block.getX() + 1.0 <= clip.getMaxX()
                        && block.getY() + 1.0 <= clip.getMaxY() && block.getZ() + 1.0 <= clip.getMaxZ()) {
                    full.set(index(xOffset, yOffset, zOffset, sizeX, sizeZ));
                    return;
                }
            }
            for (BoundingBox local : boxes) {
                double minX = Math.max(clip.getMinX(), block.getX() + local.getMinX());
                double minY = Math.max(clip.getMinY(), block.getY() + local.getMinY());
                double minZ = Math.max(clip.getMinZ(), block.getZ() + local.getMinZ());
                double maxX = Math.min(clip.getMaxX(), block.getX() + local.getMaxX());
                double maxY = Math.min(clip.getMaxY(), block.getY() + local.getMaxY());
                double maxZ = Math.min(clip.getMaxZ(), block.getZ() + local.getMaxZ());
                if (maxX - minX > EPSILON && maxY - minY > EPSILON && maxZ - minZ > EPSILON) {
                    irregular.add(new BoxVolume(minX, minY, minZ, maxX, maxY, maxZ));
                }
            }
        }

        private CapturedSection snapshot() {
            return new CapturedSection(work, minX, minY, minZ, sizeX, sizeY, sizeZ,
                    anchor, (BitSet) full.clone(), List.copyOf(irregular));
        }
    }

    private record CapturedSection(DirtySectionCoordinator.Work<SectionKey> work,
                                   int minX, int minY, int minZ,
                                   int sizeX, int sizeY, int sizeZ,
                                   Vec3 anchor, BitSet full, List<BoxVolume> irregular) {
    }

    private record BuiltSection(PhysicsShape shape, Pose pose) {
    }

    private record BoxVolume(double minX, double minY, double minZ,
                             double maxX, double maxY, double maxZ) {
    }

    private record SectionKey(int chunkX, int sectionY, int chunkZ) {
        private long packed() {
            return ((long) (chunkX & 0x3ffffff) << 38)
                    | ((long) (chunkZ & 0x3ffffff) << 12)
                    | (sectionY & 0xfffL);
        }

        private BoundingBox bounds(BoundingBox scene) {
            double minX = Math.max(scene.getMinX(), chunkX * 16.0);
            double minY = Math.max(scene.getMinY(), sectionY * 16.0);
            double minZ = Math.max(scene.getMinZ(), chunkZ * 16.0);
            double maxX = Math.min(scene.getMaxX(), chunkX * 16.0 + 16.0);
            double maxY = Math.min(scene.getMaxY(), sectionY * 16.0 + 16.0);
            double maxZ = Math.min(scene.getMaxZ(), chunkZ * 16.0 + 16.0);
            return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static boolean isUnitCube(BoundingBox box) {
        return Math.abs(box.getMinX()) < EPSILON && Math.abs(box.getMinY()) < EPSILON
                && Math.abs(box.getMinZ()) < EPSILON && Math.abs(box.getMaxX() - 1.0) < EPSILON
                && Math.abs(box.getMaxY() - 1.0) < EPSILON && Math.abs(box.getMaxZ() - 1.0) < EPSILON;
    }
}
