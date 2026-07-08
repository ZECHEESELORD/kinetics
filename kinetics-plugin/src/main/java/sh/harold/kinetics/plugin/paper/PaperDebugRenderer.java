package sh.harold.kinetics.plugin.paper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import sh.harold.kinetics.api.ColliderRef;
import sh.harold.kinetics.api.PhysicsShape;
import sh.harold.kinetics.api.Pose;
import sh.harold.kinetics.api.RaycastHit;
import sh.harold.kinetics.api.RaycastQuery;
import sh.harold.kinetics.api.Rotation;
import sh.harold.kinetics.api.Vec3;
import sh.harold.kinetics.plugin.physics.JoltScene;

/** One-shot, player-local collider diagnostics. */
public final class PaperDebugRenderer {
    private static final double REACH = 32.0;
    private static final int RING_STEPS = 24;
    private static final int MAX_COLLIDER_PARTICLES = 1_200;
    private static final Particle.DustOptions COLLIDER_DUST
            = new Particle.DustOptions(Color.fromRGB(65, 235, 155), 0.7f);
    private static final Particle.DustOptions CENTRE_DUST
            = new Particle.DustOptions(Color.fromRGB(255, 75, 75), 1.5f);
    private static final Particle.DustOptions VELOCITY_DUST
            = new Particle.DustOptions(Color.fromRGB(255, 215, 60), 0.9f);

