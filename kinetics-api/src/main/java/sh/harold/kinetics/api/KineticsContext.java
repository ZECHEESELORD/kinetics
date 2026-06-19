package sh.harold.kinetics.api;

import java.util.Collection;
import java.util.concurrent.CompletionStage;
import org.bukkit.plugin.java.JavaPlugin;

/** Owner-scoped access. The runtime closes it automatically when the owner disables. */
public interface KineticsContext extends AutoCloseable {
    JavaPlugin owner();

    CompletionStage<PhysicsScene> createScene(SceneSpec spec);

    Collection<PhysicsScene> scenes();

    boolean closed();

    @Override
    void close();
}
