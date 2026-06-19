package sh.harold.kinetics.api;

import org.bukkit.plugin.java.JavaPlugin;

/** Bukkit service bridge implemented by the installed Kinetics plugin. */
public interface KineticsService {
    KineticsContext forPlugin(JavaPlugin owner);
}
