package sh.harold.kinetics.plugin.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.stephengold.joltjni.enumerate.EShapeSubType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import sh.harold.kinetics.api.PhysicsShape;
import sh.harold.kinetics.api.Vec3;

class ShapeFactoryTest {
    @Test
    void validatesScalingCapsulesAndLeaseLifetime() throws Exception {
        Path directory = Path.of("build", "test-native");
        try (JoltRuntime runtime = new JoltRuntime(directory, 1);
                ShapeFactory factory = new ShapeFactory()) {
            ShapeFactory.ShapeLease first = factory.acquire(
                    PhysicsShape.box(1, 1, 1), Vec3.ONE, Vec3.ZERO);
            ShapeFactory.ShapeLease second = factory.acquire(
                    PhysicsShape.box(1, 1, 1), Vec3.ONE, Vec3.ZERO);
            assertEquals(1, factory.misses());
            assertEquals(1, factory.hits());
            first.close();
            second.close();

            try (ShapeFactory.ShapeLease ignored = factory.acquire(
                    PhysicsShape.box(1, 1, 1), Vec3.ONE, Vec3.ZERO)) {
                assertEquals(2, factory.misses());
            }
            try (ShapeFactory.ShapeLease capsule = factory.acquire(
                    PhysicsShape.capsule(0.5, 0), Vec3.ONE, Vec3.ZERO)) {
                assertEquals(EShapeSubType.Sphere, capsule.shape().shape().getSubType());
            }

            assertThrows(IllegalArgumentException.class, () -> factory.acquire(
                    PhysicsShape.sphere(1), new Vec3(1, 2, 1), Vec3.ZERO));
            assertThrows(IllegalArgumentException.class, () -> factory.acquire(
                    PhysicsShape.box(1, 1, 1), new Vec3(0.001, 1, 1), Vec3.ZERO));
        }
    }
}