package sh.harold.kinetics.api;

/** Lock-free diagnostics snapshot. Times are in milliseconds. */
public record SceneStats(
        long completedSteps,
        double lastStepMilliseconds,
        double peakStepMilliseconds,
        int bodies,
        int awakeBodies,
        int contacts,
        int commandBacklog,
        int dirtySections,
        long packetsSent,
        long shapeCacheHits,
        long shapeCacheMisses,
        long skippedTicks
) {
}
