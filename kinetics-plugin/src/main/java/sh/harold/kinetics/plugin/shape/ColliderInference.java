package sh.harold.kinetics.plugin.shape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import sh.harold.kinetics.api.ColliderFidelity;
import sh.harold.kinetics.api.PhysicsShape;
import sh.harold.kinetics.api.Pose;
import sh.harold.kinetics.api.Vec3;

/** Vanilla block and item collider inference without resource-pack inspection. */
public final class ColliderInference {
    public static final String ITEM_CATALOG_VERSION = "minecraft-1.21.11-v2";
    private static final double ITEM_DEPTH = 1.0 / 16.0;
    private static final PhysicsShape APPROXIMATE_ITEM = PhysicsShape.box(0.5, 0.5, ITEM_DEPTH);

    /*
     * Opaque pixels from Mojang's 1.21.11 client archive
     * (SHA-1 ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd). item/generated extrudes
     * the texture from 7.5/16 through 8.5/16 on Z; FIXED uses unit scale.
     */
    private static final PhysicsShape ENDER_PEARL = generatedItem(
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
    private static final PhysicsShape SLIME_BALL = generatedItem(
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
    private static final PhysicsShape BLAZE_ROD = generatedItem(
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

    private static final PhysicsShape SWORD = compound(
            box(0.13, 0.52, 0.05, 0.0, 0.10),
            box(0.34, 0.07, 0.07, 0.0, -0.20),
            box(0.10, 0.22, 0.08, 0.0, -0.34));
    private static final PhysicsShape AXE = compound(
            box(0.09, 0.62, 0.07, -0.05, -0.04),
            box(0.38, 0.20, 0.08, 0.10, 0.23));
    private static final PhysicsShape TRIDENT = compound(
            box(0.06, 0.70, 0.06, 0.0, -0.07),
            box(0.30, 0.05, 0.06, 0.0, 0.28),
            box(0.04, 0.22, 0.06, -0.12, 0.38),
            box(0.04, 0.25, 0.06, 0.0, 0.40),
            box(0.04, 0.22, 0.06, 0.12, 0.38));
    private static final PhysicsShape MACE = compound(
            box(0.10, 0.55, 0.08, 0.0, -0.11),
            box(0.32, 0.26, 0.10, 0.0, 0.27),
            box(0.40, 0.08, 0.11, 0.0, 0.27));

    private static final PhysicsShape ROUND_FOOD = compound(
            box(0.40, 0.46, ITEM_DEPTH, 0.0, -0.01),
            box(0.50, 0.27, ITEM_DEPTH, 0.0, -0.02),
            box(0.08, 0.13, ITEM_DEPTH, 0.08, 0.27));
    private static final PhysicsShape ROOT_FOOD = compound(
            box(0.20, 0.42, ITEM_DEPTH, 0.0, -0.08),
            box(0.34, 0.16, ITEM_DEPTH, 0.0, 0.18));
    private static final PhysicsShape BOWL_FOOD = compound(
            box(0.52, 0.16, ITEM_DEPTH, 0.0, 0.04),
            box(0.38, 0.18, ITEM_DEPTH, 0.0, -0.11));
    private static final PhysicsShape MEAT_FOOD = compound(
            box(0.45, 0.28, ITEM_DEPTH, 0.04, 0.0),
            box(0.18, 0.14, ITEM_DEPTH, -0.25, -0.02));
    private static final PhysicsShape BAKED_FOOD = compound(
            box(0.52, 0.28, ITEM_DEPTH, 0.0, -0.04),
            box(0.40, 0.14, ITEM_DEPTH, 0.0, 0.17));
    private static final PhysicsShape FLAT_FOOD = compound(
            box(0.48, 0.34, ITEM_DEPTH, 0.0, 0.0),
            box(0.30, 0.44, ITEM_DEPTH, 0.0, 0.0));

    private ColliderInference() {
    }

    public static InferredCollider infer(BlockData blockData, Location reference) {
        Objects.requireNonNull(blockData, "blockData");
        Objects.requireNonNull(reference, "reference");

        List<BoundingBox> boxes = new ArrayList<>(blockData.getCollisionShape(reference).getBoundingBoxes());
        boxes.removeIf(box -> box.getWidthX() <= 0.0 || box.getHeight() <= 0.0 || box.getWidthZ() <= 0.0);
        boxes.sort(Comparator.comparingDouble(BoundingBox::getMinX)
                .thenComparingDouble(BoundingBox::getMinY)
                .thenComparingDouble(BoundingBox::getMinZ)
                .thenComparingDouble(BoundingBox::getMaxX)
                .thenComparingDouble(BoundingBox::getMaxY)
                .thenComparingDouble(BoundingBox::getMaxZ));
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException(blockData.getAsString() + " has no collision geometry");
        }

        List<PhysicsShape.Child> children = boxes.stream().map(ColliderInference::child).toList();
        PhysicsShape shape = children.size() == 1 && children.getFirst().pose().equals(Pose.IDENTITY)
                ? children.getFirst().shape()
                : PhysicsShape.compound(children);
        return new InferredCollider(shape, ColliderFidelity.EXACT);
    }

    public static InferredCollider infer(ItemStack item) {
        Objects.requireNonNull(item, "item");
        Material material = item.getType();
        if (item.hasItemMeta() && (item.getItemMeta().hasCustomModelData()
                || item.getItemMeta().hasCustomModelDataComponent()
                || item.getItemMeta().hasItemModel())) {
            return new InferredCollider(APPROXIMATE_ITEM, ColliderFidelity.APPROXIMATE);
        }
        return inferVanillaItem(material);
    }

    /** Infers the base vanilla model without per-stack custom-model overrides. */
    public static InferredCollider inferVanillaItem(Material material) {
        Objects.requireNonNull(material, "material");
        if (material == Material.ENDER_PEARL) {
            return new InferredCollider(ENDER_PEARL, ColliderFidelity.EXACT);
        }
        if (material == Material.SLIME_BALL) {
            return new InferredCollider(SLIME_BALL, ColliderFidelity.EXACT);
        }
        if (material == Material.BLAZE_ROD) {
            return new InferredCollider(BLAZE_ROD, ColliderFidelity.EXACT);
        }
        if (material.isAir()) {
            throw new IllegalArgumentException("Air has no item collider");
        }

        String name = material.name();
        PhysicsShape shape;
        if (name.endsWith("_SWORD")) {
            shape = SWORD;
        } else if (name.endsWith("_AXE")) {
            shape = AXE;
        } else if (material == Material.TRIDENT) {
            shape = TRIDENT;
        } else if (material == Material.MACE) {
            shape = MACE;
        } else if (material.isEdible()) {
            shape = foodShape(name);
        } else {
            return new InferredCollider(APPROXIMATE_ITEM, ColliderFidelity.APPROXIMATE);
        }
        return new InferredCollider(shape, ColliderFidelity.APPROXIMATE);
    }

    private static PhysicsShape foodShape(String name) {
        if (containsAny(name, "STEW", "SOUP")) {
            return BOWL_FOOD;
        }
        if (containsAny(name, "CARROT", "POTATO", "BEETROOT", "KELP")) {
            return ROOT_FOOD;
        }
        if (containsAny(name, "BEEF", "PORKCHOP", "MUTTON", "CHICKEN", "RABBIT", "COD", "SALMON",
                "FISH", "ROTTEN_FLESH", "SPIDER_EYE")) {
            return MEAT_FOOD;
        }
        if (containsAny(name, "BREAD", "PIE", "CAKE")) {
            return BAKED_FOOD;
        }
        if (containsAny(name, "APPLE", "BERRIES", "MELON", "CHORUS_FRUIT")) {
            return ROUND_FOOD;
        }
        return FLAT_FOOD;
    }

    private static PhysicsShape.Child child(BoundingBox box) {
        Vec3 dimensions = new Vec3(box.getWidthX(), box.getHeight(), box.getWidthZ());
        Vec3 centre = new Vec3(
                (box.getMinX() + box.getMaxX()) * 0.5 - 0.5,
                (box.getMinY() + box.getMaxY()) * 0.5 - 0.5,
                (box.getMinZ() + box.getMaxZ()) * 0.5 - 0.5);
        return new PhysicsShape.Child(new PhysicsShape.Box(dimensions), Pose.at(centre));
    }

    private static PhysicsShape compound(PhysicsShape.Child... children) {
        return PhysicsShape.compound(List.of(children));
    }

    private static PhysicsShape.Child box(double width, double height, double depth, double x, double y) {
        return new PhysicsShape.Child(PhysicsShape.box(width, height, depth), Pose.at(new Vec3(x, y, 0.0)));
    }

    private static PhysicsShape generatedItem(String... rows) {
        if (rows.length != 16) {
            throw new IllegalArgumentException("A generated item mask must contain 16 rows");
        }
        List<PhysicsShape.Child> children = new ArrayList<>();
        for (int row = 0; row < rows.length; row++) {
            String pixels = rows[row];
            if (pixels.length() != 16 || pixels.chars().anyMatch(pixel -> pixel != '.' && pixel != '#')) {
                throw new IllegalArgumentException("A generated item mask must be 16 pixels wide");
            }
            int column = 0;
            while (column < pixels.length()) {
                while (column < pixels.length() && pixels.charAt(column) == '.') column++;
                int start = column;
                while (column < pixels.length() && pixels.charAt(column) == '#') column++;
                if (column == start) continue;
                double width = (column - start) / 16.0;
                double x = (start + column) / 32.0 - 0.5;
                double y = 0.5 - (row + 0.5) / 16.0;
                children.add(box(width, ITEM_DEPTH, ITEM_DEPTH, x, y));
            }
        }
        return PhysicsShape.compound(children);
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
