package sh.harold.kinetics.plugin.physics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import sh.harold.kinetics.api.PhysicsShape;
import sh.harold.kinetics.api.Rotation;
import sh.harold.kinetics.api.Vec3;

@FunctionalInterface
interface ProjectedAreaModel {
    double area(double x, double y, double z);

    static ProjectedAreaModel create(PhysicsShape definition, Vec3 scale) {
        return switch (definition) {
            case PhysicsShape.Box box -> {
                Vec3 size = new Vec3(box.dimensions().x() * scale.x(),
                        box.dimensions().y() * scale.y(), box.dimensions().z() * scale.z());
                yield (x, y, z) -> Math.abs(x) * size.y() * size.z()
                        + Math.abs(y) * size.x() * size.z()
                        + Math.abs(z) * size.x() * size.y();
            }
            case PhysicsShape.Sphere sphere -> {
                double rx = sphere.radius() * scale.x();
                double ry = sphere.radius() * scale.y();
                double rz = sphere.radius() * scale.z();
                double factor = Math.PI * rx * ry * rz;
                yield (x, y, z) -> factor * Math.sqrt(
                        square(x / rx) + square(y / ry) + square(z / rz));
            }
            case PhysicsShape.Cylinder cylinder -> {
                double rx = cylinder.radius() * scale.x();
                double rz = cylinder.radius() * scale.z();
                double height = cylinder.height() * scale.y();
                yield (x, y, z) -> Math.PI * rx * rz * Math.abs(y)
                        + 2.0 * height * Math.sqrt(square(rz * x) + square(rx * z));
            }
            case PhysicsShape.Capsule capsule -> {
                double rx = capsule.radius() * scale.x();
                double ry = capsule.radius() * scale.y();
                double rz = capsule.radius() * scale.z();
                double height = capsule.cylindricalHeight() * scale.y();
                double factor = Math.PI * rx * ry * rz;
                yield (x, y, z) -> factor * Math.sqrt(
                        square(x / rx) + square(y / ry) + square(z / rz))
                        + 2.0 * height * Math.sqrt(square(rz * x) + square(rx * z));
            }
            case PhysicsShape.ConvexHull ignored -> new Directional(definition, scale);
            case PhysicsShape.Compound ignored -> new Directional(definition, scale);
        };
    }

    private static double square(double value) {
        return value * value;
    }

    /** Lazily samples expensive shapes because static terrain never needs aerodynamic area. */
    final class Directional implements ProjectedAreaModel {
        private static final int DIRECTION_COUNT = 32;
        private static final int MAX_POINTS = 512;
        private static final Vec3[] DIRECTIONS = sampleDirections(DIRECTION_COUNT);

        private PhysicsShape definition;
        private Vec3 scale;
        private volatile double[] areas;

        private Directional(PhysicsShape definition, Vec3 scale) {
            this.definition = definition;
            this.scale = scale;
        }

        @Override
        public double area(double x, double y, double z) {
            double[] cached = areas;
            if (cached == null) cached = initialize();
            double weightedArea = 0.0;
            double totalWeight = 0.0;
            for (int i = 0; i < DIRECTIONS.length; i++) {
                Vec3 direction = DIRECTIONS[i];
                double dot = Math.abs(x * direction.x() + y * direction.y() + z * direction.z());
                double weight = square(square(dot));
                weight *= weight;
                weightedArea += cached[i] * weight;
                totalWeight += weight;
            }
            return weightedArea / totalWeight;
        }

        private synchronized double[] initialize() {
            if (areas != null) return areas;
            List<Vec3> points = bounded(representativePoints(definition));
            ArrayList<Vec3> scaled = new ArrayList<>(points.size());
            for (Vec3 point : points) {
                scaled.add(new Vec3(point.x() * scale.x(), point.y() * scale.y(), point.z() * scale.z()));
            }
            double[] built = new double[DIRECTIONS.length];
            for (int i = 0; i < built.length; i++) built[i] = projectedHullArea(scaled, DIRECTIONS[i]);
            definition = null;
            scale = null;
            areas = built;
            return built;
        }

        private static List<Vec3> bounded(List<Vec3> points) {
            if (points.size() <= MAX_POINTS) return points;
            Set<Vec3> selected = new LinkedHashSet<>();
            int sampledLimit = MAX_POINTS - DIRECTIONS.length * 2;
            int stride = Math.max(1, (int) Math.ceil((double) points.size() / sampledLimit));
            for (int i = 0; i < points.size() && selected.size() < sampledLimit; i += stride)
                selected.add(points.get(i));
            for (Vec3 direction : DIRECTIONS) {
                Vec3 minimum = null, maximum = null;
                double minimumDot = Double.POSITIVE_INFINITY, maximumDot = Double.NEGATIVE_INFINITY;
                for (Vec3 point : points) {
                    double dot = dot(point, direction);
                    if (dot < minimumDot) { minimumDot = dot; minimum = point; }
                    if (dot > maximumDot) { maximumDot = dot; maximum = point; }
                }
                selected.add(minimum);
                selected.add(maximum);
            }
            return new ArrayList<>(selected);
        }

