package sh.harold.kinetics.api;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class Kinetics {
    private Kinetics() {
    }

    public static KineticsContext forPlugin(JavaPlugin owner) {
        Objects.requireNonNull(owner, "owner");
        KineticsService service = Bukkit.getServicesManager().load(KineticsService.class);
        if (service == null) {
            throw new IllegalStateException("Kinetics is not installed or has not finished enabling");
        }
        return service.forPlugin(owner);
    }
}
