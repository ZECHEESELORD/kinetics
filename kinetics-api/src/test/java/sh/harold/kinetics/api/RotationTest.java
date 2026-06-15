package sh.harold.kinetics.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationTest {
    @Test
    void normalizesHugeFiniteComponentsWithoutOverflow() {
        Rotation rotation = new Rotation(
                Double.MAX_VALUE,
                -Double.MAX_VALUE,
                Double.MAX_VALUE / 2.0,
                -Double.MAX_VALUE / 4.0
        );

        assertTrue(Double.isFinite(rotation.x()));
        assertTrue(Double.isFinite(rotation.y()));
        assertTrue(Double.isFinite(rotation.z()));
        assertTrue(Double.isFinite(rotation.w()));
        double normSquared = rotation.x() * rotation.x()
                + rotation.y() * rotation.y()
                + rotation.z() * rotation.z()
                + rotation.w() * rotation.w();
        assertEquals(1.0, normSquared, 1.0e-15);
    }

    @Test
    void rejectsZeroLengthQuaternion() {
        assertThrows(IllegalArgumentException.class, () -> new Rotation(0.0, -0.0, 0.0, -0.0));
    }
}