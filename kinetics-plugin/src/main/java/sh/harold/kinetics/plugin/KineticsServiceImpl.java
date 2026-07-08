package sh.harold.kinetics.plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.kinetics.api.KineticsContext;
import sh.harold.kinetics.api.KineticsService;
import sh.harold.kinetics.plugin.physics.JoltRuntime;
import sh.harold.kinetics.plugin.physics.PhysicsCoordinator;

final class KineticsServiceImpl implements KineticsService, Listener, AutoCloseable {
    private final KineticsPlugin plugin;
    private final JoltRuntime runtime;
    private final PhysicsCoordinator coordinator;
    private final SceneBridgeFactory bridgeFactory;
    private final Map<JavaPlugin, KineticsContextImpl> contexts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> lateMainTasks = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;

    KineticsServiceImpl(KineticsPlugin plugin, JoltRuntime runtime,
            PhysicsCoordinator coordinator, SceneBridgeFactory bridgeFactory) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.bridgeFactory = Objects.requireNonNull(bridgeFactory, "bridgeFactory");
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getServicesManager().register(KineticsService.class, this, plugin, ServicePriority.Normal);
    }

    @Override
    public KineticsContext forPlugin(JavaPlugin owner) {
        Objects.requireNonNull(owner, "owner");
        if (closed) {
            throw new IllegalStateException("Kinetics is disabled");
        }
        if (!owner.isEnabled()) {
            throw new IllegalStateException("Owning plugin is disabled: " + owner.getName());
        }
        return contexts.computeIfAbsent(owner, key -> new KineticsContextImpl(this, key));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() instanceof JavaPlugin owner) {
            KineticsContextImpl context = contexts.remove(owner);
            if (context != null) {
                context.close();
            }
        }
    }

    List<sh.harold.kinetics.plugin.physics.JoltScene> scenes() {
        return coordinator.scenes();
    }

    void tick() {
        List<sh.harold.kinetics.plugin.physics.JoltScene> scenes = coordinator.scenes();
        bridgeFactory.tick(scenes);
        coordinator.requestStep();
    }

    void executeOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (!closed && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        } else {
            lateMainTasks.add(task);
        }
    }

    void drainLateMainTasks() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Late tasks require the Paper thread");
        Runnable task;
        while ((task = lateMainTasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable failure) {
                plugin.getLogger().severe("Kinetics shutdown callback failed: " + failure);
            }
        }
    }

    JoltRuntime runtime() {
        return runtime;
    }

    PhysicsCoordinator coordinator() {
        return coordinator;
    }

    SceneBridgeFactory bridgeFactory() {
        return bridgeFactory;
    }

    void release(JavaPlugin owner, KineticsContextImpl context) {
        contexts.remove(owner, context);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (KineticsContextImpl context : List.copyOf(contexts.values())) {
            context.close();
        }
        contexts.clear();
        Bukkit.getServicesManager().unregister(KineticsService.class, this);
    }
}
