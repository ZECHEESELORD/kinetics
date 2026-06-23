package sh.harold.kinetics.plugin.material;

import java.util.Objects;
import org.bukkit.Material;
import sh.harold.kinetics.api.PhysicsMaterial;

/** Resolves broad vanilla material families, then applies consumer overrides. */
public final class MaterialResolver {
    private static final Profile DEFAULT = new Profile(1_000.0, 0.60, 0.48, 0.08, 0.03, 0.05, 0.90);
    private static final Profile ORGANIC = new Profile(900.0, 0.70, 0.55, 0.08, 0.08, 0.10, 1.10);
    private static final Profile WOOD = new Profile(700.0, 0.62, 0.48, 0.10, 0.04, 0.07, 1.05);
    private static final Profile STONE = new Profile(2_500.0, 0.80, 0.65, 0.03, 0.02, 0.04, 0.90);
    private static final Profile SOIL = new Profile(1_600.0, 0.88, 0.72, 0.02, 0.08, 0.10, 1.00);
    private static final Profile METAL = new Profile(7_800.0, 0.50, 0.36, 0.04, 0.015, 0.03, 0.80);
    private static final Profile GOLD = new Profile(19_300.0, 0.42, 0.30, 0.03, 0.02, 0.04, 0.80);
    private static final Profile COPPER = new Profile(8_960.0, 0.46, 0.33, 0.04, 0.02, 0.04, 0.80);
    private static final Profile GEM = new Profile(3_500.0, 0.45, 0.32, 0.04, 0.015, 0.03, 0.75);
    private static final Profile GLASS = new Profile(2_500.0, 0.55, 0.40, 0.08, 0.015, 0.03, 0.75);
    private static final Profile ICE = new Profile(917.0, 0.08, 0.03, 0.03, 0.01, 0.02, 0.70);
    private static final Profile WOOL = new Profile(120.0, 0.92, 0.78, 0.02, 0.12, 0.18, 1.20);
    private static final Profile SLIME = new Profile(1_100.0, 0.78, 0.62, 0.80, 0.08, 0.12, 1.10);
    private static final Profile HONEY = new Profile(1_420.0, 1.10, 0.92, 0.00, 0.20, 0.25, 1.20);

    private MaterialResolver() {
    }

    public static ResolvedMaterial resolve(Material material, PhysicsMaterial overrides) {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(overrides, "overrides");
        Profile inferred = infer(material);
        return new ResolvedMaterial(
                overrides.densityKilogramsPerCubicMetre().orElse(inferred.density),
                overrides.staticFriction().orElse(inferred.staticFriction),
                overrides.dynamicFriction().orElse(inferred.dynamicFriction),
                overrides.restitution().orElse(inferred.restitution),
                overrides.linearDamping().orElse(inferred.linearDamping),
                overrides.angularDamping().orElse(inferred.angularDamping),
                overrides.dragCoefficient().orElse(inferred.dragCoefficient));
    }

    private static Profile infer(Material material) {
        String name = material.name();
        if (name.contains("SLIME")) {
            return SLIME;
        }
        if (name.contains("HONEY")) {
            return HONEY;
        }
        if (name.equals("ICE") || name.endsWith("_ICE")) {
            return ICE;
        }
        if (containsAny(name, "GLASS", "AMETHYST", "CRYSTAL")) {
            return GLASS;
        }
        if (containsAny(name, "WOOL", "CARPET", "SPONGE")) {
            return WOOL;
        }
        if (name.contains("GOLD")) {
            return GOLD;
        }
        if (name.contains("COPPER")) {
            return COPPER;
        }
        if (containsAny(name, "DIAMOND", "EMERALD")) {
            return GEM;
        }
        if (containsAny(name, "IRON", "STEEL", "NETHERITE", "CHAIN", "ANVIL", "CAULDRON", "HOPPER")) {
            return METAL;
        }
        if (containsAny(name, "LOG", "WOOD", "PLANK", "STEM", "HYPHAE", "BAMBOO", "CHEST", "BARREL")) {
            return WOOD;
        }
        if (containsAny(name, "STONE", "DEEPSLATE", "BRICK", "CONCRETE", "TERRACOTTA", "BASALT",
                "BLACKSTONE", "QUARTZ", "PRISMARINE", "TUFF", "GRANITE", "DIORITE", "ANDESITE")) {
            return STONE;
        }
        if (containsAny(name, "DIRT", "GRASS", "MUD", "SAND", "GRAVEL", "CLAY", "SOUL_SOIL", "SOUL_SAND")) {
            return SOIL;
        }
        if (material.isEdible() || containsAny(name, "LEAVES", "MOSS", "VINE", "FLOWER", "CROP", "HAY")) {
            return ORGANIC;
        }
        return DEFAULT;
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private record Profile(
            double density,
            double staticFriction,
            double dynamicFriction,
            double restitution,
            double linearDamping,
            double angularDamping,
            double dragCoefficient) {
    }
}
