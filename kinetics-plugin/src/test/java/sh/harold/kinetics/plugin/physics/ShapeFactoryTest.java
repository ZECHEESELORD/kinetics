package sh.harold.kinetics.plugin.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.ConvexHullShape;
import com.github.stephengold.joltjni.CylinderShape;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.enumerate.EShapeSubType;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import sh.harold.kinetics.api.PhysicsShape;
import sh.harold.kinetics.api.Pose;
import sh.harold.kinetics.api.Vec3;
import sh.harold.kinetics.plugin.shape.ColliderInference;

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

            PhysicsShape cubeHull = PhysicsShape.convexHull(List.of(
                    new Vec3(-0.5, -0.5, -0.5), new Vec3(0.5, -0.5, -0.5),
                    new Vec3(-0.5, 0.5, -0.5), new Vec3(0.5, 0.5, -0.5),
                    new Vec3(-0.5, -0.5, 0.5), new Vec3(0.5, -0.5, 0.5),
                    new Vec3(-0.5, 0.5, 0.5), new Vec3(0.5, 0.5, 0.5)));
            try (ShapeFactory.ShapeLease box = factory.acquire(
                    PhysicsShape.box(1, 2, 3), Vec3.ONE, Vec3.ZERO);
                    ShapeFactory.ShapeLease cylinder = factory.acquire(
                            PhysicsShape.cylinder(0.5, 2), Vec3.ONE, Vec3.ZERO);
                    ShapeFactory.ShapeLease hull = factory.acquire(
                            cubeHull, Vec3.ONE, Vec3.ZERO)) {
                assertEquals(0.0f, assertInstanceOf(
                        BoxShape.class, box.shape().shape()).getConvexRadius());
                assertEquals(0.0f, assertInstanceOf(
                        CylinderShape.class, cylinder.shape().shape()).getConvexRadius());
                assertEquals(0.0f, assertInstanceOf(
                        ConvexHullShape.class,
                        assertInstanceOf(ShapeRefC.class, hull.shape().shape()).getPtr())
                        .getConvexRadius());
            }

            assertGeometry(factory, PhysicsShape.box(1, 2, 3), new Vec3(2, 3, 4),
                    new Vec3(2, 6, 12), Vec3.ZERO);
            assertGeometry(factory, PhysicsShape.sphere(0.5), new Vec3(3, 3, 3),
                    new Vec3(3, 3, 3), Vec3.ZERO);
            assertGeometry(factory, PhysicsShape.capsule(0.25, 1), new Vec3(2, 2, 2),
                    new Vec3(1, 3, 1), Vec3.ZERO);
            assertGeometry(factory, PhysicsShape.cylinder(0.4, 1.2), new Vec3(2, 3, 2),
                    new Vec3(1.6, 3.6, 1.6), Vec3.ZERO);
            assertGeometry(factory, cubeHull,
                    new Vec3(2, 3, 4), new Vec3(2, 3, 4), Vec3.ZERO);
            assertGeometry(factory, PhysicsShape.compound(List.of(
                            new PhysicsShape.Child(PhysicsShape.box(1, 1, 1),
                                    Pose.at(new Vec3(-0.75, 0, 0))),
                            new PhysicsShape.Child(PhysicsShape.box(1, 1, 1),
                                    Pose.at(new Vec3(0.75, 0, 0))))),
                    new Vec3(2, 3, 4), new Vec3(5, 3, 4), Vec3.ZERO);

            assertGeneratedItemGeometry(factory, Material.ENDER_PEARL, 3.5,
                    new Vec3(2.84375, 2.84375, 0.21875),
                    new Vec3(0.109375, -0.109375, 0));
            assertGeneratedItemGeometry(factory, Material.SLIME_BALL, 1.3,
                    new Vec3(0.975, 0.975, 0.08125), Vec3.ZERO);
            assertGeneratedItemGeometry(factory, Material.BLAZE_ROD, 1.0,
                    new Vec3(0.875, 0.875, 0.0625), Vec3.ZERO);
        }
    }

    private static void assertGeneratedItemGeometry(ShapeFactory factory, Material material,
            double scale, Vec3 dimensions, Vec3 boundsCentre) {
        PhysicsShape shape = ColliderInference.inferVanillaItem(material).shape();
        assertGeometry(factory, shape, new Vec3(scale, scale, scale), dimensions, boundsCentre);
    }

    private static void assertGeometry(ShapeFactory factory, PhysicsShape shape, Vec3 scale,
            Vec3 dimensions, Vec3 boundsCentre) {
        try (ShapeFactory.ShapeLease lease = factory.acquire(shape, scale, Vec3.ZERO)) {
            assertVec(dimensions, lease.shape().dimensions());
            assertVec(boundsCentre,
                    lease.shape().centreOfMass().add(lease.shape().boundsCentre()));
        }
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), 1.0e-6);
        assertEquals(expected.y(), actual.y(), 1.0e-6);
        assertEquals(expected.z(), actual.z(), 1.0e-6);
    }
}
