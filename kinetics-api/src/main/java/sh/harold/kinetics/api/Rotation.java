package sh.harold.kinetics.api;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;

/** Immutable, normalized quaternion. */
public record Rotation(double x, double y, double z, double w) {
    public static final Rotation IDENTITY = new Rotation(0.0, 0.0, 0.0, 1.0);

    public Rotation {
        Vec3.requireFinite(x, "x");
        Vec3.requireFinite(y, "y");
        Vec3.requireFinite(z, "z");
        Vec3.requireFinite(w, "w");
        double maximum = Math.max(Math.max(Math.abs(x), Math.abs(y)),
                Math.max(Math.abs(z), Math.abs(w)));
        if (maximum == 0.0) {
            throw new IllegalArgumentException("Quaternion must have non-zero length");
        }
        double sx = x / maximum, sy = y / maximum, sz = z / maximum, sw = w / maximum;
        double inverseNorm = 1.0 / Math.sqrt(sx * sx + sy * sy + sz * sz + sw * sw);
        x = sx * inverseNorm;
        y = sy * inverseNorm;
        z = sz * inverseNorm;
        w = sw * inverseNorm;
    }

    public static Rotation of(Quaterniondc quaternion) {
        return new Rotation(quaternion.x(), quaternion.y(), quaternion.z(), quaternion.w());
    }

    public Quaterniond toJoml() {
        return new Quaterniond(x, y, z, w);
    }
}
