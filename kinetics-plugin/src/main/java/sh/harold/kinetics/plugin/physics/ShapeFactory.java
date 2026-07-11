package sh.harold.kinetics.plugin.physics;

import com.github.stephengold.joltjni.AaBox;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.CapsuleShape;
import com.github.stephengold.joltjni.ConvexHullShapeSettings;
import com.github.stephengold.joltjni.CylinderShape;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.OffsetCenterOfMassShapeSettings;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.ShapeResult;
import com.github.stephengold.joltjni.SphereShape;
import com.github.stephengold.joltjni.StaticCompoundShapeSettings;
import com.github.stephengold.joltjni.readonly.ConstShape;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import sh.harold.kinetics.api.PhysicsShape;
import sh.harold.kinetics.api.Rotation;
import sh.harold.kinetics.api.Vec3;

public final class ShapeFactory implements AutoCloseable {
    public static final double MIN_THICKNESS = 0.005;
    private static final double JOLT_DEFAULT_DENSITY = 1_000.0;

    private final Map<ShapeKey, CacheEntry> cache = new HashMap<>();
    private volatile long hits;
    private volatile long misses;

    public ShapeLease acquire(PhysicsShape definition, Vec3 scale, Vec3 centreOfMassOffset) {
        ShapeKey key = new ShapeKey(definition, scale, centreOfMassOffset);
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            hits++;
            entry.references++;
            return new ShapeLease(this, key, entry.shape);
        }
        misses++;
        CachedShape created = create(definition, scale, centreOfMassOffset);
        cache.put(key, new CacheEntry(created));
        return new ShapeLease(this, key, created);
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    private CachedShape create(PhysicsShape definition, Vec3 scale, Vec3 centreOfMassOffset) {
        ConstShape shape = buildUnscaled(definition);
        boolean retained = false;
        try {
            Vec3 physicalScale = physicalScale(shape, scale);
            if (!physicalScale.equals(Vec3.ONE)) {
                com.github.stephengold.joltjni.Vec3 factors = jolt(physicalScale);
                if (!shape.isValidScale(factors)) {
                    throw new IllegalArgumentException("Scale " + scale
                            + " is unsupported for " + definition.getClass().getSimpleName());
                }
                try (ShapeResult result = shape.scaleShape(factors)) {
                    requireValid(result, "scale");
                    ShapeRefC scaled = result.get();
                    shape.close();
                    shape = scaled;
                }
            }
            if (!centreOfMassOffset.equals(Vec3.ZERO)) {
                com.github.stephengold.joltjni.Vec3 offset = jolt(centreOfMassOffset);
                try (OffsetCenterOfMassShapeSettings settings
                                = new OffsetCenterOfMassShapeSettings(offset, shape);
                        ShapeResult result = settings.create()) {
                    requireValid(result, "centre-of-mass offset");
                    ShapeRefC offsetShape = result.get();
                    shape.close();
                    shape = offsetShape;
                }
            }

            try (AaBox bounds = shape.getLocalBounds();
                    MassProperties properties = shape.getMassProperties()) {
                com.github.stephengold.joltjni.Vec3 min = bounds.getMin();
                com.github.stephengold.joltjni.Vec3 max = bounds.getMax();
                com.github.stephengold.joltjni.Vec3 nativeCentre = shape.getCenterOfMass();
                Vec3 centreOfMass = new Vec3(
                        nativeCentre.getX(), nativeCentre.getY(), nativeCentre.getZ());
                Vec3 dimensions = new Vec3(
                        max.getX() - min.getX(),
                        max.getY() - min.getY(),
                        max.getZ() - min.getZ());
                Vec3 boundsCentre = new Vec3(
                        (min.getX() + max.getX()) * 0.5,
                        (min.getY() + max.getY()) * 0.5,
                        (min.getZ() + max.getZ()) * 0.5);
                Vec3 halfExtents = dimensions.multiply(0.5);
                double minimum = Math.min(dimensions.x(), Math.min(dimensions.y(), dimensions.z()));
                if (minimum < MIN_THICKNESS * 0.999) {
                    throw new IllegalArgumentException("Scaled collider thickness " + minimum
                            + " is below the supported minimum " + MIN_THICKNESS);
                }
                double volume = properties.getMass() / JOLT_DEFAULT_DENSITY;
                if (!(volume > 0.0) || !Double.isFinite(volume)) {
                    throw new IllegalArgumentException("Collider has invalid volume " + volume);
                }
                double radiusX = Math.max(Math.abs(min.getX()), Math.abs(max.getX()));
                double radiusY = Math.max(Math.abs(min.getY()), Math.abs(max.getY()));
                double radiusZ = Math.max(Math.abs(min.getZ()), Math.abs(max.getZ()));
                double maximumRadius = Math.sqrt(
                        radiusX * radiusX + radiusY * radiusY + radiusZ * radiusZ);
                retained = true;
                return new CachedShape(shape, physicalScale, volume, dimensions, minimum, centreOfMass,
                        boundsCentre, halfExtents, maximumRadius, ProjectedAreaModel.create(definition, physicalScale));
            }
        } finally {
            if (!retained) shape.close();
        }
    }

