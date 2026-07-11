package sh.harold.kinetics.plugin.physics;

import com.github.stephengold.joltjni.BatchBodyInterface;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyIdArray;
import com.github.stephengold.joltjni.BodyLockWrite;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.MotionProperties;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.PhysicsSettings;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.enumerate.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import sh.harold.kinetics.api.*;
import sh.harold.kinetics.plugin.binding.*;
import sh.harold.kinetics.plugin.material.*;

public final class JoltScene implements PhysicsScene {
    static final int STATIC_LAYER = 0;
    static final int MOVING_LAYER = 1;
    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private static final float STEP = 1f / 20f;

    final SceneSpec spec;
    final PhysicsSystem system;
    final BatchBodyInterface bodies;
    final Map<Long, JoltBody> byPublicId = new ConcurrentHashMap<>();
    final Map<Integer, JoltBody> byJoltId = new ConcurrentHashMap<>();
    final Vec3 origin;
    private final JoltRuntime runtime;
    private final SceneBridge bridge;
    private final Consumer<Runnable> mainExecutor;
    private final Runnable requestStep;
    private final Consumer<Throwable> errorHandler;
    private final ShapeFactory shapes = new ShapeFactory();
    private final ObjectLayerPairFilterTable pairs;
    private final BroadPhaseLayerInterfaceTable layers;
    private final ObjectVsBroadPhaseLayerFilterTable broadphase;
    private final SceneQueries queries;
    private final SceneContactCollector contacts;
    private final Object commandLock = new Object();
    private final ArrayDeque<QueuedCommand> commands = new ArrayDeque<>();
    private final LinkedHashMap<BodyId, JoltBody> bodyMap = new LinkedHashMap<>();
    private final Map<Long, Integer> terrain = new HashMap<>();
    private final Map<BodyId, Integer> quarantineCounts = new HashMap<>();
    private final Set<BodyId> quarantineWake = new HashSet<>();
    private final Set<BoundsKey> quarantineRegions = new HashSet<>();
    private final CopyOnWriteArrayList<ContactRegistration> contactListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<InteractionRegistration> interactionListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();
    private volatile boolean closed;
    private volatile boolean mainCleanupDone;
    private volatile long steps;
    private volatile long skipped;
    private volatile double lastMillis;
    private volatile double peakMillis;
    private final AtomicInteger recentContacts = new AtomicInteger();
    private boolean layoutDirty = true;
    private long sequence;
    private List<JoltBody> order = List.of();
    private BodyIdArray ids;
    private DoubleBuffer positions;
    private FloatBuffer rotations;
    private FloatBuffer linear;
    private FloatBuffer angular;
    private ByteBuffer active;

