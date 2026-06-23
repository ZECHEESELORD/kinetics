package sh.harold.kinetics.plugin.material;

/** Fully resolved SI material properties consumed by the physics backend. */
public record ResolvedMaterial(
        double densityKilogramsPerCubicMetre,
        double staticFriction,
        double dynamicFriction,
        double restitution,
        double linearDamping,
        double angularDamping,
        double dragCoefficient) {
}