    private static Vec3 physicalScale(ConstShape shape, Vec3 requested) {
        try (AaBox bounds = shape.getLocalBounds()) {
            com.github.stephengold.joltjni.Vec3 min = bounds.getMin();
            com.github.stephengold.joltjni.Vec3 max = bounds.getMax();
            double width = max.getX() - min.getX();
            double height = max.getY() - min.getY();
            double depth = max.getZ() - min.getZ();
            if (!(width > 0.0) || !(height > 0.0) || !(depth > 0.0))
                throw new IllegalArgumentException("Collider must have volume on every axis");
            if (width * requested.x() < MIN_THICKNESS
                    || height * requested.y() < MIN_THICKNESS
                    || depth * requested.z() < MIN_THICKNESS) {
                throw new IllegalArgumentException("Scaled collider thickness is below the supported minimum "
                        + MIN_THICKNESS);
            }
            return requested;
        }
    }

    private ConstShape buildUnscaled(PhysicsShape definition) {
        return switch (definition) {
            case PhysicsShape.Box box -> {
                Vec3 size = box.dimensions();
                float hx = positiveFloat(size.x() * 0.5, "box half-width");
                float hy = positiveFloat(size.y() * 0.5, "box half-height");
                float hz = positiveFloat(size.z() * 0.5, "box half-depth");
                float radius = convexRadius(hx, hy, hz);
                yield new BoxShape(new com.github.stephengold.joltjni.Vec3(hx, hy, hz), radius);
            }
            case PhysicsShape.Sphere sphere -> new SphereShape(positiveFloat(sphere.radius(), "sphere radius"));
            case PhysicsShape.Capsule capsule -> capsule.cylindricalHeight() == 0.0
                    ? new SphereShape(positiveFloat(capsule.radius(), "capsule radius"))
                    : new CapsuleShape(
                            positiveFloat(capsule.cylindricalHeight() * 0.5, "capsule half-height"),
                            positiveFloat(capsule.radius(), "capsule radius"));
            case PhysicsShape.Cylinder cylinder -> {
                float halfHeight = positiveFloat(cylinder.height() * 0.5, "cylinder half-height");
                float radius = positiveFloat(cylinder.radius(), "cylinder radius");
                yield new CylinderShape(halfHeight, radius,
                        convexRadius(halfHeight, radius, radius));
            }
            case PhysicsShape.ConvexHull hull -> buildHull(hull);
            case PhysicsShape.Compound compound -> buildCompound(compound);
        };
    }

    private ConstShape buildHull(PhysicsShape.ConvexHull hull) {
        List<com.github.stephengold.joltjni.Vec3> points = new ArrayList<>(hull.points().size());
        for (Vec3 point : hull.points()) {
            points.add(jolt(point));
        }
        try (ConvexHullShapeSettings settings = new ConvexHullShapeSettings(points, 0.005f);
                ShapeResult result = settings.create()) {
            requireValid(result, "convex hull");
            return result.get();
        }
    }

