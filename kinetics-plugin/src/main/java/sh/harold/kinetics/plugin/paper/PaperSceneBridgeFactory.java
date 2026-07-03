package sh.harold.kinetics.plugin.paper;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletionStage;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.kinetics.api.SceneSpec;
import sh.harold.kinetics.plugin.SceneBridgeFactory;
import sh.harold.kinetics.plugin.binding.SceneBridge;
import sh.harold.kinetics.plugin.physics.JoltScene;
import sh.harold.kinetics.plugin.render.VirtualDisplayRenderer;

/** Owns the Paper-only resources shared by scene bridges. */
public final class PaperSceneBridgeFactory implements SceneBridgeFactory {
    private final JavaPlugin plugin;
    private final CopyOnWriteArrayList<PaperSceneBridge> bridges = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public PaperSceneBridgeFactory(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public SceneBridge create(SceneSpec spec) {
        if (closed) {
            throw new IllegalStateException("Paper scene bridge factory is closed");
        }
        PaperSceneBridge bridge = new PaperSceneBridge(plugin, spec, new VirtualDisplayRenderer(), () -> bridges.removeIf(
                candidate -> candidate == null || candidate.closed()));
        bridges.add(bridge);
        return bridge;
    }

    @Override
    public CompletionStage<Void> prepare(JoltScene scene) {
        PaperSceneBridge bridge = bridges.stream()
                .filter(candidate -> candidate.sceneSpec() == scene.spec() && !candidate.attached())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Paper bridge for scene " + scene.name()));
        return bridge.prepare(scene);
    }

    @Override
    public void tick(List<JoltScene> scenes) {
        if (closed) {
            return;
        }
        for (PaperSceneBridge bridge : bridges) {
            bridge.tick();
        }
        bridges.removeIf(PaperSceneBridge::closed);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (PaperSceneBridge bridge : List.copyOf(bridges)) {
            bridge.close();
        }
        bridges.clear();
    }
}
