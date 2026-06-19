package sh.harold.kinetics.api;

import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

public interface PhysicsScene extends AutoCloseable {
    String name();

    World world();

    SceneSpec spec();

    boolean closed();

    CompletionStage<PhysicsBody> createBody(BodySpec spec);

    CompletionStage<PhysicsBody> createBlockDisplay(BlockData blockData, BodySpec spec);

    CompletionStage<PhysicsBody> createItemDisplay(ItemStack item, BodySpec spec);

    /** Consumes the display on success; release materializes an equivalent Bukkit display. */
    CompletionStage<PhysicsBody> adopt(Display display, BodySpec spec);

    /** Makes the mob physics-authoritative until destroy or release restores its captured state. */
    CompletionStage<PhysicsBody> attach(Mob mob, BodySpec spec);

    Optional<PhysicsBody> body(BodyId id);

    CompletionStage<Optional<RaycastHit>> raycast(RaycastQuery query);

    void invalidate(BoundingBox worldBounds);

    Subscription onContact(EnumSet<ContactPhase> phases, ContactFilter filter,
            ContactListener listener);

    Subscription onInteraction(InteractionFilter filter, InteractionListener listener);

    SceneStats stats();

    CompletionStage<Void> closeAsync();

    @Override
    default void close() {
        closeAsync();
    }
}
