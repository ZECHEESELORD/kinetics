package sh.harold.kinetics.api;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/** Immutable body creation options. A missing shape asks the binding to infer one. */
public final class BodySpec {
    public static final double MIN_SCALE = 0.01;
    public static final double MAX_SCALE = 64.0;

    private final PhysicsShape shape;
    private final Pose pose;
    private final Vec3 scale;
    private final Vec3 centreOfMassOffset;
    private final PhysicsMaterial material;
    private final MotionType motionType;
    private final MotionQuality motionQuality;
    private final double mass;
    private final double gravityScale;
    private final int collisionLayer;
    private final int collisionMask;
    private final boolean sleepAllowed;
    private final boolean interactable;

    private BodySpec(Builder builder) {
        shape = builder.shape;
        pose = builder.pose;
        scale = builder.scale;
        centreOfMassOffset = builder.centreOfMassOffset;
        material = builder.material;
        motionType = builder.motionType;
        motionQuality = builder.motionQuality;
        mass = builder.mass;
        gravityScale = builder.gravityScale;
        collisionLayer = builder.collisionLayer;
        collisionMask = builder.collisionMask;
        sleepAllowed = builder.sleepAllowed;
        interactable = builder.interactable;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder dynamic(PhysicsShape shape) {
        return builder().motionType(MotionType.DYNAMIC).shape(shape);
    }

    public static Builder inferred() {
        return builder();
    }

    /** Returns a builder pre-populated with this specification. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    public Optional<PhysicsShape> shape() {
        return Optional.ofNullable(shape);
    }

    public Pose pose() {
        return pose;
    }

    public Vec3 scale() {
        return scale;
    }

    public Vec3 centreOfMassOffset() {
        return centreOfMassOffset;
    }

    public PhysicsMaterial material() {
        return material;
    }

    public MotionType motionType() {
        return motionType;
    }

    public MotionQuality motionQuality() {
        return motionQuality;
    }

    public OptionalDouble massKilograms() {
        return Double.isNaN(mass) ? OptionalDouble.empty() : OptionalDouble.of(mass);
    }

    public double gravityScale() {
        return gravityScale;
    }

    public int collisionLayer() {
        return collisionLayer;
    }

    public int collisionMask() {
        return collisionMask;
    }

    public boolean sleepAllowed() {
        return sleepAllowed;
    }

    public boolean interactable() {
        return interactable;
    }

    public static final class Builder {
        private PhysicsShape shape;
        private Pose pose = Pose.IDENTITY;
        private Vec3 scale = Vec3.ONE;
        private Vec3 centreOfMassOffset = Vec3.ZERO;
        private PhysicsMaterial material = PhysicsMaterial.AUTO;
        private MotionType motionType = MotionType.DYNAMIC;
        private MotionQuality motionQuality = MotionQuality.AUTO;
        private double mass = Double.NaN;
        private double gravityScale = 1.0;
        private int collisionLayer = CollisionLayers.DEFAULT_BODY;
        private int collisionMask = -1;
        private boolean sleepAllowed = true;
        private boolean interactable;

        private Builder() {
        }

        private Builder(BodySpec source) {
            shape = source.shape;
            pose = source.pose;
            scale = source.scale;
            centreOfMassOffset = source.centreOfMassOffset;
            material = source.material;
            motionType = source.motionType;
            motionQuality = source.motionQuality;
            mass = source.mass;
            gravityScale = source.gravityScale;
            collisionLayer = source.collisionLayer;
            collisionMask = source.collisionMask;
            sleepAllowed = source.sleepAllowed;
            interactable = source.interactable;
        }

        public Builder shape(PhysicsShape value) {
            shape = Objects.requireNonNull(value, "shape");
            return this;
        }

        public Builder pose(Pose value) {
            pose = Objects.requireNonNull(value, "pose");
            return this;
        }

        public Builder scale(double uniformScale) {
            return scale(new Vec3(uniformScale, uniformScale, uniformScale));
        }

        public Builder scale(Vec3 value) {
            Objects.requireNonNull(value, "scale");
            validateScale(value.x(), "scale.x");
            validateScale(value.y(), "scale.y");
            validateScale(value.z(), "scale.z");
            scale = value;
            return this;
        }

        public Builder centreOfMassOffset(Vec3 value) {
            centreOfMassOffset = Objects.requireNonNull(value, "centreOfMassOffset");
            return this;
        }

        public Builder material(PhysicsMaterial value) {
            material = Objects.requireNonNull(value, "material");
            return this;
        }

        public Builder motionType(MotionType value) {
            motionType = Objects.requireNonNull(value, "motionType");
            return this;
        }

        public Builder motionQuality(MotionQuality value) {
            motionQuality = Objects.requireNonNull(value, "motionQuality");
            return this;
        }

        public Builder massKilograms(double value) {
            Vec3.requireFinite(value, "massKilograms");
            if (value <= 0.0) {
                throw new IllegalArgumentException("massKilograms must be greater than zero");
            }
            mass = value;
            return this;
        }

        public Builder gravityScale(double value) {
            Vec3.requireFinite(value, "gravityScale");
            gravityScale = value;
            return this;
        }

        public Builder collisions(int layer, int mask) {
            if (layer < 1 || layer > 31) {
                throw new IllegalArgumentException("body collision layer must be between 1 and 31");
            }
            collisionLayer = layer;
            collisionMask = mask;
            return this;
        }

        public Builder sleepAllowed(boolean value) {
            sleepAllowed = value;
            return this;
        }

        public Builder interactable(boolean value) {
            interactable = value;
            return this;
        }

        public BodySpec build() {
            return new BodySpec(this);
        }

        private static void validateScale(double value, String name) {
            if (!Double.isFinite(value) || value < MIN_SCALE || value > MAX_SCALE) {
                throw new IllegalArgumentException(name + " must be finite and between "
                        + MIN_SCALE + " and " + MAX_SCALE);
            }
        }
    }
}
