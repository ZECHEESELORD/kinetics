package sh.harold.kinetics.plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Set<MainTask> pendingMainTasks = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Runnable> lateMainTasks = new ConcurrentLinkedQueue<>();
    private final Object lifecycleLock = new Object();
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
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("Kinetics is disabled");
            }
            if (!owner.isEnabled()) {
                throw new IllegalStateException("Owning plugin is disabled: " + owner.getName());
            }
            return contexts.compute(owner, (key, existing) -> existing == null || existing.closed()
                    ? new KineticsContextImpl(this, key) : existing);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!(event.getPlugin() instanceof JavaPlugin owner)) return;
        KineticsContextImpl context;
        synchronized (lifecycleLock) {
            context = contexts.remove(owner);
        }
        if (context != null) context.close();
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
        Objects.requireNonNull(task, "task");
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }

        MainTask scheduled = new MainTask(task);
        synchronized (lifecycleLock) {
            if (closed || !plugin.isEnabled()) {
                lateMainTasks.add(scheduled);
                return;
            }
            pendingMainTasks.add(scheduled);
        }
        try {
            Bukkit.getScheduler().runTask(plugin, scheduled);
        } catch (RuntimeException rejected) {
            plugin.getLogger().warning(
                    "Paper rejected a Kinetics main-thread callback; deferring it to shutdown: "
                            + KineticsMessages.failureDetail(rejected));
            lateMainTasks.add(scheduled);
        }
    }

    void drainLateMainTasks() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Late tasks require the Paper thread");
        Runnable task;
        while ((task = lateMainTasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable failure) {
                plugin.getLogger().severe(
                        "Kinetics shutdown callback failed: " + KineticsMessages.failureDetail(failure));
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
        synchronized (lifecycleLock) {
            contexts.remove(owner, context);
        }
    }

    @Override
    public void close() {
        List<KineticsContextImpl> closing;
        List<MainTask> pending;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            closing = List.copyOf(contexts.values());
            contexts.clear();
            pending = List.copyOf(pendingMainTasks);
        }
        lateMainTasks.addAll(pending);
        for (KineticsContextImpl context : closing) context.close();
        Bukkit.getServicesManager().unregister(KineticsService.class, this);
    }

    private final class MainTask implements Runnable {
        private final Runnable delegate;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private MainTask(Runnable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            pendingMainTasks.remove(this);
            delegate.run();
        }
    }
}