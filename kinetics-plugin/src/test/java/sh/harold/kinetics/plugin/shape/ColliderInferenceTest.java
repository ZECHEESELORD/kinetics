package sh.harold.kinetics.plugin.shape;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Arrays;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import sh.harold.kinetics.api.ColliderFidelity;
import sh.harold.kinetics.api.PhysicsShape;
import sh.harold.kinetics.api.Rotation;

class ColliderInferenceTest {
    private static final double PIXEL = 1.0 / 16.0;

    @Test
    void generatedShowcaseItemsMatchTheirOpaqueTexturePixels() {
        assertMask(Material.ENDER_PEARL,
                "................",
                "................",
                ".......###......",
                ".....#######....",
                "....#########...",
                "...###########..",
                "...###########..",
                "..#############.",
                "..#############.",
                "..#############.",
                "...###########..",
                "...###########..",
                "....#########...",
                ".....#######....",
                ".......###......",
                "................");
        assertMask(Material.SLIME_BALL,
                "................",
                "................",
                "......####......",
                "....########....",
                "...##########...",
                "...##########...",
                "..############..",
                "..############..",
                "..############..",
                "..############..",
                "...##########...",
                "...##########...",
                "....########....",
                "......####......",
                "................",
                "................");
        assertMask(Material.BLAZE_ROD,
                "................",
                "............##..",
                "...........####.",
                "..........####..",
                ".........####...",
                "........####....",
                ".......####.....",
                "......####......",
                ".....####.......",
                "....####........",
                "...####.........",
                "..####..........",
                ".####...........",
                ".###............",
                ".##.............",
                "................");
    }

    private static void assertMask(Material material, String... expected) {
        InferredCollider inferred = ColliderInference.inferVanillaItem(material);
        assertEquals(ColliderFidelity.EXACT, inferred.fidelity());
        PhysicsShape.Compound compound = assertInstanceOf(
                PhysicsShape.Compound.class, inferred.shape());

        char[][] actual = new char[16][16];
        for (char[] row : actual) Arrays.fill(row, '.');
        for (PhysicsShape.Child child : compound.children()) {
            PhysicsShape.Box box = assertInstanceOf(PhysicsShape.Box.class, child.shape());
            assertEquals(Rotation.IDENTITY, child.pose().rotation());
            assertEquals(PIXEL, box.dimensions().y());
            assertEquals(PIXEL, box.dimensions().z());
            assertEquals(0.0, child.pose().position().z());

            int row = (int) Math.round(
                    (0.5 - child.pose().position().y()) * 16.0 - 0.5);
            int start = (int) Math.round(
                    (child.pose().position().x() - box.dimensions().x() * 0.5 + 0.5) * 16.0);
            int end = (int) Math.round(
                    (child.pose().position().x() + box.dimensions().x() * 0.5 + 0.5) * 16.0);
            for (int column = start; column < end; column++) {
                assertEquals('.', actual[row][column]);
                actual[row][column] = '#';
            }
        }

        String[] rows = new String[actual.length];
        for (int row = 0; row < actual.length; row++) rows[row] = new String(actual[row]);
        assertArrayEquals(expected, rows);
    }
}
