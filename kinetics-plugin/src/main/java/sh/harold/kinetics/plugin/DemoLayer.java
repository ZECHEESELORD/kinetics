package sh.harold.kinetics.plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import sh.harold.kinetics.api.BodyId;
import sh.harold.kinetics.api.BodySpec;
import sh.harold.kinetics.api.BodyState;
import sh.harold.kinetics.api.ContactPhase;
import sh.harold.kinetics.api.InteractionAction;
import sh.harold.kinetics.api.InteractionEvent;
import sh.harold.kinetics.api.KineticsContext;
import sh.harold.kinetics.api.KineticsService;
import sh.harold.kinetics.api.MotionQuality;
import sh.harold.kinetics.api.MotionType;
import sh.harold.kinetics.api.PhysicsBody;
import sh.harold.kinetics.api.PhysicsScene;
import sh.harold.kinetics.api.PhysicsShape;
import sh.harold.kinetics.api.Pose;
import sh.harold.kinetics.api.Rotation;
import sh.harold.kinetics.api.SceneSpec;
import sh.harold.kinetics.api.Subscription;
import sh.harold.kinetics.api.Vec3;

/** Optional, operator-driven showcase built entirely through the public Kinetics API. */
final class DemoLayer implements AutoCloseable {
    private final JavaPlugin plugin;
    private final KineticsService service;
    private long generation;
    private boolean pending;
    private DemoSession current;
    private Descriptor lastDescriptor;

