package sh.harold.kinetics.api;

import java.util.OptionalDouble;

/** Per-property overrides; omitted values are inferred by the runtime. */
public final class PhysicsMaterial {
    public static final PhysicsMaterial AUTO = builder().build();

    private final double density;
    private final double staticFriction;
    private final double dynamicFriction;
    private final double restitution;
    private final double linearDamping;
    private final double angularDamping;
    private final double dragCoefficient;

    private PhysicsMaterial(Builder builder) {
        density = builder.density;
        staticFriction = builder.staticFriction;
        dynamicFriction = builder.dynamicFriction;
        restitution = builder.restitution;
        linearDamping = builder.linearDamping;
        angularDamping = builder.angularDamping;
        dragCoefficient = builder.dragCoefficient;
    }

    public static Builder builder() {
        return new Builder();
    }

    public OptionalDouble densityKilogramsPerCubicMetre() {
        return optional(density);
    }

    public OptionalDouble staticFriction() {
        return optional(staticFriction);
    }

    public OptionalDouble dynamicFriction() {
        return optional(dynamicFriction);
    }

    public OptionalDouble restitution() {
        return optional(restitution);
    }

    public OptionalDouble linearDamping() {
        return optional(linearDamping);
    }

    public OptionalDouble angularDamping() {
        return optional(angularDamping);
    }

    public OptionalDouble dragCoefficient() {
        return optional(dragCoefficient);
    }

    private static OptionalDouble optional(double value) {
        return Double.isNaN(value) ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    public static final class Builder {
        private double density = Double.NaN;
        private double staticFriction = Double.NaN;
        private double dynamicFriction = Double.NaN;
        private double restitution = Double.NaN;
        private double linearDamping = Double.NaN;
        private double angularDamping = Double.NaN;
        private double dragCoefficient = Double.NaN;

        private Builder() {
        }

        public Builder densityKilogramsPerCubicMetre(double value) {
            density = positive(value, "density");
            return this;
        }

        public Builder friction(double staticValue, double dynamicValue) {
            staticFriction = nonNegative(staticValue, "staticFriction");
            dynamicFriction = nonNegative(dynamicValue, "dynamicFriction");
            return this;
        }

        public Builder restitution(double value) {
            restitution = betweenZeroAndOne(value, "restitution");
            return this;
        }

        public Builder linearDamping(double value) {
            linearDamping = nonNegative(value, "linearDamping");
            return this;
        }

        public Builder angularDamping(double value) {
            angularDamping = nonNegative(value, "angularDamping");
            return this;
        }

        public Builder dragCoefficient(double value) {
            dragCoefficient = nonNegative(value, "dragCoefficient");
            return this;
        }

        public PhysicsMaterial build() {
            return new PhysicsMaterial(this);
        }

        private static double positive(double value, String name) {
            requireFinite(value, name);
            if (value <= 0.0) {
                throw new IllegalArgumentException(name + " must be greater than zero");
            }
            return value;
        }

        private static double nonNegative(double value, String name) {
            requireFinite(value, name);
            if (value < 0.0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }

        private static double betweenZeroAndOne(double value, String name) {
            requireFinite(value, name);
            if (value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException(name + " must be between zero and one");
            }
            return value;
        }

        private static void requireFinite(double value, String name) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
        }
    }
}
