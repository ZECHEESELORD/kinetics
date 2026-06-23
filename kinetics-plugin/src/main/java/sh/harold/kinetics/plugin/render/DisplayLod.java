package sh.harold.kinetics.plugin.render;

/** Packet publication rates for virtual displays, with a small distance hysteresis. */
public enum DisplayLod {
    NEAR(1),
    MID(2),
    FAR(4);

    private static final double NEAR_DISTANCE = 32.0;
    private static final double FAR_DISTANCE = 64.0;
    private static final double HYSTERESIS = 4.0;

    private final int intervalTicks;

    DisplayLod(int intervalTicks) {
        this.intervalTicks = intervalTicks;
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    public boolean shouldPublish(long serverTick, int entityId) {
        return Math.floorMod(serverTick + entityId, intervalTicks) == 0;
    }

    public static DisplayLod select(double distanceSquared, DisplayLod previous) {
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0) {
            throw new IllegalArgumentException("distanceSquared must be finite and non-negative");
        }

        double distance = Math.sqrt(distanceSquared);
        if (previous == NEAR && distance <= NEAR_DISTANCE + HYSTERESIS) {
            return NEAR;
        }
        if (previous == FAR && distance >= FAR_DISTANCE - HYSTERESIS) {
            return FAR;
        }
        if (distance <= NEAR_DISTANCE - (previous == MID ? HYSTERESIS : 0.0)) {
            return NEAR;
        }
        if (distance <= FAR_DISTANCE + (previous == MID ? HYSTERESIS : 0.0)) {
            return MID;
        }
        return FAR;
    }
}