    DemoLayer(JavaPlugin plugin, KineticsService service) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.service = Objects.requireNonNull(service, "service");
    }

    void start(Player player, DemoLayout.Mode mode,
            Optional<DemoCommandRequest.Coordinates> coordinates) {
        requireMainThread();
        long token = ++generation;
        pending = true;
        DemoSession closing = current;
        current = null;
        lastDescriptor = null;
        World world = player.getWorld();

        CompletionStage<Void> closed = closing == null
                ? CompletableFuture.completedFuture(null)
                : closing.closeAsync().exceptionally(ignored -> null);
        CompletionStage<Vec3> placement = resolvePlacement(world, mode, coordinates);

        closed.toCompletableFuture()
                .thenCombine(placement, (ignored, floor) -> floor)
                .whenComplete((floor, failure) -> runOnMain(() -> {
                    if (token != generation) return;
                    if (failure != null) {
                        pending = false;
                        reportFailure(player, failure);
                        return;
                    }
                    Descriptor descriptor = new Descriptor(mode, world, floor);
                    open(player, descriptor, token);
                }));
    }

    void reset(Player player) {
        requireMainThread();
        Descriptor descriptor = lastDescriptor;
        if (descriptor == null) {
            player.sendMessage(KineticsMessages.noDemo());
            return;
        }
        replaceResolved(player, descriptor);
    }

    void stop(Player player) {
        requireMainThread();
        boolean hadDemo = pending || current != null || lastDescriptor != null;
        long token = ++generation;
        pending = false;
        lastDescriptor = null;
        DemoSession closing = current;
        current = null;
        if (!hadDemo) {
            player.sendMessage(KineticsMessages.noDemo());
            return;
        }

        CompletionStage<Void> closed = closing == null
                ? CompletableFuture.completedFuture(null)
                : closing.closeAsync();
        closed.whenComplete((ignored, failure) -> runOnMain(() -> {
            if (token != generation) return;
            if (failure == null) player.sendMessage(KineticsMessages.demoStopped());
            else reportFailure(player, failure);
        }));
    }

    private void replaceResolved(Player player, Descriptor descriptor) {
        long token = ++generation;
        pending = true;
        DemoSession closing = current;
        current = null;
        lastDescriptor = null;
        CompletionStage<Void> closed = closing == null
                ? CompletableFuture.completedFuture(null)
                : closing.closeAsync().exceptionally(ignored -> null);
        closed.whenComplete((ignored, failure) -> runOnMain(() -> {
            if (token == generation) open(player, descriptor, token);
        }));
    }

    private void open(Player player, Descriptor descriptor, long token) {
        if (token != generation) return;
        Vec3 floor = descriptor.floor();
        player.sendMessage(KineticsMessages.demoStarting(descriptor.mode().displayName(),
                descriptor.world().getName(), floor.x(), floor.y(), floor.z()));

        DemoSession session;
        try {
            session = new DemoSession(plugin, service, descriptor);
        } catch (Throwable failure) {
            pending = false;
            reportFailure(player, failure);
            return;
        }
        current = session;
        lastDescriptor = descriptor;
        session.open().whenComplete((ignored, failure) -> runOnMain(() -> {
            if (token != generation || current != session) {
                session.closeAsync();
                return;
            }
            pending = false;
            if (failure != null) {
                current = null;
                lastDescriptor = null;
                session.closeAsync();
                reportFailure(player, failure);
                return;
            }
            player.sendMessage(KineticsMessages.demoStarted(descriptor.mode().displayName()));
            if (descriptor.mode() == DemoLayout.Mode.SAMPLER) {
                player.sendMessage(KineticsMessages.samplerGuide());
            }
        }));
    }

    private CompletionStage<Vec3> resolvePlacement(World world, DemoLayout.Mode mode,
            Optional<DemoCommandRequest.Coordinates> coordinates) {
        double x = coordinates.map(DemoCommandRequest.Coordinates::x).orElse(0.0);
        double z = coordinates.map(DemoCommandRequest.Coordinates::z).orElse(0.0);
        DemoLayout.Footprint footprint = DemoLayout.footprint(mode);
        try {
            validateHorizontalPlacement(world, mode, x, z);
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return loadChunks(world, footprint, x, z)
                .thenCompose(ignored -> onMain(() -> {
                    double y = coordinates.map(DemoCommandRequest.Coordinates::y)
                            .orElseGet(() -> surfaceY(world, x, z));
                    Vec3 floor = new Vec3(x, y, z);
                    validatePlacement(world, mode, floor);
                    return floor;
                }));
    }

    private static CompletionStage<Void> loadChunks(World world, DemoLayout.Footprint footprint,
            double x, double z) {
        int minimumChunkX = Math.floorDiv((int) Math.floor(x + footprint.minimumX()), 16);
        int maximumChunkX = Math.floorDiv((int) Math.floor(x + footprint.maximumX()), 16);
        int minimumChunkZ = Math.floorDiv((int) Math.floor(z + footprint.minimumZ()), 16);
        int maximumChunkZ = Math.floorDiv((int) Math.floor(z + footprint.maximumZ()), 16);
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                loads.add(world.getChunkAtAsync(chunkX, chunkZ, true));
            }
        }
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
    }

    private static double surfaceY(World world, double x, double z) {
        return world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z),
                HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1.0;
    }

    private static void validatePlacement(World world, DemoLayout.Mode mode, Vec3 floor) {
        BoundingBox bounds = DemoLayout.bounds(mode, floor);
        if (bounds.getMinY() < world.getMinHeight() || bounds.getMaxY() > world.getMaxHeight()) {
            throw new IllegalArgumentException("Demo bounds exceed the world's build height");
        }

        DemoLayout.Footprint footprint = DemoLayout.footprint(mode);
        int minimumX = (int) Math.floor(floor.x() + footprint.minimumX());
        int maximumX = (int) Math.ceil(floor.x() + footprint.maximumX());
        int minimumZ = (int) Math.floor(floor.z() + footprint.minimumZ());
        int maximumZ = (int) Math.ceil(floor.z() + footprint.maximumZ());
        int minimumY = (int) Math.floor(floor.y());
        int maximumY = (int) Math.ceil(floor.y() + footprint.clearanceHeight());
        for (int blockX = minimumX; blockX <= maximumX; blockX++) {
            for (int blockZ = minimumZ; blockZ <= maximumZ; blockZ++) {
                for (int blockY = minimumY; blockY < maximumY; blockY++) {
                    if (!world.getBlockAt(blockX, blockY, blockZ).isPassable()) {
                        throw new IllegalArgumentException(
                                "Demo construction space is obstructed near "
                                        + blockX + ", " + blockY + ", " + blockZ);
                    }
                }
            }
        }
    }

    private static void validateHorizontalPlacement(World world, DemoLayout.Mode mode,
            double x, double z) {
        BoundingBox bounds = DemoLayout.bounds(mode, new Vec3(x, 0.0, z));
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double halfSize = border.getSize() * 0.5;
        if (bounds.getMinX() < center.getX() - halfSize
                || bounds.getMaxX() > center.getX() + halfSize
                || bounds.getMinZ() < center.getZ() - halfSize
                || bounds.getMaxZ() > center.getZ() + halfSize) {
            throw new IllegalArgumentException("Demo bounds cross the world border");
        }
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        runOnMain(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void runOnMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private void reportFailure(Player player, Throwable failure) {
        Throwable detail = unwrap(failure);
        if (player.isOnline()) player.sendMessage(KineticsMessages.failure(detail));
        plugin.getLogger().warning("Kinetics demonstration failed: "
                + KineticsMessages.failureDetail(detail));
    }

    @Override
    public void close() {
        requireMainThread();
        generation++;
        pending = false;
        lastDescriptor = null;
        DemoSession closing = current;
        current = null;
        if (closing != null) closing.closeAsync();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Demo lifecycle requires the Paper thread");
        }
    }

    private record Descriptor(DemoLayout.Mode mode, World world, Vec3 floor) {
    }

    private static final class DemoSession {
        private static final Title.Times COUNTDOWN_TIMES = Title.Times.times(
                Duration.ofMillis(100), Duration.ofMillis(650), Duration.ofMillis(150));

        private final JavaPlugin plugin;
        private final KineticsService service;
        private final Descriptor descriptor;
        private final List<PhysicsBody> bodies = new ArrayList<>();
        private final Map<BodyId, PhysicsBody> interactiveBodies = new HashMap<>();
        private final Set<BodyId> resizingBodies = new HashSet<>();
        private final List<Entity> labels = new ArrayList<>();
        private final Set<BukkitTask> tasks = new HashSet<>();
        private final List<Subscription> subscriptions = new ArrayList<>();
        private KineticsContext context;
        private PhysicsScene scene;
        private boolean closed;
        private int effectTick = Integer.MIN_VALUE;
        private int effectsThisTick;
        private int lastSoundTick = Integer.MIN_VALUE;

        private DemoSession(JavaPlugin plugin, KineticsService service, Descriptor descriptor) {
            this.plugin = plugin;
            this.service = service;
            this.descriptor = descriptor;
        }

        CompletionStage<Void> open() {
            context = service.forPlugin(plugin);
            SceneSpec sceneSpec = new SceneSpec(
                    "demo-" + descriptor.mode().displayName(),
                    descriptor.world(),
                    DemoLayout.bounds(descriptor.mode(), descriptor.floor()),
                    SceneSpec.DEFAULT_CAPTURE_BUDGET_NANOS,
                    DemoLayout.maximumBodies(descriptor.mode()),
                    true);
            return context.createScene(sceneSpec).thenCompose(created -> {
                if (closed) return CompletableFuture.failedFuture(
                        new IllegalStateException("Demonstration was stopped during preparation"));
                scene = created;
                registerContactEffects();
                return descriptor.mode() == DemoLayout.Mode.SAMPLER
                        ? buildSampler() : buildSpectacle();
            });
        }

        private CompletionStage<Void> buildSampler() {
            Vec3 floor = descriptor.floor();
            List<PlannedBody> planned = new ArrayList<>();
            double rampAngle = Math.toRadians(-12.0);
            Rotation rampRotation = aroundZ(rampAngle);
            Material[] laneMaterials = {Material.ICE, Material.SLIME_BLOCK, Material.HONEY_BLOCK};
            double[] laneZ = {-5.5, -2.5, 0.5};

            for (int i = 0; i < laneMaterials.length; i++) {
                double z = floor.z() + laneZ[i];
                planned.add(new PlannedBody(createBlock(Material.SMOOTH_STONE,
                        BodySpec.builder()
                                .shape(PhysicsShape.box(1.0, 1.0, 1.0))
                                .pose(new Pose(new Vec3(floor.x() - 6.0, floor.y() + 3.3, z),
                                        rampRotation))
                                .scale(new Vec3(6.0, 0.4, 1.5))
                                .motionType(MotionType.STATIC)
                                .build()), false, ignored -> { }));

                planned.add(new PlannedBody(createBlock(laneMaterials[i],
                        BodySpec.inferred()
                                .pose(new Pose(new Vec3(floor.x() - 8.2, floor.y() + 4.5, z),
                                        Rotation.IDENTITY))
                                .scale(0.75)
                                .massKilograms(10.0)
                                .interactable(true)
                                .build()), true,
                        body -> body.setLinearVelocity(new Vec3(1.5, 0.0, 0.0))));
            }

            planned.add(new PlannedBody(createBlock(Material.COPPER_BLOCK,
                    BodySpec.inferred()
                            .pose(pose(floor, 3.5, 2.0, -4.5))
                            .scale(0.9)
                            .massKilograms(15.0)
                            .interactable(true)
                            .build()), true, ignored -> { }));
            planned.add(new PlannedBody(createItem(Material.SLIME_BALL,
                    BodySpec.inferred()
                            .pose(pose(floor, 6.0, 2.0, -4.5))
                            .scale(1.3)
                            .massKilograms(8.0)
                            .interactable(true)
                            .build()), true, ignored -> { }));
            planned.add(new PlannedBody(createItem(Material.BLAZE_ROD,
                    BodySpec.inferred()
                            .pose(pose(floor, 8.5, 2.0, -4.5))
                            .massKilograms(8.0)
                            .interactable(true)
                            .build()), true, ignored -> { }));

            planned.add(new PlannedBody(createBlock(Material.IRON_BLOCK,
                    BodySpec.builder()
                            .shape(PhysicsShape.box(1.0, 1.0, 1.0))
                            .pose(pose(floor, 5.5, 2.5, 3.5))
                            .scale(new Vec3(2.5, 0.35, 2.5))
                            .motionType(MotionType.KINEMATIC)
                            .sleepAllowed(false)
                            .build()), false, this::startKinematicMotion));

            planned.add(new PlannedBody(createBlock(Material.SAND,
                    BodySpec.inferred()
                            .pose(pose(floor, 11.0, 8.0, 5.5))
                            .massKilograms(12.0)
                            .interactable(true)
                            .build()), true, ignored -> { }));

            return sequence(planned.stream().map(PlannedBody::stage).toList())
                    .thenAccept(created -> {
                        for (int i = 0; i < planned.size(); i++) {
                            PlannedBody plan = planned.get(i);
                            PhysicsBody body = created.get(i);
                            bodies.add(body);
                            if (plan.interactive()) interactiveBodies.put(body.id(), body);
                            plan.ready().accept(body);
                        }
                        subscriptions.add(scene.onInteraction(
                                event -> interactiveBodies.containsKey(event.body()),
                                this::handleInteraction));
                        spawnLabel(KineticsMessages.materialLabel(), -6.0, 7.0, -2.5);
                        spawnLabel(KineticsMessages.interactionLabel(), 6.0, 4.5, -4.5);
                        spawnLabel(KineticsMessages.motionLabel(), 5.5, 4.5, 3.5);
                        spawnLabel(KineticsMessages.terrainLabel(), 11.0, 10.0, 5.5);
                    });
        }

        private CompletionStage<Void> buildSpectacle() {
            Vec3 floor = descriptor.floor();
            Vec3 towerCenter = floor.add(new Vec3(4.0, 0.0, 0.0));
            List<CompletionStage<PhysicsBody>> creation = new ArrayList<>(254);

            creation.add(createBlock(Material.POLISHED_DEEPSLATE,
                    BodySpec.builder()
                            .shape(PhysicsShape.box(1.0, 1.0, 1.0))
                            .pose(new Pose(towerCenter.add(new Vec3(0.0, 0.30, 0.0)),
                                    Rotation.IDENTITY))
                            .scale(new Vec3(12.0, 0.5, 12.0))
                            .motionType(MotionType.STATIC)
                            .build()));

            List<CompletionStage<PhysicsBody>> towerCreation = new ArrayList<>(252);
            for (DemoLayout.TowerCell cell : DemoLayout.towerCells()) {
                double x = towerCenter.x() + cell.x() - (DemoLayout.TOWER_SIZE - 1) * 0.5;
                double y = floor.y() + cell.y() + 1.05;
                double z = towerCenter.z() + cell.z() - (DemoLayout.TOWER_SIZE - 1) * 0.5;
                Material material = (cell.x() + cell.y() + cell.z()) % 7 == 0
                        ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
                CompletionStage<PhysicsBody> stage = createBlock(material,
                        BodySpec.builder()
                                .shape(PhysicsShape.box(1.0, 1.0, 1.0))
                                .pose(new Pose(new Vec3(x, y, z), Rotation.IDENTITY))
                                .scale(0.96)
                                .massKilograms(18.0)
                                .sleepAllowed(true)
                                .build());
                towerCreation.add(stage);
                creation.add(stage);
            }

            CompletionStage<PhysicsBody> projectileCreation = createItem(Material.ENDER_PEARL,
                    BodySpec.inferred()
                            .pose(new Pose(new Vec3(floor.x() - 16.0, floor.y() + 4.5, floor.z()),
                                    Rotation.IDENTITY))
                            .scale(3.5)
                            .massKilograms(8_000.0)
                            .gravityScale(0.0)
                            .motionQuality(MotionQuality.CONTINUOUS)
                            .sleepAllowed(false)
                            .build());
            creation.add(projectileCreation);

            return sequence(creation).thenAccept(created -> {
                bodies.addAll(created);
                List<PhysicsBody> tower = towerCreation.stream()
                        .map(stage -> stage.toCompletableFuture().join())
                        .toList();
                tower.forEach(PhysicsBody::sleep);
                PhysicsBody projectile = projectileCreation.toCompletableFuture().join();
                spawnLabel(KineticsMessages.towerLabel(), 4.0, 11.5, 0.0);
                scheduleCountdown(projectile);
            });
        }

        private void registerContactEffects() {
            subscriptions.add(scene.onContact(EnumSet.of(ContactPhase.IMPACT),
                    event -> event.relativeSpeed() >= 2.5,
                    event -> {
                        int tick = Bukkit.getCurrentTick();
                        if (effectTick != tick) {
                            effectTick = tick;
                            effectsThisTick = 0;
                        }
                        if (effectsThisTick++ >= 8) return;
                        Vec3 point = event.point();
                        descriptor.world().spawnParticle(Particle.CRIT,
                                point.x(), point.y(), point.z(), 4, 0.12, 0.12, 0.12, 0.02);
                        if (lastSoundTick == Integer.MIN_VALUE || tick - lastSoundTick >= 4) {
                            lastSoundTick = tick;
                            descriptor.world().playSound(
                                    new Location(descriptor.world(), point.x(), point.y(), point.z()),
                                    Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.BLOCKS,
                                    0.45f, 0.8f + Math.min(0.7f, (float) event.relativeSpeed() * 0.04f));
                        }
                    }));
        }

        private void handleInteraction(InteractionEvent event) {
            PhysicsBody body = interactiveBodies.get(event.body());
            if (body == null || body.destroyed()) return;
            double mass = Math.max(1.0, body.state().massKilograms());
            if (event.action() == InteractionAction.ATTACK) {
                if (event.player().isSneaking()) {
                    body.applyTorque(new Vec3(mass * 2.0, mass * 7.0, mass * 2.0));
                } else {
                    Vec3 direction = Vec3.of(event.player().getEyeLocation().getDirection())
                            .add(new Vec3(0.0, 0.15, 0.0)).normalized();
                    body.applyImpulseAtPoint(direction.multiply(mass * 5.5), event.point());
                }
                return;
            }

            if (event.player().isSneaking()) {
                if (!resizingBodies.add(body.id())) return;
                BodyState state = body.state();
                double current = state.scale().x();
                double target = current > 1.0 ? 0.65 : 1.35;
                if (target > current) {
                    // Resize is centre-anchored, so lift demo bodies before growing near terrain.
                    Pose pose = state.pose();
                    body.teleport(new Pose(pose.position().add(
                            new Vec3(0.0, target - current, 0.0)), pose.rotation()));
                }
                body.resize(new Vec3(target, target, target)).whenComplete((result, failure) -> {
                    resizingBodies.remove(body.id());
                    if (event.player().isOnline()) {
                        event.player().sendMessage(failure == null
                                ? KineticsMessages.resizeResult(result.applied())
                                : KineticsMessages.failure(failure));
                    }
                });
            } else {
                startForceBurst(body);
            }
        }

        private void startForceBurst(PhysicsBody body) {
            int[] remaining = {10};
            BukkitTask[] reference = new BukkitTask[1];
            reference[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (closed || body.destroyed() || remaining[0]-- <= 0) {
                    cancel(reference[0]);
                    return;
                }
                double mass = Math.max(1.0, body.state().massKilograms());
                body.applyForce(new Vec3(0.0, mass * 30.0, 0.0));
                Vec3 point = body.state().pose().position();
                descriptor.world().spawnParticle(Particle.END_ROD,
                        point.x(), point.y() - 0.4, point.z(), 2, 0.08, 0.08, 0.08, 0.01);
            }, 0L, 1L);
            tasks.add(reference[0]);
        }

        private void startKinematicMotion(PhysicsBody body) {
            Pose base = body.state().pose();
            int[] ticks = {0};
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (closed || body.destroyed()) return;
                double offset = Math.sin(ticks[0]++ * 0.075) * 2.5;
                body.teleport(new Pose(base.position().add(new Vec3(offset, 0.0, 0.0)),
                        base.rotation()));
            }, 1L, 1L);
            tasks.add(task);
        }

        private void scheduleCountdown(PhysicsBody projectile) {
            for (int index = 0; index < 3; index++) {
                int number = 3 - index;
                float pitch = 1.0f + index * 0.15f;
                schedule(index * 20L, () -> showTitle(KineticsMessages.countdown(number),
                        Sound.BLOCK_NOTE_BLOCK_HAT, pitch));
            }
            schedule(60L, () -> {
                projectile.setLinearVelocity(new Vec3(36.0, 0.0, 0.0));
                showTitle(KineticsMessages.launch(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f);
            });
        }

        private void showTitle(Component title, Sound sound, float pitch) {
            Location center = new Location(descriptor.world(), descriptor.floor().x(),
                    descriptor.floor().y(), descriptor.floor().z());
            for (Player player : descriptor.world().getPlayers()) {
                if (player.getLocation().distanceSquared(center) > 96.0 * 96.0) continue;
                player.showTitle(Title.title(title, Component.empty(), COUNTDOWN_TIMES));
                player.playSound(player.getLocation(), sound, SoundCategory.BLOCKS, 0.8f, pitch);
            }
        }

        private void schedule(long delay, Runnable action) {
            BukkitTask[] reference = new BukkitTask[1];
            reference[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                tasks.remove(reference[0]);
                if (!closed) action.run();
            }, delay);
            tasks.add(reference[0]);
        }

        private void cancel(BukkitTask task) {
            if (task == null) return;
            tasks.remove(task);
            task.cancel();
        }

        private CompletionStage<PhysicsBody> createBlock(Material material, BodySpec bodySpec) {
            return scene.createBlockDisplay(Bukkit.createBlockData(material), bodySpec);
        }

        private CompletionStage<PhysicsBody> createItem(Material material, BodySpec bodySpec) {
            return scene.createItemDisplay(new ItemStack(material), bodySpec);
        }

        private void spawnLabel(Component text, double x, double y, double z) {
            Vec3 floor = descriptor.floor();
            TextDisplay label = descriptor.world().spawn(
                    new Location(descriptor.world(), floor.x() + x, floor.y() + y, floor.z() + z),
                    TextDisplay.class, display -> {
                        display.setPersistent(false);
                        display.setBillboard(Display.Billboard.CENTER);
                        display.setSeeThrough(true);
                        display.setShadowed(true);
                        display.setLineWidth(240);
                        display.setViewRange(48.0f);
                        display.text(text);
                    });
            labels.add(label);
        }

        CompletionStage<Void> closeAsync() {
            if (closed) {
                return scene == null ? CompletableFuture.completedFuture(null) : scene.closeAsync();
            }
            closed = true;
            for (BukkitTask task : List.copyOf(tasks)) task.cancel();
            tasks.clear();
            for (Subscription subscription : List.copyOf(subscriptions)) subscription.close();
            subscriptions.clear();
            for (Entity label : List.copyOf(labels)) {
                if (label.isValid()) label.remove();
            }
            labels.clear();
            interactiveBodies.clear();
            resizingBodies.clear();
            bodies.clear();

            CompletionStage<Void> result = scene == null
                    ? CompletableFuture.completedFuture(null)
                    : scene.closeAsync();
            if (context != null) context.close();
            return result;
        }

        private static Pose pose(Vec3 floor, double x, double y, double z) {
            return new Pose(floor.add(new Vec3(x, y, z)), Rotation.IDENTITY);
        }

        private static Rotation aroundZ(double radians) {
            double half = radians * 0.5;
            return new Rotation(0.0, 0.0, Math.sin(half), Math.cos(half));
        }

        private static <T> CompletableFuture<List<T>> sequence(
                List<? extends CompletionStage<T>> stages) {
            CompletableFuture<?>[] futures = stages.stream()
                    .map(CompletionStage::toCompletableFuture)
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(futures).thenApply(ignored -> stages.stream()
                    .map(stage -> stage.toCompletableFuture().join())
                    .toList());
        }

        private record PlannedBody(CompletionStage<PhysicsBody> stage, boolean interactive,
                Consumer<PhysicsBody> ready) {
        }
    }
}
