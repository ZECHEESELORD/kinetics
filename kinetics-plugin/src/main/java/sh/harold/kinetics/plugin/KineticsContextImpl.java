package sh.harold.kinetics.plugin;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
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
        pruneClosedScenes();
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Kinetics context is closed"));
        }
        if (!owner.isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Owning plugin is disabled"));
        }
        if (!reservedNames.add(spec.name())) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Scene name already exists for this owner: " + spec.name()));
        }

        CompletableFuture<PhysicsScene> result = new CompletableFuture<>();
        service.executeOnMain(() -> createSceneOnMain(spec, result));
        return result;
    }

    private void createSceneOnMain(SceneSpec spec, CompletableFuture<PhysicsScene> result) {
        if (closed.get() || !owner.isEnabled()) {
            reservedNames.remove(spec.name());
            result.completeExceptionally(new IllegalStateException("Owning plugin is disabled"));
            return;
        }

        final SceneBridge bridge;
        try {
            bridge = service.bridgeFactory().create(spec);
        } catch (Throwable failure) {
            reservedNames.remove(spec.name());
            result.completeExceptionally(failure);
            return;
        }

        service.coordinator().submit(() -> new JoltScene(spec, service.runtime(), bridge,
                        service::executeOnMain, service.coordinator().stepRequester(),
                        service.coordinator()::reportFailure))
                .whenComplete((scene, constructionFailure) -> service.executeOnMain(() -> {
                    if (constructionFailure != null) {
                        reservedNames.remove(spec.name());
                        bridge.close();
                        result.completeExceptionally(unwrap(constructionFailure));
                        return;
                    }
                    if (closed.get() || !owner.isEnabled()) {
                        bridge.close();
                        service.coordinator().dispose(scene);
                        reservedNames.remove(spec.name());
                        result.completeExceptionally(new IllegalStateException("Owning plugin is disabled"));
                        return;
                    }

                    service.coordinator().add(scene);
                    scenes.add(scene);
                    try {
                        service.bridgeFactory().prepare(scene).whenComplete((ignored, preparationFailure) ->
                                service.executeOnMain(() -> finishPreparation(scene, result, preparationFailure)));
                    } catch (Throwable failure) {
                        finishPreparation(scene, result, failure);
                    }
                }));
    }

    private void finishPreparation(JoltScene scene, CompletableFuture<PhysicsScene> result,
            Throwable preparationFailure) {
        if (preparationFailure == null && !closed.get() && owner.isEnabled()) {
            result.complete(scene);
            return;
        }
        scenes.remove(scene);
        reservedNames.remove(scene.name());
        scene.closeAsync();
        Throwable failure = preparationFailure == null
                ? new IllegalStateException("Owning plugin was disabled while creating the scene")
                : unwrap(preparationFailure);
        result.completeExceptionally(failure);
    }

    @Override
    public Collection<PhysicsScene> scenes() {
        pruneClosedScenes();
        return List.copyOf(scenes);
    }

    private void pruneClosedScenes() {
        scenes.removeIf(scene -> {
            if (!scene.closed()) {
                return false;
            }
            reservedNames.remove(scene.name());
            return true;
        });
    }

    @Override
    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (JoltScene scene : scenes) {
            scene.closeAsync();
        }
        scenes.clear();
        reservedNames.clear();
        service.release(owner, this);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }
}
