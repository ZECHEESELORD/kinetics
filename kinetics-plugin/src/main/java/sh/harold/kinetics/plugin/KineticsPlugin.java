package sh.harold.kinetics.plugin;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.util.PEVersion;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
    private boolean runtimeRetained;
    private boolean demoEnabled;
    private DemoLayer demoLayer;
    private final PaperDebugRenderer debugRenderer = new PaperDebugRenderer();

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            demoEnabled = getConfig().getBoolean("demo", false);
            requirePacketEvents();
            int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
            runtime = new JoltRuntime(getDataFolder().toPath(), workers);
            coordinator = new PhysicsCoordinator(failure ->
                    getLogger().severe("Physics coordinator failed: "
                            + KineticsMessages.failureDetail(failure)));
            bridgeFactory = createSceneBridgeFactory();
            service = new KineticsServiceImpl(this, runtime, coordinator, bridgeFactory);
            service.register();
            if (demoEnabled) demoLayer = new DemoLayer(this, service);
            PaperEventRouter eventRouter = new PaperEventRouter(this, service::scenes);
            getServer().getPluginManager().registerEvents(eventRouter, this);
            packetListener = PacketEvents.getAPI().getEventManager()
                    .registerListener(eventRouter, PacketListenerPriority.NORMAL);
            registerCommand("kinetics", "Kinetics runtime diagnostics", new StatsCommand());
            tickTask = getServer().getScheduler().runTaskTimer(this, service::tick, 1L, 1L);
            getLogger().info("Kinetics enabled with " + workers + " Jolt worker thread(s)");
        } catch (IOException | RuntimeException | LinkageError failure) {
            getLogger().severe(
                    "Kinetics could not start: " + KineticsMessages.failureDetail(failure));
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
        if (demoLayer != null) {
            try {
                demoLayer.close();
            } catch (Throwable failure) {
                getLogger().warning("Could not close the Kinetics demonstration: "
                        + KineticsMessages.failureDetail(failure));
            }
            demoLayer = null;
        }

        if (packetListener != null) {
            try {
                var api = PacketEvents.getAPI();
                if (api != null) api.getEventManager().unregisterListener(packetListener);
            } catch (RuntimeException | LinkageError failure) {
                getLogger().warning("Could not unregister PacketEvents listener: "
                        + KineticsMessages.failureDetail(failure));
            }
            packetListener = null;
        }
        if (tickTask != null) {
            try {
                tickTask.cancel();
            } catch (RuntimeException failure) {
                getLogger().warning(
                        "Could not cancel the Kinetics tick task: "
                                + KineticsMessages.failureDetail(failure));
            }
            tickTask = null;
        }

        KineticsServiceImpl closingService = service;
        service = null;
        if (closingService != null) {
            try {
                closingService.close();
            } catch (Throwable failure) {
                getLogger().severe(
                        "Could not close the Kinetics service: "
                                + KineticsMessages.failureDetail(failure));
            }
        }

        boolean coordinatorTerminated = coordinator == null;
        PhysicsCoordinator closingCoordinator = coordinator;
        coordinator = null;
        if (closingCoordinator != null) {
            if (!closingCoordinator.flush()) {
                getLogger().warning("The physics command flush did not complete cleanly.");
            }
            drainLateMainTasks(closingService);
            coordinatorTerminated = closingCoordinator.closeAndAwait(15, TimeUnit.SECONDS);
            drainLateMainTasks(closingService);
            if (!coordinatorTerminated) {
                getLogger().severe(
                        "THE PHYSICS COORDINATOR DID NOT TERMINATE CLEANLY. "
                                + "Jolt native resources will be retained; restart the JVM before "
                                + "attempting to enable Kinetics again.");
            }
        }

        if (bridgeFactory != null) {
            try {
                bridgeFactory.close();
            } catch (Throwable failure) {
                getLogger().severe(
                        "Could not close Paper scene resources: "
                                + KineticsMessages.failureDetail(failure));
            }
            bridgeFactory = null;
        }

        if (runtime != null && !runtimeRetained) {
            if (!coordinatorTerminated) {
                runtimeRetained = true;
            } else {
                try {
                    runtime.close();
                } catch (Throwable failure) {
                    getLogger().severe(
                            "Jolt runtime cleanup failed: "
                                    + KineticsMessages.failureDetail(failure));
                } finally {
                    runtime = null;
                }
            }
        }
    }

    private void drainLateMainTasks(KineticsServiceImpl closingService) {
        if (closingService == null) return;
        try {
            closingService.drainLateMainTasks();
        } catch (Throwable failure) {
            getLogger().severe(
                    "Could not drain shutdown callbacks: "
                            + KineticsMessages.failureDetail(failure));
        }
    }

    private final class StatsCommand implements BasicCommand {
        @Override
        public void execute(CommandSourceStack source, String[] args) {
            CommandSender sender = source.getSender();
            if (args.length == 0) {
                sender.sendMessage(KineticsMessages.usage(demoEnabled));
                return;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("stats")) {
                sendStats(sender);
                return;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
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
            if (args[0].equalsIgnoreCase("demo")) {
                executeDemo(sender, Arrays.copyOfRange(args, 1, args.length));
                return;
            }
            sender.sendMessage(KineticsMessages.usage(demoEnabled));
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            if (args.length <= 1) {
                return demoEnabled ? List.of("stats", "debug", "demo") : List.of("stats", "debug");
            }
            if (demoEnabled && args[0].equalsIgnoreCase("demo") && args.length == 2) {
                return List.of("sampler", "spectacle", "reset", "stop");
            }
            return List.of();
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

        private void executeDemo(CommandSender sender, String[] args) {
            if (!demoEnabled || demoLayer == null) {
                sender.sendMessage(KineticsMessages.demoDisabled());
                return;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(KineticsMessages.demoPlayerOnly());
                return;
            }

            final DemoCommandRequest request;
            try {
                request = DemoCommandRequest.parse(args);
            } catch (IllegalArgumentException failure) {
                sender.sendMessage(KineticsMessages.demoUsage());
                return;
            }

            switch (request.action()) {
                case SAMPLER, SPECTACLE ->
                        demoLayer.start(player, request.action().mode(), request.coordinates());
                case RESET -> demoLayer.reset(player);
                case STOP -> demoLayer.stop(player);
            }
        }
    }
}
