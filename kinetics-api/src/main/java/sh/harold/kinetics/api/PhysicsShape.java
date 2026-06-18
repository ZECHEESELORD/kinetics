package sh.harold.kinetics.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable authoring geometry. Scaling belongs to {@link BodySpec}. */
public sealed interface PhysicsShape permits PhysicsShape.Box, PhysicsShape.Sphere,
        PhysicsShape.Capsule, PhysicsShape.Cylinder, PhysicsShape.ConvexHull,
        PhysicsShape.Compound {

    static Box box(double width, double height, double depth) {
        return new Box(new Vec3(width, height, depth));
    }

    static Sphere sphere(double radius) {
        return new Sphere(radius);
    }

    static Capsule capsule(double radius, double cylindricalHeight) {
        return new Capsule(radius, cylindricalHeight);
    }

    static Cylinder cylinder(double radius, double height) {
        return new Cylinder(radius, height);
    }

    static ConvexHull convexHull(List<Vec3> points) {
        return new ConvexHull(points);
    }

    static Compound compound(List<Child> children) {
        return new Compound(children);
    }

    record Box(Vec3 dimensions) implements PhysicsShape {
        public Box {
            Objects.requireNonNull(dimensions, "dimensions");
            positive(dimensions.x(), "width");
            positive(dimensions.y(), "height");
            positive(dimensions.z(), "depth");
        }
    }

    record Sphere(double radius) implements PhysicsShape {
        public Sphere {
            positive(radius, "radius");
        }
    }

    /** Capsule aligned to local Y; height excludes the two hemispherical caps. */
    record Capsule(double radius, double cylindricalHeight) implements PhysicsShape {
        public Capsule {
            positive(radius, "radius");
            nonNegative(cylindricalHeight, "cylindricalHeight");
        }
    }

    /** Cylinder aligned to local Y. */
    record Cylinder(double radius, double height) implements PhysicsShape {
        public Cylinder {
            positive(radius, "radius");
            positive(height, "height");
        }
    }

    record ConvexHull(List<Vec3> points) implements PhysicsShape {
        public ConvexHull {
            Objects.requireNonNull(points, "points");
            points = List.copyOf(points);
            if (points.size() < 4 || new HashSet<>(points).size() < 4) {
                throw new IllegalArgumentException("A convex hull requires at least four distinct points");
            }
        }
    }

    record Compound(List<Child> children) implements PhysicsShape {
        public Compound {
            Objects.requireNonNull(children, "children");
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new IllegalArgumentException("A compound requires at least one child");
            }
        }
    }

    record Child(PhysicsShape shape, Pose pose) {
        public Child {
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(pose, "pose");
        }

        public Child(PhysicsShape shape) {
            this(shape, Pose.IDENTITY);
        }
    }

    private static void positive(double value, String name) {
        Vec3.requireFinite(value, name);
        if (value <= 0.0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }

    private static void nonNegative(double value, String name) {
        Vec3.requireFinite(value, name);
        if (value < 0.0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
