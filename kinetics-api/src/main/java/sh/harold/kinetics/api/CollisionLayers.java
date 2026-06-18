package sh.harold.kinetics.api;

/** Logical contact-filter layers. Layer zero is reserved for scene terrain. */
public final class CollisionLayers {
    public static final int TERRAIN = 0;
    public static final int DEFAULT_BODY = 1;
    public static final int ALL = -1;

    private CollisionLayers() {
    }

    public static int mask(int... layers) {
        int mask = 0;
        for (int layer : layers) {
            if (layer < 0 || layer > 31)
                throw new IllegalArgumentException("collision layer must be between 0 and 31");
            mask |= 1 << layer;
        }
        return mask;
    }
}
