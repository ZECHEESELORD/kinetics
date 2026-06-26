package sh.harold.kinetics.plugin.binding;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import sh.harold.kinetics.api.BodyState;

public interface BodyBinding {
    BodyBinding HEADLESS = new BodyBinding() {
        @Override
        public Material materialHint() {
            return Material.STONE;
        }

        @Override
        public CompletionStage<Optional<Entity>> release(BodyState state) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    };

    Material materialHint();

    default void publish(BodyState state) {
    }

    default CompletionStage<Optional<Entity>> release(BodyState state) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    default void destroy() {
    }

    default void ownerCleanup() {
        destroy();
    }
}