        private static List<Vec3> representativePoints(PhysicsShape shape) {
            return switch (shape) {
                case PhysicsShape.Box box -> boxPoints(box.dimensions());
                case PhysicsShape.Sphere sphere -> spherePoints(sphere.radius(), 0.0);
                case PhysicsShape.Cylinder cylinder -> cylinderPoints(cylinder.radius(), cylinder.height());
                case PhysicsShape.Capsule capsule -> capsulePoints(capsule.radius(), capsule.cylindricalHeight());
                case PhysicsShape.ConvexHull hull -> hull.points();
                case PhysicsShape.Compound compound -> {
                    ArrayList<Vec3> points = new ArrayList<>();
                    for (PhysicsShape.Child child : compound.children()) {
                        for (Vec3 point : representativePoints(child.shape())) {
                            points.add(rotate(child.pose().rotation(), point).add(child.pose().position()));
                        }
                    }
                    yield points;
                }
            };
        }

        private static List<Vec3> boxPoints(Vec3 dimensions) {
            double x = dimensions.x() * .5, y = dimensions.y() * .5, z = dimensions.z() * .5;
            ArrayList<Vec3> points = new ArrayList<>(8);
            for (int ix = -1; ix <= 1; ix += 2)
                for (int iy = -1; iy <= 1; iy += 2)
                    for (int iz = -1; iz <= 1; iz += 2)
                        points.add(new Vec3(ix * x, iy * y, iz * z));
            return points;
        }

        private static List<Vec3> spherePoints(double radius, double yOffset) {
            ArrayList<Vec3> points = new ArrayList<>(DIRECTION_COUNT);
            for (Vec3 direction : DIRECTIONS)
                points.add(new Vec3(direction.x() * radius,
                        direction.y() * radius + yOffset, direction.z() * radius));
            return points;
        }

        private static List<Vec3> cylinderPoints(double radius, double height) {
            ArrayList<Vec3> points = new ArrayList<>(32);
            for (int i = 0; i < 16; i++) {
                double angle = i * Math.PI * 2.0 / 16.0;
                double x = Math.cos(angle) * radius, z = Math.sin(angle) * radius;
                points.add(new Vec3(x, -height * .5, z));
                points.add(new Vec3(x, height * .5, z));
            }
            return points;
        }

        private static List<Vec3> capsulePoints(double radius, double cylindricalHeight) {
            ArrayList<Vec3> points = new ArrayList<>(DIRECTION_COUNT * 2);
            points.addAll(spherePoints(radius, -cylindricalHeight * .5));
            points.addAll(spherePoints(radius, cylindricalHeight * .5));
            return points;
        }

        private static Vec3 rotate(Rotation q, Vec3 value) {
            double tx = 2.0 * (q.y() * value.z() - q.z() * value.y());
            double ty = 2.0 * (q.z() * value.x() - q.x() * value.z());
            double tz = 2.0 * (q.x() * value.y() - q.y() * value.x());
            return new Vec3(value.x() + q.w() * tx + q.y() * tz - q.z() * ty,
                    value.y() + q.w() * ty + q.z() * tx - q.x() * tz,
                    value.z() + q.w() * tz + q.x() * ty - q.y() * tx);
        }

        private static double projectedHullArea(List<Vec3> points, Vec3 normal) {
            Vec3 axis = Math.abs(normal.x()) < .8 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 u = cross(normal, axis).normalized();
            Vec3 v = cross(normal, u);
            Point2[] projected = new Point2[points.size()];
            for (int i = 0; i < points.size(); i++) {
                Vec3 point = points.get(i);
                projected[i] = new Point2(dot(point, u), dot(point, v));
            }
            Arrays.sort(projected);
            Point2[] hull = new Point2[projected.length * 2];
            int count = 0;
            for (Point2 point : projected) {
                while (count >= 2 && cross(hull[count - 2], hull[count - 1], point) <= 0.0) count--;
                hull[count++] = point;
            }
            int lower = count + 1;
            for (int i = projected.length - 2; i >= 0; i--) {
                Point2 point = projected[i];
                while (count >= lower && cross(hull[count - 2], hull[count - 1], point) <= 0.0) count--;
                hull[count++] = point;
            }
            if (count < 4) return ShapeFactory.MIN_THICKNESS * ShapeFactory.MIN_THICKNESS;
            double twiceArea = 0.0;
            for (int i = 0; i < count - 1; i++)
                twiceArea += hull[i].x * hull[i + 1].y - hull[i].y * hull[i + 1].x;
            return Math.max(ShapeFactory.MIN_THICKNESS * ShapeFactory.MIN_THICKNESS,
                    Math.abs(twiceArea) * .5);
        }

        private static Vec3[] sampleDirections(int count) {
            Vec3[] directions = new Vec3[count];
            double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
            for (int i = 0; i < count; i++) {
                double y = 1.0 - 2.0 * (i + .5) / count;
                double radius = Math.sqrt(1.0 - y * y);
                double angle = goldenAngle * i;
                directions[i] = new Vec3(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
            }
            return directions;
        }

        private static Vec3 cross(Vec3 a, Vec3 b) {
            return new Vec3(a.y() * b.z() - a.z() * b.y(),
                    a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
        }

        private static double dot(Vec3 a, Vec3 b) {
            return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
        }

        private static double cross(Point2 a, Point2 b, Point2 c) {
            return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
        }

        private record Point2(double x, double y) implements Comparable<Point2> {
            @Override public int compareTo(Point2 other) {
                int xOrder = Double.compare(x, other.x);
                return xOrder != 0 ? xOrder : Double.compare(y, other.y);
            }
        }
    }
}