package sh.harold.kinetics.plugin;

import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import sh.harold.kinetics.api.SceneStats;

/** Central player-visible command copy. */
final class KineticsMessages {
    private KineticsMessages() {
    }

    static Component usage() {
        return Component.text("Usage: /kinetics <stats|debug>", NamedTextColor.YELLOW);
    }

    static Component noScenes() {
        return Component.text("Kinetics has no active scenes.", NamedTextColor.GRAY);
    }

    static Component playerOnly() {
        return Component.text("Collider debugging requires a player.", NamedTextColor.RED);
    }

    static Component debugRendered(boolean rendered) {
        return rendered
                ? Component.text("Rendered the selected body collider, center of mass, and velocity.",
                        NamedTextColor.GREEN)
                : Component.text("No physics body is in reach.", NamedTextColor.GRAY);
    }

    static Component failure(Throwable failure) {
        return Component.text(
                "Kinetics operation failed: " + failureDetail(failure), NamedTextColor.RED);
    }

    static String failureDetail(Throwable failure) {
        if (failure == null) return "unknown failure";
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current
                && (current instanceof CompletionException
                        || current instanceof ExecutionException
                        || current.getMessage() == null
                        || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message != null && !message.isBlank()) return message.strip();
        String type = current.getClass().getSimpleName();
        return type.isBlank() ? "unknown failure" : type;
    }

    static Component sceneCount(int count) {
        return Component.text("Kinetics scenes: " + count, NamedTextColor.AQUA);
    }

    static Component stats(String name, SceneStats stats) {
        return Component.text(String.format(Locale.ROOT,
                "%s: %d bodies (%d awake), %.3f ms step (%.3f peak), %d contacts, %d queued, "
                        + "%d dirty, %d packets, %d/%d shape hits/misses, %d skipped",
                name, stats.bodies(), stats.awakeBodies(), stats.lastStepMilliseconds(),
                stats.peakStepMilliseconds(),
                stats.contacts(), stats.commandBacklog(), stats.dirtySections(),
                stats.packetsSent(), stats.shapeCacheHits(), stats.shapeCacheMisses(),
                stats.skippedTicks()), NamedTextColor.GRAY);
    }
}