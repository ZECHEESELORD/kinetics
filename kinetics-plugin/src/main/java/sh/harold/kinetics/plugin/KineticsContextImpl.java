package sh.harold.kinetics.plugin;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.kinetics.api.KineticsContext;
import sh.harold.kinetics.api.PhysicsScene;
import sh.harold.kinetics.api.SceneSpec;
import sh.harold.kinetics.plugin.binding.SceneBridge;
import sh.harold.kinetics.plugin.physics.JoltScene;

final class KineticsContextImpl implements KineticsContext {
    private final KineticsServiceImpl service;
    private final JavaPlugin owner;
    private final CopyOnWriteArrayList<JoltScene> scenes = new CopyOnWriteArrayList<>();
    private final java.util.Set<String> reservedNames = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    KineticsContextImpl(KineticsServiceImpl service, JavaPlugin owner) {
        this.service = Objects.requireNonNull(service, "service");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override
    public JavaPlugin owner() {
        return owner;
    }

    @Override
    public CompletionStage<PhysicsScene> createScene(SceneSpec spec) {
        Objects.requireNonNull(spec, "spec");
        synchronized (lifecycleLock) {
            pruneClosedScenesLocked();
            if (closed.get()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Kinetics context is closed"));
            }
            if (!owner.isEnabled()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Owning plugin is disabled"));
            }
            if (!reservedNames.add(spec.name())) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "Scene name already exists for this owner: " + spec.name()));
            }
        }

        CompletableFuture<PhysicsScene> result = new CompletableFuture<>();
        service.executeOnMain(() -> createSceneOnMain(spec, result));
        return result;
    }

    private void createSceneOnMain(SceneSpec spec, CompletableFuture<PhysicsScene> result) {
        synchronized (lifecycleLock) {
            if (closed.get() || !owner.isEnabled()) {
                reservedNames.remove(spec.name());
                result.completeExceptionally(unavailableFailure());
                return;
            }
        }

        final SceneBridge bridge;
        try {
            bridge = service.bridgeFactory().create(spec);
        } catch (Throwable failure) {
            releaseName(spec.name());
            result.completeExceptionally(failure);
            return;
        }

        service.coordinator().submit(() -> constructScene(spec, bridge))
                .whenComplete((scene, constructionFailure) -> service.executeOnMain(() -> {
                    if (constructionFailure != null) {
                        releaseName(spec.name());
                        closeAfterConstructionFailure(bridge, unwrap(constructionFailure), result);
                        return;
                    }

                    boolean accepted;
                    synchronized (lifecycleLock) {
                        accepted = !closed.get() && owner.isEnabled();
                        if (accepted) scenes.add(scene);
                    }
                    if (!accepted) {
                        disposeRejectedScene(scene, unavailableFailure(), result);
                        return;
                    }

                    try {
                        service.bridgeFactory().prepare(scene).whenComplete((ignored, preparationFailure) ->
                                service.executeOnMain(() ->
                                        finishPreparation(scene, result, preparationFailure)));
                    } catch (Throwable failure) {
                        finishPreparation(scene, result, failure);
                    }
                }));
    }

    private JoltScene constructScene(SceneSpec spec, SceneBridge bridge) {
        JoltScene scene = new JoltScene(spec, service.runtime(), bridge,
                service::executeOnMain, service.coordinator().stepRequester(),
                service.coordinator()::reportFailure);
        service.coordinator().add(scene);
        return scene;
    }

    private void finishPreparation(JoltScene scene, CompletableFuture<PhysicsScene> result,
            Throwable preparationFailure) {
        boolean accepted;
        synchronized (lifecycleLock) {
            accepted = preparationFailure == null && !closed.get() && owner.isEnabled()
                    && scenes.contains(scene);
            if (!accepted) {
                scenes.remove(scene);
                reservedNames.remove(scene.name());
            }
        }
        if (accepted) {
            result.complete(scene);
            return;
        }

        scene.closeAsync();
        Throwable failure = preparationFailure == null
                ? unavailableFailure()
                : unwrap(preparationFailure);
        result.completeExceptionally(failure);
    }

    private void closeAfterConstructionFailure(SceneBridge bridge, Throwable failure,
            CompletableFuture<PhysicsScene> result) {
        try {
            bridge.close();
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        }
        result.completeExceptionally(failure);
    }

    private void disposeRejectedScene(JoltScene scene, Throwable failure,
            CompletableFuture<PhysicsScene> result) {
        releaseName(scene.name());
        try {
            service.coordinator().dispose(scene);
        } catch (Throwable disposalFailure) {
            if (disposalFailure != failure) failure.addSuppressed(disposalFailure);
        }
        result.completeExceptionally(failure);
    }

    @Override
    public Collection<PhysicsScene> scenes() {
        synchronized (lifecycleLock) {
            pruneClosedScenesLocked();
            return List.copyOf(scenes);
        }
    }

    private void pruneClosedScenesLocked() {
        scenes.removeIf(scene -> {
            if (!scene.closed()) return false;
            reservedNames.remove(scene.name());
            return true;
        });
    }

    private void releaseName(String name) {
        synchronized (lifecycleLock) {
            reservedNames.remove(name);
        }
    }

    private IllegalStateException unavailableFailure() {
        return new IllegalStateException(closed.get()
                ? "Kinetics context is closed"
                : "Owning plugin is disabled");
    }

    @Override
    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        List<JoltScene> closing;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
            closing = List.copyOf(scenes);
            scenes.clear();
            reservedNames.clear();
        }
        for (JoltScene scene : closing) scene.closeAsync();
        service.release(owner, this);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}