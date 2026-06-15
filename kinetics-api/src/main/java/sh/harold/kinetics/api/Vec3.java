package sh.harold.kinetics.api;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Immutable three-dimensional vector in Kinetics' metre/block coordinate system. */
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);
    public static final Vec3 ONE = new Vec3(1.0, 1.0, 1.0);

    public Vec3 {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
    }

    public static Vec3 of(Vector vector) {
        return new Vec3(vector.getX(), vector.getY(), vector.getZ());
    }

    public static Vec3 of(Vector3dc vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    public static Vec3 of(Location location) {
        return new Vec3(location.getX(), location.getY(), location.getZ());
    }

    public Vector toBukkit() {
        return new Vector(x, y, z);
    }

    public Vector3d toJoml() {
        return new Vector3d(x, y, z);
    }

    public Vec3 add(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 subtract(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public Vec3 multiply(double scalar) {
        requireFinite(scalar, "scalar");
        return new Vec3(x * scalar, y * scalar, z * scalar);
    }

    public double length() {
        return Math.hypot(Math.hypot(x, y), z);
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public Vec3 normalized() {
        double maximum = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (maximum == 0.0) {
            throw new IllegalStateException("Cannot normalize a zero vector");
        }
        double sx = x / maximum, sy = y / maximum, sz = z / maximum;
        double inverseLength = 1.0 / Math.sqrt(sx * sx + sy * sy + sz * sz);
        return new Vec3(sx * inverseLength, sy * inverseLength, sz * inverseLength);
    }

    static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
