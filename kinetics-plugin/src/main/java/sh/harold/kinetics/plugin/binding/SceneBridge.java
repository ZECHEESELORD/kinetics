package sh.harold.kinetics.plugin.binding;

import java.util.concurrent.CompletionStage;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import sh.harold.kinetics.api.BodySpec;
import sh.harold.kinetics.api.PhysicsBody;
import sh.harold.kinetics.plugin.physics.JoltScene;

public interface SceneBridge extends AutoCloseable {
    CompletionStage<PhysicsBody> createBlockDisplay(JoltScene scene, BlockData blockData, BodySpec spec);

    CompletionStage<PhysicsBody> createItemDisplay(JoltScene scene, ItemStack item, BodySpec spec);

    CompletionStage<PhysicsBody> adopt(JoltScene scene, Display display, BodySpec spec);

    CompletionStage<PhysicsBody> attach(JoltScene scene, Mob mob, BodySpec spec);

    void invalidate(BoundingBox worldBounds);

    default int dirtySections() {
        return 0;
    }

    default long packetsSent() {
        return 0L;
    }

    @Override
    default void close() {
    }
}
