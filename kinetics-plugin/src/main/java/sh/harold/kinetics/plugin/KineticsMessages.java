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

    static Component usage(boolean demoEnabled) {
        String actions = demoEnabled ? "stats|debug|demo" : "stats|debug";
        return Component.text("Usage: /kinetics <" + actions + ">", NamedTextColor.YELLOW);
    }

    static Component noScenes() {
        return Component.text("Kinetics has no active scenes.", NamedTextColor.GRAY);
    }

    static Component playerOnly() {
        return Component.text("Collider debugging requires a player.", NamedTextColor.RED);
    }

    static Component demoPlayerOnly() {
        return Component.text("Kinetics demonstrations require a player world.", NamedTextColor.RED);
    }

    static Component demoDisabled() {
        return Component.text("The Kinetics demonstration layer is disabled in config.yml.",
                NamedTextColor.RED);
    }

    static Component demoUsage() {
        return Component.text("Usage: /kinetics demo <sampler|spectacle> [x y z] | reset | stop",
                NamedTextColor.YELLOW);
    }

    static Component demoStarting(String mode, String world, double x, double y, double z) {
        return Component.text(String.format(Locale.ROOT,
                "Preparing the %s demo in %s at %.1f, %.1f, %.1f...", mode, world, x, y, z),
                NamedTextColor.AQUA);
    }

    static Component demoStarted(String mode) {
        return Component.text("Kinetics " + mode + " demo is ready.", NamedTextColor.GREEN);
    }

    static Component demoStopped() {
        return Component.text("Kinetics demonstration stopped.", NamedTextColor.GREEN);
    }

    static Component noDemo() {
        return Component.text("Kinetics has no active demonstration.", NamedTextColor.GRAY);
    }

    static Component samplerGuide() {
        return Component.text("Sampler controls: ", NamedTextColor.AQUA)
                .append(Component.text("attack", NamedTextColor.YELLOW))
                .append(Component.text(" = point impulse, ", NamedTextColor.GRAY))
                .append(Component.text("right-click", NamedTextColor.YELLOW))
                .append(Component.text(" = force burst, ", NamedTextColor.GRAY))
                .append(Component.text("sneak-attack", NamedTextColor.YELLOW))
                .append(Component.text(" = torque, ", NamedTextColor.GRAY))
                .append(Component.text("sneak-right-click", NamedTextColor.YELLOW))
                .append(Component.text(" = resize. Use /kinetics debug while looking at a body.",
                        NamedTextColor.GRAY));
    }

    static Component resizeResult(boolean applied) {
        return applied
                ? Component.text("Resized the selected physics body.", NamedTextColor.GREEN)
                : Component.text("That resize is blocked by another collider.", NamedTextColor.YELLOW);
    }

    static Component materialLabel() {
        return label("MATERIAL RESPONSE");
    }

    static Component interactionLabel() {
        return label("INFERRED BOX | SPHERE | CAPSULE\nIMPULSE | FORCE | TORQUE | RESIZE");
    }

    static Component motionLabel() {
        return label("KINEMATIC MOTION");
    }

    static Component terrainLabel() {
        return label("TERRAIN COLLISION");
    }

    static Component towerLabel() {
        return label("252-BODY TOWER DEMOLITION");
    }

    static Component countdown(int number) {
        return Component.text(Integer.toString(number), NamedTextColor.GOLD);
    }

    static Component launch() {
        return Component.text("IMPACT", NamedTextColor.RED);
    }

    private static Component label(String text) {
        return Component.text(text, NamedTextColor.AQUA);
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