    public JoltScene(SceneSpec spec, JoltRuntime runtime, SceneBridge bridge,
            Consumer<Runnable> mainExecutor, Runnable requestStep,
            Consumer<Throwable> errorHandler) {
        this.spec = Objects.requireNonNull(spec);
        this.runtime = Objects.requireNonNull(runtime);
        this.bridge = Objects.requireNonNull(bridge);
        this.mainExecutor = Objects.requireNonNull(mainExecutor);
        this.requestStep = Objects.requireNonNull(requestStep);
        this.errorHandler = Objects.requireNonNull(errorHandler);
        BoundingBox box = spec.bounds();
        this.origin = new Vec3(midpoint(box.getMinX(), box.getMaxX()),
                midpoint(box.getMinY(), box.getMaxY()),
                midpoint(box.getMinZ(), box.getMaxZ()));
        long terrainCapacity = spec.terrainCollision()
                ? (long) terrainSectionCapacity(
                        box, spec.world().getMinHeight(), spec.world().getMaxHeight()) + 1L : 0L;
        long maximumLong = (long) spec.maximumBodies() + terrainCapacity + 6L;
        if (maximumLong > 1_000_000L) {
            throw new IllegalArgumentException("Scene bounds require too many native bodies: " + maximumLong);
        }
        int maximum = (int) maximumLong;
        int maximumPairs = (int) Math.min(Integer.MAX_VALUE, Math.max(65_536L, maximumLong * 12L));
        int maximumConstraints = (int) Math.min(Integer.MAX_VALUE,
                Math.max(20_480L, maximumLong * 6L));

        ObjectLayerPairFilterTable createdPairs = null;
        BroadPhaseLayerInterfaceTable createdLayers = null;
        ObjectVsBroadPhaseLayerFilterTable createdBroadphase = null;
        PhysicsSystem createdSystem = null;
        SceneQueries createdQueries = null;
        SceneContactCollector createdContacts = null;
        try {
            createdPairs = new ObjectLayerPairFilterTable(2);
            createdPairs.enableCollision(MOVING_LAYER, MOVING_LAYER);
            createdPairs.enableCollision(MOVING_LAYER, STATIC_LAYER);
            createdLayers = new BroadPhaseLayerInterfaceTable(2, 2);
            createdLayers.mapObjectToBroadPhaseLayer(STATIC_LAYER, 0);
            createdLayers.mapObjectToBroadPhaseLayer(MOVING_LAYER, 1);
            createdBroadphase = new ObjectVsBroadPhaseLayerFilterTable(
                    createdLayers, 2, createdPairs, 2);
            createdSystem = new PhysicsSystem();
            createdSystem.init(maximum, 0, maximumPairs, maximumConstraints,
                    createdLayers, createdBroadphase, createdPairs);
            try (PhysicsSettings settings = createdSystem.getPhysicsSettings()) {
                settings.setPenetrationSlop(0.001f);
                settings.setSpeculativeContactDistance(0.005f);
                createdSystem.setPhysicsSettings(settings);
            }
            createdSystem.setGravity(0, -9.80665f, 0);

            pairs = createdPairs;
            layers = createdLayers;
            broadphase = createdBroadphase;
            system = createdSystem;
            bodies = createdSystem.getBodyInterface();
            createdQueries = new SceneQueries(this);
            createdContacts = new SceneContactCollector(this);
            queries = createdQueries;
            contacts = createdContacts;
            system.setContactListener(contacts);
            createWalls(box);
            system.optimizeBroadPhase();
        } catch (Throwable failure) {
            SceneContactCollector contactsToClose = createdContacts;
            SceneQueries queriesToClose = createdQueries;
            PhysicsSystem systemToClose = createdSystem;
            ObjectVsBroadPhaseLayerFilterTable broadphaseToClose = createdBroadphase;
            BroadPhaseLayerInterfaceTable layersToClose = createdLayers;
            ObjectLayerPairFilterTable pairsToClose = createdPairs;
            if (systemToClose != null)
                failure = attempt(failure, () -> systemToClose.setContactListener(null));
            if (contactsToClose != null) failure = attempt(failure, contactsToClose::close);
            if (queriesToClose != null) failure = attempt(failure, queriesToClose::close);
            if (systemToClose != null) {
                failure = attempt(failure, systemToClose::destroyAllBodies);
                failure = attempt(failure, systemToClose::forgetMe);
                failure = attempt(failure, systemToClose::close);
            }
            failure = attempt(failure, shapes::close);
            if (broadphaseToClose != null) failure = attempt(failure, broadphaseToClose::close);
            if (layersToClose != null) failure = attempt(failure, layersToClose::close);
            if (pairsToClose != null) failure = attempt(failure, pairsToClose::close);
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error error) throw error;
            throw new CompletionException(failure);
        }
    }
    @Override public String name() { return spec.name(); }
    @Override public World world() { return spec.world(); }
    @Override public SceneSpec spec() { return spec; }
    @Override public boolean closed() { return closed || closeRequested.get(); }

    @Override
    public CompletionStage<PhysicsBody> createBody(BodySpec bodySpec) {
        PhysicsShape shape = bodySpec.shape().orElseThrow(
                () -> new IllegalArgumentException("Headless bodies require a shape"));
        return createBoundBody(bodySpec, bodySpec.pose(), shape,
                ColliderFidelity.EXACT, BodyBinding.HEADLESS, false);
    }

    public CompletionStage<PhysicsBody> createBoundBody(BodySpec bodySpec, Pose pose,
            PhysicsShape shape, ColliderFidelity fidelity, BodyBinding binding, boolean yawOnly) {
        CompletableFuture<PhysicsBody> future = new CompletableFuture<>();
        enqueue(() -> {
            JoltBody body = null;
            try {
                if (bodyMap.size() >= spec.maximumBodies()) throw new IllegalStateException("Scene body limit reached");
                body = new JoltBody(this, new BodyId(NEXT_ID.getAndIncrement()),
                        bodySpec, shape, fidelity, binding, yawOnly, pose);
                createNative(body, pose);
                try {
                    bodyMap.put(body.id, body);
                    byPublicId.put(body.id.value(), body);
                    byJoltId.put(body.joltId, body);
                    layoutDirty = true;
                } catch (Throwable failure) {
                    removeNative(body);
                    throw failure;
                }
                complete(future, body, null);
            } catch (Throwable failure) {
                complete(future, null, failure);
            }
        }, future);
        return future;
    }

    @Override public CompletionStage<PhysicsBody> createBlockDisplay(BlockData data, BodySpec bodySpec) {
        return bridge.createBlockDisplay(this, data, bodySpec);
    }
    @Override public CompletionStage<PhysicsBody> createItemDisplay(ItemStack item, BodySpec bodySpec) {
        return bridge.createItemDisplay(this, item, bodySpec);
    }
    @Override public CompletionStage<PhysicsBody> adopt(Display display, BodySpec bodySpec) {
        return bridge.adopt(this, display, bodySpec);
    }
    @Override public CompletionStage<PhysicsBody> attach(Mob mob, BodySpec bodySpec) {
        return bridge.attach(this, mob, bodySpec);
    }
    @Override public Optional<PhysicsBody> body(BodyId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(byPublicId.get(id.value()));
    }
    @Override public CompletionStage<Optional<RaycastHit>> raycast(RaycastQuery query) { return queries.cast(query); }
    @Override public void invalidate(BoundingBox bounds) { bridge.invalidate(bounds.clone()); }

    @Override
    public Subscription onContact(EnumSet<ContactPhase> phases, ContactFilter filter, ContactListener listener) {
        Objects.requireNonNull(phases, "phases");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(listener, "listener");
        if (phases.isEmpty()) throw new IllegalArgumentException("Contact phases cannot be empty");
        ContactRegistration registration = new ContactRegistration(phases, filter, listener);
        contactListeners.add(registration);
        return registration;
    }

    @Override
    public Subscription onInteraction(InteractionFilter filter, InteractionListener listener) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(listener, "listener");
        InteractionRegistration registration = new InteractionRegistration(filter, listener);
        interactionListeners.add(registration);
        return registration;
    }

    public void dispatchInteraction(InteractionEvent event) {
        for (InteractionRegistration registration : interactionListeners) {
            try {
                if (registration.active() && registration.filter.test(event))
                    registration.listener.onInteraction(event);
            } catch (Throwable failure) {
                errorHandler.accept(failure);
            }
        }
    }

    boolean wantsContact(ContactPhase phase) {
        for (ContactRegistration registration : contactListeners) {
            if (registration.active() && registration.phases.contains(phase)) return true;
        }
        return false;
    }

    void recordContact() {
        recentContacts.incrementAndGet();
    }

    void dispatchContact(ContactEvent event) {
        mainExecutor.accept(() -> {
            if (closed()) return;
            for (ContactRegistration registration : contactListeners) {
                try {
                    if (registration.active() && registration.phases.contains(event.phase())
                            && registration.filter.test(event)) registration.listener.onContact(event);
                } catch (Throwable failure) {
                    errorHandler.accept(failure);
                }
            }
        });
    }

    @Override
    public SceneStats stats() {
        int backlog;
        synchronized (commandLock) { backlog = commands.size(); }
        int awake = 0;
        for (JoltBody body : byPublicId.values()) if (!body.state.sleeping()) awake++;
        return new SceneStats(steps, lastMillis, peakMillis, byPublicId.size(), awake,
                recentContacts.get(), backlog, bridge.dirtySections(), bridge.packetsSent(),
                shapes.hits(), shapes.misses(), skipped);
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        if (closed) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> created = new CompletableFuture<>();
        if (!closeFuture.compareAndSet(null, created)) return closeFuture.get();
        closeRequested.set(true);
        try {
            enqueue(() -> {
                try {
                    closeNative();
                } catch (Throwable failure) {
                    complete(created, null, failure);
                }
            }, true);
        } catch (Throwable failure) {
            complete(created, null, failure);
        }
        return created;
    }

    public void stepNative() {
        if (closed) return;
        long start = System.nanoTime();
        drainCommands();
        if (closed) return;
        recentContacts.set(0);
        enforceQuarantines();
        applyDragAndCcd();
        int errors = system.update(STEP, 6, runtime.allocator(), runtime.jobs());
        if (errors != 0) throw new IllegalStateException("Jolt update error 0x" + Integer.toHexString(errors));
        PublishedSnapshot snapshot = capture();
        steps++;
        lastMillis = (System.nanoTime() - start) / 1_000_000d;
        peakMillis = Math.max(peakMillis, lastMillis);
        mainExecutor.accept(() -> publish(snapshot));
    }

    public void markSkippedTick() { skipped++; }

    public CompletionStage<Void> replaceTerrainSection(long key, PhysicsShape shape, Pose pose) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        enqueue(() -> {
            Integer replacement = null;
            try {
                if (shape != null) {
                    try (ShapeFactory.ShapeLease lease = shapes.acquire(shape, Vec3.ONE, Vec3.ZERO)) {
                        replacement = createStatic(lease.shape(), pose);
                    }
                }
                Integer old = terrain.get(key);
                if (old != null) {
                    if (bodies.isAdded(old)) bodies.removeBody(old);
                    bodies.destroyBody(old);
                }
                if (replacement == null) terrain.remove(key);
                else terrain.put(key, replacement);
                complete(future, null, null);
            } catch (Throwable failure) {
                if (replacement != null) {
                    int failedReplacement = replacement;
                    Throwable cleanupFailure = attempt(null, () -> {
                        if (bodies.isAdded(failedReplacement)) bodies.removeBody(failedReplacement);
                    });
                    cleanupFailure = attempt(cleanupFailure, () -> bodies.destroyBody(failedReplacement));
                    if (cleanupFailure != null && cleanupFailure != failure)
                        failure.addSuppressed(cleanupFailure);
                }
                complete(future, null, failure);
            }
        }, future);
        return future;
    }

    public void quarantine(BoundingBox worldBounds, boolean quarantined) {
        BoundsKey key = new BoundsKey(worldBounds);
        enqueue(() -> {
            boolean changed = quarantined ? quarantineRegions.add(key) : quarantineRegions.remove(key);
            if (!changed) return;
            BoundingBox bounds = key.bounds();
            for (JoltBody body : bodyMap.values()) {
                if (body.spec.motionType() == MotionType.STATIC || !bodyBounds(body).overlaps(bounds)) continue;
                if (quarantined) {
                    if (!quarantineCounts.containsKey(body.id) && bodies.isActive(body.joltId))
                        quarantineWake.add(body.id);
                    quarantineCounts.merge(body.id, 1, Integer::sum);
                    bodies.deactivateBody(body.joltId);
                } else {
                    Integer count = quarantineCounts.get(body.id);
                    if (count == null) continue;
                    if (count <= 1) {
                        quarantineCounts.remove(body.id);
                        if (quarantineWake.remove(body.id)) bodies.activateBody(body.joltId);
                    } else {
                        quarantineCounts.put(body.id, count - 1);
                    }
                }
            }
        });
    }

    void applyForce(JoltBody body, Vec3 force, Vec3 point) {
        com.github.stephengold.joltjni.Vec3 nativeForce = nativeVec(force);
        enqueueBody(body, () -> {
            if (point == null) bodies.addForce(body.joltId, nativeForce);
            else bodies.addForce(body.joltId, nativeForce, local(point));
            if (body.spec.motionQuality() == MotionQuality.AUTO)
                body.forceContinuousThisStep = true;
        });
    }

    void applyTorque(JoltBody body, Vec3 torque) {
        com.github.stephengold.joltjni.Vec3 nativeTorque = nativeVec(torque);
        enqueueBody(body, () -> bodies.addTorque(body.joltId, nativeTorque));
    }

    void applyImpulse(JoltBody body, Vec3 impulse, Vec3 point) {
        com.github.stephengold.joltjni.Vec3 nativeImpulse = nativeVec(impulse);
        enqueueBody(body, () -> {
            if (point == null) bodies.addImpulse(body.joltId, nativeImpulse);
            else bodies.addImpulse(body.joltId, nativeImpulse, local(point));
            body.motionDirty = true;
        });
    }

    void setLinearVelocity(JoltBody body, Vec3 velocity) {
        com.github.stephengold.joltjni.Vec3 limited = nativeVec(limitMagnitude(velocity, 128.0));
        enqueueBody(body, () -> {
            bodies.setLinearVelocity(body.joltId, limited);
            body.motionDirty = true;
        });
    }

    void setAngularVelocity(JoltBody body, Vec3 velocity) {
        enqueueBody(body, () -> bodies.setAngularVelocity(body.joltId,
                nativeVec(limitMagnitude(velocity, maxAngular(body.cachedShape)))));
    }

    void teleport(JoltBody body, Pose pose) { enqueueBody(body, () -> {
        boolean wasActive = bodies.isActive(body.joltId);
        bodies.removeBody(body.joltId);
        try {
            if (insideScene(body.cachedShape, pose)
                    && !queries.overlaps(body.cachedShape, pose, body.joltId)) {
                bodies.setPositionAndRotation(body.joltId, local(pose.position()),
                        nativeQuat(pose.rotation()), EActivation.DontActivate);
                body.poseDirty = true;

            }
        } finally {
            bodies.addBody(body.joltId,
                    wasActive ? EActivation.Activate : EActivation.DontActivate);
        }
    }); }

    /** Returns an immutable view of the latest published body state for diagnostics. */
    public Optional<DebugBodySnapshot> debugSnapshot(BodyId id) {
        Objects.requireNonNull(id, "id");
        JoltBody body = byPublicId.get(id.value());
        if (body == null || body.destroyed()) return Optional.empty();
        BodyState state = body.state;
        ShapeFactory.CachedShape cached = body.cachedShape;
        if (cached == null) return Optional.empty();
        Pose pose = state.pose();
        Vec3 centreOfMass = pose.position().add(rotate(pose.rotation(), cached.centreOfMass()));
        return Optional.of(new DebugBodySnapshot(body.id, body.definition, pose,
                cached.physicalScale(), centreOfMass, state.linearVelocity()));
    }

    public boolean interactable(BodyId id) {
        JoltBody body = byPublicId.get(id.value());
        return body != null && !body.destroyed && body.spec.interactable();
    }
    void wake(JoltBody body, boolean wake) { enqueueBody(body, () -> {
        if (quarantineCounts.containsKey(body.id)) {
            if (wake) quarantineWake.add(body.id); else quarantineWake.remove(body.id);
            bodies.deactivateBody(body.joltId);
        } else if (wake) bodies.activateBody(body.joltId);
        else bodies.deactivateBody(body.joltId);
    }); }
    void setMaterial(JoltBody body, PhysicsMaterial requested) {
        ResolvedMaterial resolved = MaterialResolver.resolve(body.binding.materialHint(), requested);
        friction(resolved);
        nativeFloat(resolved.linearDamping(), "linearDamping");
        nativeFloat(resolved.angularDamping(), "angularDamping");
        if (body.spec.motionType() == MotionType.DYNAMIC && body.spec.massKilograms().isEmpty()) {
            positiveNativeFloat(body.cachedShape.massAtDensity(
                    resolved.densityKilogramsPerCubicMetre()), "massKilograms");
        }
        enqueueBody(body, () -> {
            body.requestedMaterial = requested;
            body.material = resolved;
            bodies.setFriction(body.joltId, friction(resolved));
            bodies.setRestitution(body.joltId, (float) resolved.restitution());
            if (body.spec.motionType() != MotionType.STATIC) {
                try (BodyLockWrite lock = new BodyLockWrite(system.getBodyLockInterface(), body.joltId)) {
                    if (lock.succeeded()) {
                        MotionProperties motion = lock.getBody().getMotionPropertiesUnchecked();
                        motion.setLinearDamping(nativeFloat(resolved.linearDamping(), "linearDamping"));
                        motion.setAngularDamping(nativeFloat(resolved.angularDamping(), "angularDamping"));
                    }
                }
            }
            if (body.spec.motionType() == MotionType.DYNAMIC && body.spec.massKilograms().isEmpty()) {
                body.mass = body.cachedShape.massAtDensity(resolved.densityKilogramsPerCubicMetre());
                applyMass(body, body.cachedShape, body.mass);
            }
        });
    }

    CompletionStage<ResizeResult> resize(JoltBody body, Vec3 scale) {
        CompletableFuture<ResizeResult> future = new CompletableFuture<>();
        enqueue(() -> {
            ShapeFactory.ShapeLease replacementLease = null;
            try {
                validateScale(scale);
                if (body.yawOnly && (scale.x() != scale.y() || scale.x() != scale.z()))
                    throw new IllegalArgumentException("Mob bodies require uniform scale");
                if (body.destroyed) {
                    complete(future, new ResizeResult(ResizeResult.Status.BODY_DESTROYED, "Body destroyed"), null);
                    return;
                }
                replacementLease = shapes.acquire(body.definition, scale, body.spec.centreOfMassOffset());
                ShapeFactory.CachedShape replacement = replacementLease.shape();
                ShapeFactory.CachedShape original = body.cachedShape;
                ShapeFactory.ShapeLease originalLease = body.shapeLease;
                double originalMass = body.mass;
                Pose currentPose = nativePose(body);
                if (!insideScene(replacement, currentPose)) {
                    complete(future, new ResizeResult(ResizeResult.Status.BLOCKED,
                            "Collider outside scene bounds"), null);
                    return;
                }

                boolean wasActive = bodies.isActive(body.joltId);
                boolean changed = false;
                EMotionQuality originalQuality = body.spec.motionType() == MotionType.STATIC
                        ? EMotionQuality.Discrete : bodies.getMotionQuality(body.joltId);
                bodies.removeBody(body.joltId);
                ResizeResult result;
                try {
                    if (queries.overlaps(replacement, currentPose, body.joltId)) {
                        result = new ResizeResult(ResizeResult.Status.BLOCKED, "Collider overlaps");
                    } else {
                        bodies.setShape(body.joltId, replacement.shape(), false, EActivation.DontActivate);
                        changed = true;
                        double mass = body.spec.massKilograms().orElse(
                                replacement.massAtDensity(body.material.densityKilogramsPerCubicMetre()));
                        if (body.spec.motionType() == MotionType.DYNAMIC)
                            applyMass(body, replacement, mass);
                        if (body.spec.motionType() != MotionType.STATIC) {
                            setMaxAngularVelocity(body, maxAngular(replacement));
                            bodies.setMotionQuality(body.joltId, nativeQuality(body, replacement));
                        }
                        originalLease.close();
                        body.shapeLease = replacementLease;
                        body.cachedShape = replacement;
                        body.scale = scale;
                        body.mass = mass;
                        replacementLease = null;
                        result = new ResizeResult(ResizeResult.Status.APPLIED, "");
                    }
                } catch (Throwable failure) {
                    if (changed) {
                        try {
                            bodies.setShape(body.joltId, original.shape(), false, EActivation.DontActivate);
                            if (body.spec.motionType() == MotionType.DYNAMIC)
                                applyMass(body, original, originalMass);
                            if (body.spec.motionType() != MotionType.STATIC) {
                                setMaxAngularVelocity(body, maxAngular(original));
                                bodies.setMotionQuality(body.joltId, originalQuality);
                            }
                        } catch (Throwable rollbackFailure) {
                            failure.addSuppressed(rollbackFailure);
                        }
                    }
                    throw failure;
                } finally {
                    bodies.addBody(body.joltId,
                            wasActive ? EActivation.Activate : EActivation.DontActivate);
                }
                complete(future, result, null);
            } catch (Throwable failure) {
                complete(future, null, failure);
            } finally {
                if (replacementLease != null) replacementLease.close();
            }
        }, future);
        return future;
    }
    CompletionStage<Void> destroy(JoltBody body) {
        CompletableFuture<Optional<Entity>> created = new CompletableFuture<>();
        if (!body.terminal.compareAndSet(null, created))
            return body.terminal.get().thenApply(ignored -> null);
        body.destroyRequested.set(true);
        try {
            enqueue(() -> {
                try {
                    removeNative(body);
                    mainExecutor.accept(() -> {
                        try {
                            body.binding.destroy();
                            created.complete(Optional.empty());
                        } catch (Throwable failure) {
                            created.completeExceptionally(failure);
                        }
                    });
                } catch (Throwable failure) {
                    complete(created, null, failure);
                }
            }, created);
        } catch (Throwable failure) {
            complete(created, null, failure);
        }
        return created.thenApply(ignored -> null);
    }

    CompletionStage<Optional<Entity>> release(JoltBody body) {
        CompletableFuture<Optional<Entity>> created = new CompletableFuture<>();
        if (!body.terminal.compareAndSet(null, created)) return body.terminal.get();
        body.destroyRequested.set(true);
        try {
            enqueue(() -> {
                try {
                    BodyState state = snapshotNow(body);
                    removeNative(body);
                    mainExecutor.accept(() -> {
                        try {
                            body.binding.release(state).whenComplete((value, failure) -> {
                                if (failure == null) created.complete(value);
                                else created.completeExceptionally(failure);
                            });
                        } catch (Throwable failure) {
                            created.completeExceptionally(failure);
                        }
                    });
                } catch (Throwable failure) {
                    complete(created, null, failure);
                }
            }, created);
        } catch (Throwable failure) {
            complete(created, null, failure);
        }
        return created;
    }

    void enqueue(Runnable command) {
        enqueue(command, false, ignored -> { });
    }

    <T> void enqueue(Runnable command, CompletableFuture<T> future) {
        try {
            enqueue(command, false, failure -> complete(future, null, failure));
        } catch (Throwable failure) {
            complete(future, null, failure);
        }
    }

    private void enqueue(Runnable command, boolean allowClosing) {
        enqueue(command, allowClosing, ignored -> { });
    }

    private void enqueue(Runnable command, boolean allowClosing, Consumer<Throwable> cancellation) {
        QueuedCommand queued = new QueuedCommand(command, cancellation);
        synchronized (commandLock) {
            if (closed || closeRequested.get() && !allowClosing)
                throw new IllegalStateException("Scene closed");
            commands.addLast(queued);
        }
        try {
            requestStep.run();
        } catch (Throwable failure) {
            synchronized (commandLock) {
                commands.remove(queued);
            }
            queued.cancel(failure);
            throw failure;
        }
    }

    private void enqueueBody(JoltBody body, Runnable command) {
        if (!body.destroyed()) enqueue(() -> {
            if (!body.destroyed) command.run();
        });
    }

    void drainCommandsForShutdown() {
        if (!closed) drainCommands();
    }

    private void drainCommands() {
        List<QueuedCommand> pending;
        synchronized (commandLock) {
            pending = new ArrayList<>(commands);
            commands.clear();
        }
        Throwable firstFailure = null;
        for (int i = 0; i < pending.size(); i++) {
            QueuedCommand command = pending.get(i);
            try {
                command.run();
            } catch (Throwable failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
            if (closed) {
                CancellationException cancellation = new CancellationException("Scene closed");
                for (int j = i + 1; j < pending.size(); j++) pending.get(j).cancel(cancellation);
                break;
            }
        }
        if (firstFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (firstFailure instanceof Error error) throw error;
        if (firstFailure != null) throw new CompletionException(firstFailure);
    }

    private void cancelPending(Throwable failure) {
        List<QueuedCommand> pending;
        synchronized (commandLock) {
            pending = new ArrayList<>(commands);
            commands.clear();
        }
        for (QueuedCommand command : pending) command.cancel(failure);
    }
    private Pose nativePose(JoltBody body) {
        RVec3 position = bodies.getPosition(body.joltId);
        Quat rotation = bodies.getRotation(body.joltId);
        return new Pose(world(position), new Rotation(
                rotation.getX(), rotation.getY(), rotation.getZ(), rotation.getW()));
    }

    private BodyState snapshotNow(JoltBody body) {
        com.github.stephengold.joltjni.Vec3 linearVelocity = bodies.getLinearVelocity(body.joltId);
        com.github.stephengold.joltjni.Vec3 angularVelocity = bodies.getAngularVelocity(body.joltId);
        return new BodyState(nativePose(body), apiVec(linearVelocity), apiVec(angularVelocity),
                body.scale, body.mass, !bodies.isActive(body.joltId), ++sequence);
    }

    RVec3 local(Vec3 world) { return new RVec3(world.x() - origin.x(), world.y() - origin.y(), world.z() - origin.z()); }
    Vec3 world(RVec3 local) { return new Vec3(local.xx() + origin.x(), local.yy() + origin.y(), local.zz() + origin.z()); }
    static com.github.stephengold.joltjni.Vec3 nativeVec(Vec3 v) {
        return new com.github.stephengold.joltjni.Vec3(
                nativeFloat(v.x(), "x"), nativeFloat(v.y(), "y"), nativeFloat(v.z(), "z"));
    }
    static Quat nativeQuat(Rotation q) {
        return new Quat(nativeFloat(q.x(), "rotation.x"), nativeFloat(q.y(), "rotation.y"),
                nativeFloat(q.z(), "rotation.z"), nativeFloat(q.w(), "rotation.w"));
    }
    static float nativeFloat(double value, String name) {
        float converted = (float) value;
        if (!Float.isFinite(converted))
            throw new IllegalArgumentException(name + " is outside Jolt single-precision range");
        return converted;
    }
    static Vec3 apiVec(com.github.stephengold.joltjni.Vec3 v) { return new Vec3(v.getX(), v.getY(), v.getZ()); }
    private static float positiveNativeFloat(double value, String name) {
        float converted = nativeFloat(value, name);
        if (!(converted > 0.0f)) throw new IllegalArgumentException(name + " must be positive");
        return converted;
    }


    private final class ContactRegistration extends Registration implements Subscription {
        final EnumSet<ContactPhase> phases; final ContactFilter filter; final ContactListener listener;
        ContactRegistration(EnumSet<ContactPhase> p, ContactFilter f, ContactListener l) { phases = EnumSet.copyOf(p); filter = f; listener = l; }
        @Override void remove() { contactListeners.remove(this); }
    }
    private final class InteractionRegistration extends Registration implements Subscription {
        final InteractionFilter filter; final InteractionListener listener;
        InteractionRegistration(InteractionFilter f, InteractionListener l) { filter = f; listener = l; }
        @Override void remove() { interactionListeners.remove(this); }
    }
    private abstract static class Registration implements Subscription {
        final AtomicBoolean active = new AtomicBoolean(true);
        @Override public boolean active() { return active.get(); }
        @Override public void close() { if (active.compareAndSet(true, false)) remove(); }
        abstract void remove();
    }

    <T> void complete(CompletableFuture<T> future, T value, Throwable error) {
        Runnable completion = () -> {
            if (error == null) future.complete(value);
            else future.completeExceptionally(error);
        };
        try {
            mainExecutor.accept(completion);
        } catch (Throwable schedulingFailure) {
            if (error != null) schedulingFailure.addSuppressed(error);
            future.completeExceptionally(schedulingFailure);
        }
    }

    private void createNative(JoltBody body, Pose pose) {
        body.material = MaterialResolver.resolve(body.binding.materialHint(), body.requestedMaterial);
        ShapeFactory.ShapeLease lease = shapes.acquire(
                body.definition, body.scale, body.spec.centreOfMassOffset());
        ShapeFactory.CachedShape cached = lease.shape();
        body.shapeLease = lease;
        body.cachedShape = cached;
        boolean nativeCreated = false;
        try {
            if (!insideScene(cached, pose))
                throw new IllegalArgumentException("Initial collider is outside scene bounds");
            if (queries.overlaps(cached, pose, -1))
                throw new IllegalArgumentException("Initial collider overlaps scene geometry");
            body.mass = body.spec.massKilograms().orElse(
                    cached.massAtDensity(body.material.densityKilogramsPerCubicMetre()));
            com.github.stephengold.joltjni.enumerate.EMotionType motion = switch (body.spec.motionType()) {
                case STATIC -> com.github.stephengold.joltjni.enumerate.EMotionType.Static;
                case KINEMATIC -> com.github.stephengold.joltjni.enumerate.EMotionType.Kinematic;
                case DYNAMIC -> com.github.stephengold.joltjni.enumerate.EMotionType.Dynamic;
            };
            int layer = body.spec.motionType() == MotionType.STATIC ? STATIC_LAYER : MOVING_LAYER;
            try (BodyCreationSettings settings = new BodyCreationSettings(cached.shape(),
                    local(pose.position()), nativeQuat(pose.rotation()), motion, layer)) {
                settings.setFriction(friction(body.material))
                        .setRestitution((float) body.material.restitution())
                        .setLinearDamping(nativeFloat(body.material.linearDamping(), "linearDamping"))
                        .setAngularDamping(nativeFloat(body.material.angularDamping(), "angularDamping"))
                        .setGravityFactor(nativeFloat(body.spec.gravityScale(), "gravityScale"))
                        .setAllowSleeping(body.spec.sleepAllowed())
                        .setMaxLinearVelocity(128f)
                        .setMaxAngularVelocity(maxAngular(cached))
                        .setMotionQuality(nativeQuality(body, cached))
                        .setUserData(body.id.value());
                if (body.yawOnly) settings.setAllowedDofs(EAllowedDofs.TranslationX | EAllowedDofs.TranslationY
                        | EAllowedDofs.TranslationZ | EAllowedDofs.RotationY);
                if (body.spec.motionType() == MotionType.DYNAMIC) {
                    settings.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia);
                    settings.getMassPropertiesOverride().setMass(
                            positiveNativeFloat(body.mass, "massKilograms"));
                }
                body.joltId = bodies.createAndAddBody(settings,
                        body.spec.motionType() == MotionType.STATIC
                                ? EActivation.DontActivate : EActivation.Activate);
                if (body.joltId == Jolt.cInvalidBodyId)
                    throw new IllegalStateException("Jolt body capacity exhausted");
                nativeCreated = true;
                if (body.spec.motionType() != MotionType.STATIC) {
                    int quarantines = 0;
                    BoundingBox bounds = transformedBounds(cached, nativePose(body));
                    for (BoundsKey region : quarantineRegions)
                        if (bounds.overlaps(region.bounds())) quarantines++;
                    if (quarantines > 0) {
                        quarantineCounts.put(body.id, quarantines);
                        quarantineWake.add(body.id);
                        bodies.deactivateBody(body.joltId);
                    }
                }
            }
        } catch (Throwable failure) {
            if (nativeCreated) {
                failure = attempt(failure, () -> bodies.removeBody(body.joltId));
                failure = attempt(failure, () -> bodies.destroyBody(body.joltId));
            }
            quarantineCounts.remove(body.id);
            quarantineWake.remove(body.id);
            body.shapeLease = null;
            body.cachedShape = null;
            failure = attempt(failure, lease::close);
            throwUnchecked(failure);
        }
    }
    private int createStatic(ShapeFactory.CachedShape shape, Pose pose) {
        try (BodyCreationSettings settings = new BodyCreationSettings(shape.shape(), local(pose.position()),
                nativeQuat(pose.rotation()), com.github.stephengold.joltjni.enumerate.EMotionType.Static, STATIC_LAYER)) {
            settings.setUserData(0).setFriction(.7f).setRestitution(.02f);
            int id = bodies.createAndAddBody(settings, EActivation.DontActivate);
            if (id == Jolt.cInvalidBodyId) throw new IllegalStateException("Jolt terrain capacity exhausted");
            return id;
        }
    }

    private void createWalls(BoundingBox box) {
        double hx = box.getWidthX() * .5, hy = box.getHeight() * .5, hz = box.getWidthZ() * .5;
        wall(new Vec3(hx + .5, 0, 0), 1, box.getHeight() + 2, box.getWidthZ() + 2);
        wall(new Vec3(-hx - .5, 0, 0), 1, box.getHeight() + 2, box.getWidthZ() + 2);
        wall(new Vec3(0, hy + .5, 0), box.getWidthX() + 2, 1, box.getWidthZ() + 2);
        wall(new Vec3(0, -hy - .5, 0), box.getWidthX() + 2, 1, box.getWidthZ() + 2);
        wall(new Vec3(0, 0, hz + .5), box.getWidthX() + 2, box.getHeight() + 2, 1);
        wall(new Vec3(0, 0, -hz - .5), box.getWidthX() + 2, box.getHeight() + 2, 1);
    }

    private void wall(Vec3 position, double width, double height, double depth) {
        com.github.stephengold.joltjni.Vec3 half = new com.github.stephengold.joltjni.Vec3(
                positiveNativeFloat(width * .5, "boundary half-width"),
                positiveNativeFloat(height * .5, "boundary half-height"),
                positiveNativeFloat(depth * .5, "boundary half-depth"));
        try (BoxShape shape = new BoxShape(half, .005f);
                BodyCreationSettings settings = new BodyCreationSettings(shape,
                        new RVec3(position.x(), position.y(), position.z()), Quat.sIdentity(),
                        com.github.stephengold.joltjni.enumerate.EMotionType.Static, STATIC_LAYER)) {
            settings.setUserData(0).setFriction(.7f).setRestitution(.02f);
            if (bodies.createAndAddBody(settings, EActivation.DontActivate) == Jolt.cInvalidBodyId)
                throw new IllegalStateException("Could not create scene boundary");
        }
    }

    private void enforceQuarantines() {
        for (JoltBody body : bodyMap.values()) {
            if (body.spec.motionType() == MotionType.STATIC) continue;
            int desired = 0;
            BoundingBox bounds = transformedBounds(body.cachedShape, nativePose(body));
            for (BoundsKey region : quarantineRegions) if (bounds.overlaps(region.bounds())) desired++;
            Integer current = quarantineCounts.get(body.id);
            if (desired > 0) {
                if (current == null && bodies.isActive(body.joltId)) quarantineWake.add(body.id);
                quarantineCounts.put(body.id, desired);
                bodies.deactivateBody(body.joltId);
            } else if (current != null) {
                quarantineCounts.remove(body.id);
                if (quarantineWake.remove(body.id)) bodies.activateBody(body.joltId);
            }
        }
    }

    private void applyDragAndCcd() {
        for (JoltBody body : bodyMap.values()) {
            if (body.spec.motionType() != MotionType.DYNAMIC) continue;
            BodyState state = body.state;
            if (state.sleeping() && !body.motionDirty && !body.poseDirty
                    && !body.forceContinuousThisStep) continue;

            Vec3 velocity = body.motionDirty
                    ? apiVec(bodies.getLinearVelocity(body.joltId)) : state.linearVelocity();
            Rotation rotation;
            if (body.poseDirty) {
                Quat current = bodies.getRotation(body.joltId);
                rotation = new Rotation(current.getX(), current.getY(), current.getZ(), current.getW());
            } else {
                rotation = state.pose().rotation();
            }
            double speed = velocity.length();
            if (speed > .001 && body.material.dragCoefficient() > 0.0) {
                double inverseSpeed = 1.0 / speed;
                double area = body.cachedShape.projectedArea(rotation,
                        velocity.x() * inverseSpeed, velocity.y() * inverseSpeed,
                        velocity.z() * inverseSpeed);
                double desired = .5 * 1.225 * body.material.dragCoefficient()
                        * area * speed * speed;
                double maximum = Math.min(Float.MAX_VALUE, body.mass * speed / STEP);
                double magnitude = Math.min(desired, maximum);
                if (magnitude > 0.0) {
                    bodies.addForce(body.joltId,
                            nativeVec(velocity.multiply(-magnitude / speed)));
                }
            }
            if (body.spec.motionQuality() == MotionQuality.AUTO) {
                double predictedSpeed = speed
                        + Math.abs(body.spec.gravityScale()) * 9.80665 * STEP;
                EMotionQuality quality = body.forceContinuousThisStep
                        || predictedSpeed / 120d > body.cachedShape.minimumThickness() * .5
                        ? EMotionQuality.LinearCast : EMotionQuality.Discrete;
                if (bodies.getMotionQuality(body.joltId) != quality)
                    bodies.setMotionQuality(body.joltId, quality);
            }
            body.motionDirty = false;
            body.poseDirty = false;
            body.forceContinuousThisStep = false;
        }
    }

    private PublishedSnapshot capture() {
        if (layoutDirty) rebuildLayout();
        List<JoltBody> capturedOrder = order;
        BodyState[] capturedStates = new BodyState[capturedOrder.size()];
        if (capturedOrder.isEmpty()) return new PublishedSnapshot(capturedOrder, capturedStates);
        bodies.getPositions(ids, positions); bodies.getRotations(ids, rotations);
        bodies.getLinearVelocities(ids, linear); bodies.getAngularVelocities(ids, angular); bodies.areActive(ids, active);
        long current = ++sequence;
        for (int i = 0; i < capturedOrder.size(); i++) {
            JoltBody body = capturedOrder.get(i);
            BodyState previous = body.state;
            BodyState state;
            if (sameSleepingSnapshot(previous, body, i)) {
                state = previous;
            } else {
                Vec3 position = new Vec3(positions.get(i * 3) + origin.x(),
                        positions.get(i * 3 + 1) + origin.y(), positions.get(i * 3 + 2) + origin.z());
                Rotation rotation = new Rotation(rotations.get(i * 4), rotations.get(i * 4 + 1),
                        rotations.get(i * 4 + 2), rotations.get(i * 4 + 3));
                Vec3 lv = new Vec3(linear.get(i * 3), linear.get(i * 3 + 1), linear.get(i * 3 + 2));
                Vec3 av = new Vec3(angular.get(i * 3), angular.get(i * 3 + 1), angular.get(i * 3 + 2));
                state = new BodyState(new Pose(position, rotation), lv, av,
                        body.scale, body.mass, active.get(i) == 0, current);
                body.state = state;
            }
            capturedStates[i] = state;
        }
        return new PublishedSnapshot(capturedOrder, capturedStates);
    }

    private boolean sameSleepingSnapshot(BodyState state, JoltBody body, int index) {
        if (!state.sleeping() || active.get(index) != 0 || !state.scale().equals(body.scale)
                || Double.doubleToLongBits(state.massKilograms()) != Double.doubleToLongBits(body.mass)) return false;
        int vector = index * 3;
        Vec3 position = state.pose().position();
        if (position.x() != positions.get(vector) + origin.x()
                || position.y() != positions.get(vector + 1) + origin.y()
                || position.z() != positions.get(vector + 2) + origin.z()
                || state.linearVelocity().x() != linear.get(vector)
                || state.linearVelocity().y() != linear.get(vector + 1)
                || state.linearVelocity().z() != linear.get(vector + 2)
                || state.angularVelocity().x() != angular.get(vector)
                || state.angularVelocity().y() != angular.get(vector + 1)
                || state.angularVelocity().z() != angular.get(vector + 2)) return false;
        int quaternion = index * 4;
        return sameRotation(state.pose().rotation(), rotations.get(quaternion), rotations.get(quaternion + 1),
                rotations.get(quaternion + 2), rotations.get(quaternion + 3));
    }

    private static boolean sameRotation(Rotation rotation, double x, double y, double z, double w) {
        double maximum = Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.max(Math.abs(z), Math.abs(w)));
        double sx = x / maximum, sy = y / maximum, sz = z / maximum, sw = w / maximum;
        double inverseNorm = 1.0 / Math.sqrt(sx * sx + sy * sy + sz * sz + sw * sw);
        return rotation.x() == sx * inverseNorm && rotation.y() == sy * inverseNorm
                && rotation.z() == sz * inverseNorm && rotation.w() == sw * inverseNorm;
    }

    private void rebuildLayout() {
        if (ids != null) ids.close();
        order = List.copyOf(bodyMap.values());
        ids = new BodyIdArray(order.size());
        for (int i = 0; i < order.size(); i++) ids.set(i, order.get(i).joltId);
        positions = Jolt.newDirectDoubleBuffer(order.size() * 3); rotations = Jolt.newDirectFloatBuffer(order.size() * 4);
        linear = Jolt.newDirectFloatBuffer(order.size() * 3); angular = Jolt.newDirectFloatBuffer(order.size() * 3);
        active = Jolt.newDirectByteBuffer(order.size()); layoutDirty = false;
    }

    private void publish(PublishedSnapshot snapshot) {
        if (closed) return;
        List<JoltBody> bodies = snapshot.bodies();
        BodyState[] states = snapshot.states();
        for (int i = 0; i < bodies.size(); i++) {
            JoltBody body = bodies.get(i);
            if (body.destroyed) continue;
            try {
                body.binding.publish(states[i]);
            } catch (Throwable failure) {
                errorHandler.accept(failure);
            }
        }
    }

    private record PublishedSnapshot(List<JoltBody> bodies, BodyState[] states) {
    }

    private record QueuedCommand(Runnable action, Consumer<Throwable> cancellation) {
        void run() {
            action.run();
        }

        void cancel(Throwable failure) {
            cancellation.accept(failure);
        }
    }

    private void setMaxAngularVelocity(JoltBody body, float maximum) {
        try (BodyLockWrite lock = new BodyLockWrite(system.getBodyLockInterface(), body.joltId)) {
            if (!lock.succeeded()) throw new IllegalStateException("Body vanished during resize");
            lock.getBody().getMotionPropertiesUnchecked().setMaxAngularVelocity(maximum);
        }
    }

    private void applyMass(JoltBody body, ShapeFactory.CachedShape shape, double mass) {
        try (MassProperties properties = shape.shape().getMassProperties();
                BodyLockWrite lock = new BodyLockWrite(system.getBodyLockInterface(), body.joltId)) {
            if (!lock.succeeded()) throw new IllegalStateException("Body vanished during resize");
            properties.scaleToMass(positiveNativeFloat(mass, "massKilograms"));
            MotionProperties motion = lock.getBody().getMotionPropertiesUnchecked();
            int dofs = body.yawOnly ? EAllowedDofs.TranslationX | EAllowedDofs.TranslationY
                    | EAllowedDofs.TranslationZ | EAllowedDofs.RotationY : EAllowedDofs.All;
            motion.setMassProperties(dofs, properties);
        }
    }

    private void removeNative(JoltBody body) {
        if (body.destroyed) return;
        body.destroyed = true;
        Throwable failure = null;
        failure = attempt(failure, () -> bodies.removeBody(body.joltId));
        failure = attempt(failure, () -> bodies.destroyBody(body.joltId));
        bodyMap.remove(body.id);
        byPublicId.remove(body.id.value());
        byJoltId.remove(body.joltId);
        quarantineCounts.remove(body.id);
        quarantineWake.remove(body.id);
        ShapeFactory.ShapeLease lease = body.shapeLease;
        body.shapeLease = null;
        body.cachedShape = null;
        if (lease != null) failure = attempt(failure, lease::close);
        layoutDirty = true;
        if (failure != null) throwUnchecked(failure);
    }

    void cleanupBindingsOnMain(Consumer<Throwable> errorHandler) {
        if (mainCleanupDone || closed) return;
        mainCleanupDone = true;
        for (JoltBody body : List.copyOf(bodyMap.values())) {
            try {
                body.binding.ownerCleanup();
            } catch (Throwable failure) {
                errorHandler.accept(failure);
            }
        }
        try {
            bridge.close();
        } catch (Throwable failure) {
            errorHandler.accept(failure);
        }
    }

    void shutdownNowNative() {
        if (closed) return;
        cancelPending(new CancellationException("Scene closed before queued work completed"));
        closeNative();
    }

    private void closeNative() {
        if (closed) return;
        Throwable failure = null;
        for (JoltBody body : List.copyOf(bodyMap.values())) {
            failure = attempt(failure, () -> removeNative(body));
            if (!mainCleanupDone)
                failure = attempt(failure, () -> mainExecutor.accept(body.binding::ownerCleanup));
        }
        if (!mainCleanupDone)
            failure = attempt(failure, () -> mainExecutor.accept(bridge::close));
        failure = attempt(failure, () -> system.setContactListener(null));
        failure = attempt(failure, contacts::close);
        failure = attempt(failure, queries::close);
        if (ids != null) failure = attempt(failure, ids::close);
        failure = attempt(failure, system::destroyAllBodies);
        failure = attempt(failure, system::forgetMe);
        failure = attempt(failure, system::close);
        failure = attempt(failure, shapes::close);
        failure = attempt(failure, broadphase::close);
        failure = attempt(failure, layers::close);
        failure = attempt(failure, pairs::close);
        terrain.clear();
        closed = true;
        CompletableFuture<Void> pending = closeFuture.get();
        if (pending != null) complete(pending, null, failure);
        if (failure != null) throwUnchecked(failure);
    }
    static int terrainSectionCapacity(BoundingBox bounds, int worldMinimumY, int worldMaximumY) {
        long chunksX = (long) Math.ceil(bounds.getMaxX() / 16.0)
                - (long) Math.floor(bounds.getMinX() / 16.0);
        long chunksZ = (long) Math.ceil(bounds.getMaxZ() / 16.0)
                - (long) Math.floor(bounds.getMinZ() / 16.0);
        double minimumY = Math.max(bounds.getMinY(), worldMinimumY);
        double maximumY = Math.min(bounds.getMaxY(), worldMaximumY);
        long sectionsY = maximumY <= minimumY ? 0L
                : (long) Math.ceil(maximumY / 16.0) - (long) Math.floor(minimumY / 16.0);
        long sections = Math.multiplyExact(Math.multiplyExact(chunksX, chunksZ), sectionsY);
        if (sections > Integer.MAX_VALUE) throw new IllegalArgumentException("Scene terrain is too large");
        return (int) sections;
    }

    public record DebugBodySnapshot(
            BodyId id,
            PhysicsShape shape,
            Pose pose,
            Vec3 physicalScale,
            Vec3 centreOfMass,
            Vec3 linearVelocity
    ) {
    }

    private record BoundsKey(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        BoundsKey(BoundingBox box) {
            this(box.getMinX(), box.getMinY(), box.getMinZ(),
                    box.getMaxX(), box.getMaxY(), box.getMaxZ());
        }

        BoundingBox bounds() {
            return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private boolean insideScene(ShapeFactory.CachedShape shape, Pose pose) {
        BoundingBox body = transformedBounds(shape, pose);
        BoundingBox scene = spec.bounds();
        return body.getMinX() >= scene.getMinX() && body.getMaxX() <= scene.getMaxX()
                && body.getMinY() >= scene.getMinY() && body.getMaxY() <= scene.getMaxY()
                && body.getMinZ() >= scene.getMinZ() && body.getMaxZ() <= scene.getMaxZ();
    }

    private static BoundingBox bodyBounds(JoltBody body) {
        return transformedBounds(body.cachedShape, body.state.pose());
    }

    private static BoundingBox transformedBounds(ShapeFactory.CachedShape shape, Pose pose) {
        Rotation q = pose.rotation();
        Vec3 localCentre = shape.centreOfMass().add(shape.boundsCentre());
        Vec3 worldCentre = pose.position().add(rotate(q, localCentre));
        Vec3 half = shape.halfExtents();
        double xx = q.x() * q.x(), yy = q.y() * q.y(), zz = q.z() * q.z();
        double xy = q.x() * q.y(), xz = q.x() * q.z(), yz = q.y() * q.z();
        double xw = q.x() * q.w(), yw = q.y() * q.w(), zw = q.z() * q.w();
        double ex = Math.abs(1 - 2 * (yy + zz)) * half.x()
                + Math.abs(2 * (xy - zw)) * half.y() + Math.abs(2 * (xz + yw)) * half.z();
        double ey = Math.abs(2 * (xy + zw)) * half.x()
                + Math.abs(1 - 2 * (xx + zz)) * half.y() + Math.abs(2 * (yz - xw)) * half.z();
        double ez = Math.abs(2 * (xz - yw)) * half.x()
                + Math.abs(2 * (yz + xw)) * half.y() + Math.abs(1 - 2 * (xx + yy)) * half.z();
        return new BoundingBox(worldCentre.x() - ex, worldCentre.y() - ey, worldCentre.z() - ez,
                worldCentre.x() + ex, worldCentre.y() + ey, worldCentre.z() + ez);
    }

    private static Vec3 rotate(Rotation q, Vec3 vector) {
        double tx = 2.0 * (q.y() * vector.z() - q.z() * vector.y());
        double ty = 2.0 * (q.z() * vector.x() - q.x() * vector.z());
        double tz = 2.0 * (q.x() * vector.y() - q.y() * vector.x());
        return new Vec3(
                vector.x() + q.w() * tx + q.y() * tz - q.z() * ty,
                vector.y() + q.w() * ty + q.z() * tx - q.x() * tz,
                vector.z() + q.w() * tz + q.x() * ty - q.y() * tx);
    }

    private static Vec3 limitMagnitude(Vec3 value, double maximum) {
        double largest = Math.max(Math.abs(value.x()),
                Math.max(Math.abs(value.y()), Math.abs(value.z())));
        if (largest == 0.0) return value;
        double x = value.x() / largest, y = value.y() / largest, z = value.z() / largest;
        double normalizedLength = Math.sqrt(x * x + y * y + z * z);
        if (largest <= maximum / normalizedLength) return value;
        double scale = maximum / normalizedLength;
        return new Vec3(x * scale, y * scale, z * scale);
    }
    private static Throwable attempt(Throwable first, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (first == null) return failure;
            first.addSuppressed(failure);
        }
        return first;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
        throw new CompletionException(failure);
    }

    private static double midpoint(double minimum, double maximum) {
        return minimum * 0.5 + maximum * 0.5;
    }
    private static float maxAngular(ShapeFactory.CachedShape shape) {
        double radius = shape.maximumRadius();
        return (float)Math.max(1, Math.min(100, 60 / Math.max(.01, radius)));
    }

    private static EMotionQuality nativeQuality(JoltBody body, ShapeFactory.CachedShape shape) {
        return switch (body.spec.motionQuality()) {
            case DISCRETE -> EMotionQuality.Discrete;
            case CONTINUOUS -> EMotionQuality.LinearCast;
            case AUTO -> shape.minimumThickness() < .25
                    ? EMotionQuality.LinearCast : EMotionQuality.Discrete;
        };
    }
    private static float friction(ResolvedMaterial material) {
        nativeFloat(material.staticFriction(), "staticFriction");
        nativeFloat(material.dynamicFriction(), "dynamicFriction");
        double combined = Math.sqrt(material.staticFriction()) * Math.sqrt(material.dynamicFriction());
        return nativeFloat(combined, "friction");
    }

    private static void validateScale(Vec3 scale) { for (double v : new double[]{scale.x(), scale.y(), scale.z()})
        if (v < BodySpec.MIN_SCALE || v > BodySpec.MAX_SCALE) throw new IllegalArgumentException("Scale outside supported range"); }
}
