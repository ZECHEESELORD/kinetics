package sh.harold.kinetics.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import sh.harold.kinetics.api.SceneSpec;
import sh.harold.kinetics.plugin.binding.SceneBridge;
import sh.harold.kinetics.plugin.physics.JoltScene;

/** Paper-side scene lifecycle, kept outside the native physics coordinator. */
public interface SceneBridgeFactory extends AutoCloseable {
    SceneBridge create(SceneSpec spec);

    /** Completes when the bounded terrain snapshot is ready for consumers. */
    default CompletionStage<Void> prepare(JoltScene scene) {
        return CompletableFuture.completedFuture(null);
    }

    /** Runs once per Paper tick, before a physics step is requested. */
    default void tick(List<JoltScene> scenes) {
    }

    @Override
    default void close() {
    }
}