    private ConstShape buildCompound(PhysicsShape.Compound compound) {
        try (StaticCompoundShapeSettings settings = new StaticCompoundShapeSettings()) {
            for (PhysicsShape.Child child : compound.children()) {
                try (ConstShape childShape = buildUnscaled(child.shape())) {
                    com.github.stephengold.joltjni.Vec3 offset = jolt(child.pose().position());
                    Quat rotation = jolt(child.pose().rotation());
                    settings.addShape(offset, rotation, childShape);
                }
            }
            try (ShapeResult result = settings.create()) {
                requireValid(result, "compound");
                return result.get();
            }
        }
    }

    private static float convexRadius(float x, float y, float z) {
        return Math.min(0.005f, Math.min(x, Math.min(y, z)) * 0.1f);
    }

    private static void requireValid(ShapeResult result, String operation) {
        if (result.hasError()) {
            throw new IllegalArgumentException("Invalid " + operation + ": " + result.getError());
        }
    }

    private static com.github.stephengold.joltjni.Vec3 jolt(Vec3 value) {
        return new com.github.stephengold.joltjni.Vec3(
                JoltScene.nativeFloat(value.x(), "shape.x"), JoltScene.nativeFloat(value.y(), "shape.y"),
                JoltScene.nativeFloat(value.z(), "shape.z"));
    }

    private static float positiveFloat(double value, String name) {
        float converted = JoltScene.nativeFloat(value, name);
        if (!(converted > 0f)) throw new IllegalArgumentException(name + " is too small for Jolt");
        return converted;
    }

    private static Quat jolt(Rotation value) {
        return new Quat((float) value.x(), (float) value.y(),
                (float) value.z(), (float) value.w());
    }

    @Override
    public void close() {
        Throwable firstFailure = null;
        for (CacheEntry value : cache.values()) {
            try {
                value.shape.shape().close();
            } catch (Throwable failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        cache.clear();
        if (firstFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (firstFailure instanceof Error error) throw error;
        if (firstFailure != null) throw new CompletionException(firstFailure);
    }

    private void release(ShapeKey key, CachedShape shape) {
        CacheEntry entry = cache.get(key);
        if (entry == null || entry.shape != shape) return;
        if (--entry.references == 0) {
            cache.remove(key);
            shape.shape().close();
        }
    }

    public static final class ShapeLease implements AutoCloseable {
        private ShapeFactory owner;
        private final ShapeKey key;
        private final CachedShape shape;

        private ShapeLease(ShapeFactory owner, ShapeKey key, CachedShape shape) {
            this.owner = owner;
            this.key = key;
            this.shape = shape;
        }

        public CachedShape shape() {
            return shape;
        }

        @Override
        public void close() {
            ShapeFactory current = owner;
            if (current == null) return;
            owner = null;
            current.release(key, shape);
        }
    }

    public record CachedShape(
            ConstShape shape,
            Vec3 physicalScale,
            double volume,
            Vec3 dimensions,
            double minimumThickness,
            Vec3 centreOfMass,
            Vec3 boundsCentre,
            Vec3 halfExtents,
            double maximumRadius,
            ProjectedAreaModel areaModel
    ) {
        public double massAtDensity(double density) {
            return volume * density;
        }

        public double projectedArea(Rotation bodyRotation, double worldX, double worldY, double worldZ) {
            double tx = 2.0 * (-bodyRotation.y() * worldZ + bodyRotation.z() * worldY);
            double ty = 2.0 * (-bodyRotation.z() * worldX + bodyRotation.x() * worldZ);
            double tz = 2.0 * (-bodyRotation.x() * worldY + bodyRotation.y() * worldX);
            double localX = worldX + bodyRotation.w() * tx
                    - bodyRotation.y() * tz + bodyRotation.z() * ty;
            double localY = worldY + bodyRotation.w() * ty
                    - bodyRotation.z() * tx + bodyRotation.x() * tz;
            double localZ = worldZ + bodyRotation.w() * tz
                    - bodyRotation.x() * ty + bodyRotation.y() * tx;
            return areaModel.area(localX, localY, localZ);
        }
    }

    private record ShapeKey(PhysicsShape definition, Vec3 scale, Vec3 centreOfMassOffset) {
    }

    private static final class CacheEntry {
        final CachedShape shape;
        int references = 1;

        CacheEntry(CachedShape shape) {
            this.shape = shape;
        }
    }
}