    /**
     * Selects the nearest visible physics body under the player's crosshair and draws it once.
     * The returned value is false when no body was selected or the player left before rendering.
     */
    public CompletionStage<Boolean> renderSelected(
            Player player, Collection<JoltScene> activeScenes) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(activeScenes, "activeScenes");
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Debug rendering must start on the Paper thread");
        }
        if (!player.isOnline()) return CompletableFuture.completedFuture(false);

        Location eye = player.getEyeLocation();
        Vec3 origin = Vec3.of(eye);
        Vec3 direction = Vec3.of(eye.getDirection());
        List<PendingHit> pending = new ArrayList<>();
        for (JoltScene scene : activeScenes) {
            if (scene.closed() || scene.world() != player.getWorld()) continue;
            try {
                CompletableFuture<Optional<RaycastHit>> future = scene
                        .raycast(RaycastQuery.all(origin, direction, REACH))
                        .toCompletableFuture()
                        .exceptionally(ignored -> Optional.empty());
                pending.add(new PendingHit(scene, future));
            } catch (IllegalStateException ignored) {
                // A scene may close between the active check and command enqueue.
            }
        }
        if (pending.isEmpty()) return CompletableFuture.completedFuture(false);

        CompletableFuture<?>[] futures = pending.stream().map(PendingHit::future)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures).thenApply(ignored -> {
            if (!player.isOnline() || player.getWorld() != eye.getWorld()) return false;
            PendingResult winner = null;
            for (PendingHit candidate : pending) {
                Optional<RaycastHit> result = candidate.future.getNow(Optional.empty());
                if (result.isEmpty()
                        || !(result.get().collider() instanceof ColliderRef.Body body)) continue;
                if (winner == null || result.get().distance() < winner.hit.distance()) {
                    winner = new PendingResult(candidate.scene, body, result.get());
                }
            }
            if (winner == null || winner.scene.closed()) return false;
            Optional<JoltScene.DebugBodySnapshot> snapshot
                    = winner.scene.debugSnapshot(winner.body.id());
            if (snapshot.isEmpty()) return false;
            draw(player, snapshot.get());
            return true;
        });
    }

    private static void draw(Player player, JoltScene.DebugBodySnapshot snapshot) {
        List<Segment> collider = new ArrayList<>();
        addShape(snapshot.shape(), Pose.IDENTITY, snapshot, collider);
        drawSegments(player, collider, COLLIDER_DUST, 0.15, MAX_COLLIDER_PARTICLES);

        spawnDust(player, snapshot.centreOfMass(), CENTRE_DUST);
        Vec3 velocity = snapshot.linearVelocity().multiply(0.25);
        double length = velocity.length();
        if (length > 0.01) {
            if (length > 8.0) velocity = velocity.multiply(8.0 / length);
            drawSegments(player, List.of(new Segment(snapshot.centreOfMass(),
                    snapshot.centreOfMass().add(velocity))), VELOCITY_DUST, 0.12, 80);
        }
    }

    private static void addShape(PhysicsShape shape, Pose localPose,
            JoltScene.DebugBodySnapshot snapshot, List<Segment> output) {
        switch (shape) {
            case PhysicsShape.Box box -> addBox(box, localPose, snapshot, output);
            case PhysicsShape.Sphere sphere -> {
                addCircle(localPose, snapshot, output, Plane.XY, 0.0, sphere.radius());
                addCircle(localPose, snapshot, output, Plane.XZ, 0.0, sphere.radius());
                addCircle(localPose, snapshot, output, Plane.YZ, 0.0, sphere.radius());
            }
            case PhysicsShape.Capsule capsule -> addCapsule(capsule, localPose, snapshot, output);
            case PhysicsShape.Cylinder cylinder -> addCylinder(cylinder, localPose, snapshot, output);
            case PhysicsShape.ConvexHull hull -> addHull(hull, localPose, snapshot, output);
            case PhysicsShape.Compound compound -> {
                for (PhysicsShape.Child child : compound.children()) {
                    addShape(child.shape(), combine(localPose, child.pose()), snapshot, output);
                }
            }
        }
    }

    private static void addBox(PhysicsShape.Box box, Pose localPose,
            JoltScene.DebugBodySnapshot snapshot, List<Segment> output) {
        Vec3 half = box.dimensions().multiply(0.5);
        Vec3[] corners = new Vec3[8];
        for (int bits = 0; bits < corners.length; bits++) {
            corners[bits] = new Vec3((bits & 1) == 0 ? -half.x() : half.x(),
                    (bits & 2) == 0 ? -half.y() : half.y(),
                    (bits & 4) == 0 ? -half.z() : half.z());
        }
        for (int bits = 0; bits < corners.length; bits++) {
            for (int axis = 0; axis < 3; axis++) {
                int mask = 1 << axis;
                if ((bits & mask) == 0) {
                    addLocal(output, corners[bits], corners[bits | mask], localPose, snapshot);
                }
            }
        }
    }

    private static void addCylinder(PhysicsShape.Cylinder cylinder, Pose localPose,
            JoltScene.DebugBodySnapshot snapshot, List<Segment> output) {
        double halfHeight = cylinder.height() * 0.5;
        addCircle(localPose, snapshot, output, Plane.XZ, halfHeight, cylinder.radius());
        addCircle(localPose, snapshot, output, Plane.XZ, -halfHeight, cylinder.radius());
        double radius = cylinder.radius();
        addLocal(output, new Vec3(radius, -halfHeight, 0), new Vec3(radius, halfHeight, 0), localPose, snapshot);
        addLocal(output, new Vec3(-radius, -halfHeight, 0), new Vec3(-radius, halfHeight, 0), localPose, snapshot);
        addLocal(output, new Vec3(0, -halfHeight, radius), new Vec3(0, halfHeight, radius), localPose, snapshot);
        addLocal(output, new Vec3(0, -halfHeight, -radius), new Vec3(0, halfHeight, -radius), localPose, snapshot);
    }

    private static void addCapsule(PhysicsShape.Capsule capsule, Pose localPose,
            JoltScene.DebugBodySnapshot snapshot, List<Segment> output) {
        double halfHeight = capsule.cylindricalHeight() * 0.5;
        double radius = capsule.radius();
        addCircle(localPose, snapshot, output, Plane.XZ, halfHeight, radius);
        addCircle(localPose, snapshot, output, Plane.XZ, -halfHeight, radius);
        addCapsuleOutline(localPose, snapshot, output, radius, halfHeight, false);
        addCapsuleOutline(localPose, snapshot, output, radius, halfHeight, true);
    }

    private static void addCapsuleOutline(Pose localPose, JoltScene.DebugBodySnapshot snapshot,
            List<Segment> output, double radius, double halfHeight, boolean useZ) {
        List<Vec3> points = new ArrayList<>(RING_STEPS + 4);
        int halfSteps = RING_STEPS / 2;
        for (int i = 0; i <= halfSteps; i++) {
            double angle = Math.PI * i / halfSteps;
            double radial = Math.cos(angle) * radius;
            points.add(useZ ? new Vec3(0, halfHeight + Math.sin(angle) * radius, radial)
                    : new Vec3(radial, halfHeight + Math.sin(angle) * radius, 0));
        }
        for (int i = 0; i <= halfSteps; i++) {
            double angle = Math.PI + Math.PI * i / halfSteps;
            double radial = Math.cos(angle) * radius;
            points.add(useZ ? new Vec3(0, -halfHeight + Math.sin(angle) * radius, radial)
                    : new Vec3(radial, -halfHeight + Math.sin(angle) * radius, 0));
        }
        addPolyline(points, true, localPose, snapshot, output);
    }

    private static void addHull(PhysicsShape.ConvexHull hull, Pose localPose,
            JoltScene.DebugBodySnapshot snapshot, List<Segment> output) {
        List<Vec3> all = hull.points();
        int stride = Math.max(1, (all.size() + 127) / 128);
        List<Vec3> points = new ArrayList<>((all.size() + stride - 1) / stride);
        for (int i = 0; i < all.size(); i += stride) points.add(all.get(i));

        // ponytail: nearest-neighbour edges are diagnostic; native hull topology can replace this if needed.
        Set<Long> edges = new HashSet<>();
        for (int i = 0; i < points.size(); i++) {
            int[] nearest = {-1, -1, -1};
            double[] distance = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY};
            for (int j = 0; j < points.size(); j++) {
                if (i == j) continue;
                double candidate = points.get(i).subtract(points.get(j)).lengthSquared();
                for (int slot = 0; slot < nearest.length; slot++) {
                    if (candidate >= distance[slot]) continue;
                    for (int shift = nearest.length - 1; shift > slot; shift--) {
                        distance[shift] = distance[shift - 1];
                        nearest[shift] = nearest[shift - 1];
                    }
                    distance[slot] = candidate;
                    nearest[slot] = j;
                    break;
                }
            }
            for (int neighbour : nearest) {
                if (neighbour < 0) continue;
                int low = Math.min(i, neighbour), high = Math.max(i, neighbour);
                long key = ((long) low << 32) | (high & 0xffff_ffffL);
                if (edges.add(key)) {
                    addLocal(output, points.get(low), points.get(high), localPose, snapshot);
                }
            }
        }
    }

    private static void addCircle(Pose localPose, JoltScene.DebugBodySnapshot snapshot,
            List<Segment> output, Plane plane, double offset, double radius) {
        List<Vec3> points = new ArrayList<>(RING_STEPS);
        for (int i = 0; i < RING_STEPS; i++) {
            double angle = Math.PI * 2.0 * i / RING_STEPS;
            double a = Math.cos(angle) * radius;
            double b = Math.sin(angle) * radius;
            points.add(switch (plane) {
                case XY -> new Vec3(a, b, offset);
                case XZ -> new Vec3(a, offset, b);
                case YZ -> new Vec3(offset, a, b);
            });
        }
        addPolyline(points, true, localPose, snapshot, output);
    }

    private static void addPolyline(List<Vec3> points, boolean closed, Pose localPose,
            JoltScene.DebugBodySnapshot snapshot, List<Segment> output) {
        for (int i = 1; i < points.size(); i++) {
            addLocal(output, points.get(i - 1), points.get(i), localPose, snapshot);
        }
        if (closed && points.size() > 1) {
            addLocal(output, points.getLast(), points.getFirst(), localPose, snapshot);
        }
    }

    private static void addLocal(List<Segment> output, Vec3 start, Vec3 end,
            Pose localPose, JoltScene.DebugBodySnapshot snapshot) {
        output.add(new Segment(worldPoint(start, localPose, snapshot),
                worldPoint(end, localPose, snapshot)));
    }

    private static Vec3 worldPoint(Vec3 point, Pose localPose,
            JoltScene.DebugBodySnapshot snapshot) {
        Vec3 root = localPose.position().add(rotate(localPose.rotation(), point));
        Vec3 scaled = multiply(root, snapshot.physicalScale());
        return snapshot.pose().position().add(rotate(snapshot.pose().rotation(), scaled));
    }

    private static Pose combine(Pose parent, Pose child) {
        return new Pose(parent.position().add(rotate(parent.rotation(), child.position())),
                multiply(parent.rotation(), child.rotation()));
    }

    private static Rotation multiply(Rotation first, Rotation second) {
        return new Rotation(
                first.w() * second.x() + first.x() * second.w()
                        + first.y() * second.z() - first.z() * second.y(),
                first.w() * second.y() - first.x() * second.z()
                        + first.y() * second.w() + first.z() * second.x(),
                first.w() * second.z() + first.x() * second.y()
                        - first.y() * second.x() + first.z() * second.w(),
                first.w() * second.w() - first.x() * second.x()
                        - first.y() * second.y() - first.z() * second.z());
    }

    private static Vec3 multiply(Vec3 first, Vec3 second) {
        return new Vec3(first.x() * second.x(), first.y() * second.y(), first.z() * second.z());
    }

    private static Vec3 rotate(Rotation rotation, Vec3 vector) {
        double tx = 2.0 * (rotation.y() * vector.z() - rotation.z() * vector.y());
        double ty = 2.0 * (rotation.z() * vector.x() - rotation.x() * vector.z());
        double tz = 2.0 * (rotation.x() * vector.y() - rotation.y() * vector.x());
        return new Vec3(
                vector.x() + rotation.w() * tx + rotation.y() * tz - rotation.z() * ty,
                vector.y() + rotation.w() * ty + rotation.z() * tx - rotation.x() * tz,
                vector.z() + rotation.w() * tz + rotation.x() * ty - rotation.y() * tx);
    }

    private static void drawSegments(Player player, List<Segment> segments,
            Particle.DustOptions dust, double minimumSpacing, int maximumParticles) {
        double totalLength = 0.0;
        for (Segment segment : segments) totalLength += segment.length();
        double spacing = Math.max(minimumSpacing, totalLength / Math.max(1, maximumParticles));
        int remaining = maximumParticles;
        for (Segment segment : segments) {
            if (remaining <= 0) break;
            int samples = Math.max(1, (int) Math.ceil(segment.length() / spacing));
            for (int i = 0; i <= samples && remaining > 0; i++, remaining--) {
                double ratio = (double) i / samples;
                spawnDust(player, segment.point(ratio), dust);
            }
        }
    }

    private static void spawnDust(Player player, Vec3 point, Particle.DustOptions dust) {
        player.spawnParticle(Particle.DUST, point.x(), point.y(), point.z(),
                1, 0.0, 0.0, 0.0, 0.0, dust);
    }

    private enum Plane { XY, XZ, YZ }

    private record Segment(Vec3 start, Vec3 end) {
        double length() {
            return end.subtract(start).length();
        }

        Vec3 point(double ratio) {
            return start.add(end.subtract(start).multiply(ratio));
        }
    }

    private record PendingHit(JoltScene scene,
            CompletableFuture<Optional<RaycastHit>> future) {
    }

    private record PendingResult(JoltScene scene, ColliderRef.Body body, RaycastHit hit) {
    }
}