package sh.harold.kinetics.plugin.terrain;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.PriorityQueue;

/** Coordinates asynchronous terrain rebuild stages and rejects stale results. */
public final class DirtySectionCoordinator<K> {
    public static final int DEFAULT_MAX_PENDING = 4_096;

    private final int maxPending;
    private final Map<K, Status> states = new HashMap<>();
    private final Map<K, Queued<K>> pending = new HashMap<>();
    private final PriorityQueue<Queued<K>> queue = new PriorityQueue<>(
            Comparator.<Queued<K>>comparingInt(Queued::priority).reversed()
                    .thenComparingLong(Queued::sequence));

    private long nextRevision;
    private long nextSequence;
    private boolean rescanRequired;
    private long rescanRevision;

    public DirtySectionCoordinator() {
        this(DEFAULT_MAX_PENDING);
    }

    public DirtySectionCoordinator(int maxPending) {
        if (maxPending < 1) {
            throw new IllegalArgumentException("maxPending must be greater than zero");
        }
        this.maxPending = maxPending;
    }

    /** Marks a section dirty. Larger priorities are captured first. */
    public synchronized long markDirty(K section, int priority) {
        Objects.requireNonNull(section, "section");
        long revision = nextRevision();
        if (rescanRequired) {
            rescanRevision = revision;
            return revision;
        }

        states.put(section, new Status(revision, Stage.DIRTY));
        Queued<K> existing = pending.get(section);
        if (existing != null) {
            // Keep the earlier queue position unless urgency increased; the state carries the newest revision.
            if (priority > existing.priority()) {
                queue.remove(existing);
                Queued<K> replacement = new Queued<>(section, priority, existing.sequence());
                pending.put(section, replacement);
                queue.add(replacement);
            }
            return revision;
        }

        if (pending.size() >= maxPending) {
            requireRescan(revision);
            return revision;
        }
        Queued<K> entry = new Queued<>(section, priority, nextSequence++);
        pending.put(section, entry);
        queue.add(entry);
        return revision;
    }

    public synchronized Optional<Work<K>> pollDirty() {
        if (rescanRequired) {
            return Optional.empty();
        }
        Queued<K> entry = queue.poll();
        if (entry == null) {
            return Optional.empty();
        }
        pending.remove(entry.section());
        Status status = states.get(entry.section());
        return Optional.of(new Work<>(entry.section(), status.revision(), entry.priority()));
    }

    public synchronized boolean markCaptured(K section, long revision) {
        return advance(section, revision, Stage.DIRTY, Stage.CAPTURED);
    }

    public synchronized boolean markBuilt(K section, long revision) {
        return advance(section, revision, Stage.CAPTURED, Stage.BUILT);
    }

    public synchronized boolean markActive(K section, long revision) {
        return advance(section, revision, Stage.BUILT, Stage.ACTIVE);
    }

    /** Requeues a failed capture without changing its revision. */
    public synchronized boolean retry(K section, long revision, int priority) {
        if (rescanRequired || pending.containsKey(section)) {
            return false;
        }
        Status status = states.get(section);
        if (status == null || status.revision() != revision || status.stage() != Stage.DIRTY) {
            return false;
        }
        if (pending.size() >= maxPending) {
            requireRescan(revision);
            return false;
        }
        Queued<K> entry = new Queued<>(section, priority, nextSequence++);
        pending.put(section, entry);
        queue.add(entry);
        return true;
    }

    public synchronized Optional<Status> status(K section) {
        return Optional.ofNullable(states.get(Objects.requireNonNull(section, "section")));
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    /** Revision token for a required full rescan, if queue capacity was exceeded. */
    public synchronized OptionalLong rescanRevision() {
        return rescanRequired ? OptionalLong.of(rescanRevision) : OptionalLong.empty();
    }

    /**
     * Clears the marker only when no invalidation occurred after the caller began its rescan.
     */
    public synchronized boolean completeRescan(long revision) {
        if (!rescanRequired || rescanRevision != revision) {
            return false;
        }
        rescanRequired = false;
        states.clear();
        return true;
    }

    private boolean advance(K section, long revision, Stage expected, Stage next) {
        Status current = states.get(Objects.requireNonNull(section, "section"));
        if (rescanRequired || current == null || current.revision() != revision || current.stage() != expected) {
            return false;
        }
        states.put(section, new Status(revision, next));
        return true;
    }

    private long nextRevision() {
        if (nextRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("Terrain revision counter exhausted");
        }
        return ++nextRevision;
    }

    private void requireRescan(long revision) {
        rescanRequired = true;
        rescanRevision = revision;
        queue.clear();
        pending.clear();
        states.clear();
    }

    public enum Stage {
        DIRTY,
        CAPTURED,
        BUILT,
        ACTIVE
    }

    public record Work<K>(K section, long revision, int priority) {
    }

    public record Status(long revision, Stage stage) {
        public Status {
            Objects.requireNonNull(stage, "stage");
        }
    }

    private record Queued<K>(K section, int priority, long sequence) {
    }
}
