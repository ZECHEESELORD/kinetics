package sh.harold.kinetics.plugin.binding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import sh.harold.kinetics.api.BodyState;
import sh.harold.kinetics.plugin.render.ChunkViewerCache;
import sh.harold.kinetics.plugin.render.RenderedDisplay;
import sh.harold.kinetics.plugin.render.VirtualDisplayRenderer;

/** Paper-thread binding between a physics body and one PacketEvents display. */
public final class VirtualDisplayBinding implements BodyBinding {
    private final VirtualDisplayRenderer renderer;
    private final RenderedDisplay display;
    private final Material materialHint;
    private final boolean restoreOnOwnerCleanup;
    private final ChunkViewerCache viewerCache;
    private final ArrayList<Player> viewers = new ArrayList<>();

    private BodyState lastPublished;
    private boolean closed;

    public VirtualDisplayBinding(
            VirtualDisplayRenderer renderer,
            RenderedDisplay display,
            Material materialHint
    ) {
        this(renderer, display, materialHint, new ChunkViewerCache(display.world()), false);
    }

    /**
     * @param restoreOnOwnerCleanup true for an adopted display that must be materialized again
     *                              when its owning plugin is disabled
     */
    public VirtualDisplayBinding(
            VirtualDisplayRenderer renderer,
            RenderedDisplay display,
            Material materialHint,
            boolean restoreOnOwnerCleanup
    ) {
        this(renderer, display, materialHint, new ChunkViewerCache(display.world()), restoreOnOwnerCleanup);
    }

    public VirtualDisplayBinding(
            VirtualDisplayRenderer renderer,
            RenderedDisplay display,
            Material materialHint,
            ChunkViewerCache viewerCache,
            boolean restoreOnOwnerCleanup
    ) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.display = Objects.requireNonNull(display, "display");
        this.materialHint = Objects.requireNonNull(materialHint, "materialHint");
        this.viewerCache = Objects.requireNonNull(viewerCache, "viewerCache");
        this.restoreOnOwnerCleanup = restoreOnOwnerCleanup;
    }

    @Override
    public Material materialHint() {
        return materialHint;
    }

    @Override
    public void publish(BodyState state) {
        requirePrimaryThread();
        if (closed) {
            return;
        }

        Collection<? extends Player> desiredViewers = viewerCache.viewers(
                renderer.renderAnchor(display, state), renderer.renderBounds(display, state), viewers);

        // Sleeping snapshots get a stable sequence: existing viewers receive no redundant pose
        // packet, while publish still discovers and spawns newly eligible viewers.
        BodyState outgoing = state.sleeping() && sameTransform(lastPublished, state)
                ? lastPublished : state;
        renderer.publish(display, outgoing, desiredViewers, Bukkit.getCurrentTick());
        lastPublished = outgoing;
    }

    @Override
    public CompletionStage<Optional<Entity>> release(BodyState state) {
        requirePrimaryThread();
        if (closed) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Display restored = renderer.release(display, state);
        closed = true;
        return CompletableFuture.completedFuture(Optional.of(restored));
    }

    @Override
    public void destroy() {
        requirePrimaryThread();
        if (!closed) {
            closed = true;
            renderer.destroy(display);
        }
    }

    @Override
    public void ownerCleanup() {
        requirePrimaryThread();
        if (closed) {
            return;
        }
        if (restoreOnOwnerCleanup) {
            closed = true;
            renderer.release(display);
        } else {
            destroy();
        }
    }

    private static boolean sameTransform(BodyState first, BodyState second) {
        return first != null && first.pose().equals(second.pose()) && first.scale().equals(second.scale());
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Display bindings must run on the Paper thread");
        }
    }
}
