package sh.harold.kinetics.plugin;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.util.PEVersion;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import sh.harold.kinetics.plugin.paper.PaperEventRouter;
import sh.harold.kinetics.plugin.paper.PaperDebugRenderer;
import sh.harold.kinetics.plugin.paper.PaperSceneBridgeFactory;
import sh.harold.kinetics.plugin.physics.JoltRuntime;
import sh.harold.kinetics.plugin.physics.PhysicsCoordinator;

public final class KineticsPlugin extends JavaPlugin {
    private JoltRuntime runtime;
    private PhysicsCoordinator coordinator;
    private SceneBridgeFactory bridgeFactory;
    private KineticsServiceImpl service;
    private BukkitTask tickTask;
    private PacketListenerCommon packetListener;
    private final PaperDebugRenderer debugRenderer = new PaperDebugRenderer();

    @Override
    public void onEnable() {
        try {
            requirePacketEvents();
            int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
            runtime = new JoltRuntime(getDataFolder().toPath(), workers);
            coordinator = new PhysicsCoordinator(failure ->
                    getLogger().severe("Physics coordinator failed: " + failure));
            bridgeFactory = createSceneBridgeFactory();
            service = new KineticsServiceImpl(this, runtime, coordinator, bridgeFactory);
            service.register();
            PaperEventRouter eventRouter = new PaperEventRouter(this, service::scenes);
            getServer().getPluginManager().registerEvents(eventRouter, this);
            packetListener = PacketEvents.getAPI().getEventManager()
                    .registerListener(eventRouter, PacketListenerPriority.NORMAL);
            registerCommand("kinetics", "Kinetics runtime diagnostics", new StatsCommand());
            tickTask = getServer().getScheduler().runTaskTimer(this, service::tick, 1L, 1L);
            getLogger().info("Kinetics enabled with " + workers + " Jolt worker thread(s)");
        } catch (IOException | RuntimeException | LinkageError failure) {
            getLogger().severe("Kinetics could not start: " + failure.getMessage());
            shutdown();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private static void requirePacketEvents() {
        var api = PacketEvents.getAPI();
        if (api == null || !api.isInitialized()) {
            throw new IllegalStateException("PacketEvents must be initialized before Kinetics");
        }
        PEVersion minimum = new PEVersion(2, 13, 0);
        if (api.getVersion().isOlderThan(minimum)) {
            throw new IllegalStateException(
                    "PacketEvents 2.13.0 or newer is required; found " + api.getVersion());
        }
    }

    private SceneBridgeFactory createSceneBridgeFactory() {
        return new PaperSceneBridgeFactory(this);
    }

    @Override
    public void onDisable() {
        shutdown();
    }

    private void shutdown() {
        if (packetListener != null) {
            try {
                var api = PacketEvents.getAPI();
                if (api != null) api.getEventManager().unregisterListener(packetListener);
            } catch (RuntimeException | LinkageError failure) {
                getLogger().warning("Could not unregister PacketEvents listener: " + failure.getMessage());
            }
            packetListener = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        KineticsServiceImpl closingService = service;
        if (closingService != null) closingService.close();
        if (coordinator != null) {
            coordinator.flush();
            if (closingService != null) closingService.drainLateMainTasks();
            coordinator.close();
            if (closingService != null) closingService.drainLateMainTasks();
            coordinator = null;
        }
        service = null;
        if (bridgeFactory != null) {
            bridgeFactory.close();
            bridgeFactory = null;
        }
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }

    private final class StatsCommand implements BasicCommand {
        @Override
        public void execute(CommandSourceStack source, String[] args) {
            CommandSender sender = source.getSender();
            if (args.length != 1) {
                sender.sendMessage(KineticsMessages.usage());
                return;
            }
            if (args[0].equalsIgnoreCase("stats")) {
                sendStats(sender);
                return;
            }
            if (args[0].equalsIgnoreCase("debug")) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(KineticsMessages.playerOnly());
                    return;
                }
                debugRenderer.renderSelected(player, coordinator == null ? List.of() : coordinator.scenes())
                        .whenComplete((rendered, failure) -> sender.sendMessage(failure == null
                                ? KineticsMessages.debugRendered(rendered)
                                : KineticsMessages.failure(failure)));
                return;
            }
            sender.sendMessage(KineticsMessages.usage());
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            return args.length <= 1 ? List.of("stats", "debug") : List.of();
        }

        @Override
        public String permission() {
            return "kinetics.admin";
        }

        private void sendStats(CommandSender sender) {
            if (coordinator == null || coordinator.scenes().isEmpty()) {
                sender.sendMessage(KineticsMessages.noScenes());
                return;
            }
            sender.sendMessage(KineticsMessages.sceneCount(coordinator.scenes().size()));
            coordinator.scenes().forEach(scene ->
                    sender.sendMessage(KineticsMessages.stats(scene.name(), scene.stats())));
        }
    }
}
