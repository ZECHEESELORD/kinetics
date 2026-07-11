package sh.harold.kinetics.plugin.physics;

import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.enumerate.ValidateResult;
import com.github.stephengold.joltjni.readonly.ConstBody;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import sh.harold.kinetics.api.*;
import sh.harold.kinetics.api.Vec3;

final class SceneContactCollector extends CustomContactListener {
    private final JoltScene scene;
    private final Map<PairKey, ArrayDeque<Vec3>> validationPoints = new HashMap<>();
    private final Map<PairKey, ContactMemory> activePairs = new HashMap<>();

    SceneContactCollector(JoltScene scene) {
        this.scene = scene;
    }

    @Override
    public int onContactValidate(long firstAddress, long secondAddress,
            double x, double y, double z, long collisionAddress) {
        ConstBody first = new Body(scene.system, firstAddress);
        ConstBody second = new Body(scene.system, secondAddress);
        JoltBody a = scene.byPublicId.get(first.getUserData());
        JoltBody b = scene.byPublicId.get(second.getUserData());
        if (a != null && b != null) {
            boolean allowed = (a.spec.collisionMask() & (1 << b.spec.collisionLayer())) != 0
                    && (b.spec.collisionMask() & (1 << a.spec.collisionLayer())) != 0;
            if (!allowed) return ValidateResult.RejectAllContactsForThisBodyPair.ordinal();
        } else {
            JoltBody body = a == null ? b : a;
            if (body != null && (body.spec.collisionMask() & 1) == 0)
                return ValidateResult.RejectAllContactsForThisBodyPair.ordinal();
        }

        CollideShapeResult collision = new CollideShapeResult(collisionAddress);
        com.github.stephengold.joltjni.Vec3 firstPoint = collision.getContactPointOn1();
        com.github.stephengold.joltjni.Vec3 secondPoint = collision.getContactPointOn2();
        Vec3 worldPoint = new Vec3(
                x + midpoint(firstPoint.getX(), secondPoint.getX()) + scene.origin.x(),
                y + midpoint(firstPoint.getY(), secondPoint.getY()) + scene.origin.y(),
                z + midpoint(firstPoint.getZ(), secondPoint.getZ()) + scene.origin.z());
        synchronized (validationPoints) {
            validationPoints.computeIfAbsent(PairKey.of(first.getId(), second.getId()),
                    ignored -> new ArrayDeque<>()).addLast(worldPoint);
        }
        return ValidateResult.AcceptAllContactsForThisBodyPair.ordinal();
    }

    @Override
    public void onContactAdded(long firstAddress, long secondAddress,
            long manifoldAddress, long settingsAddress) {
        ConstBody first = new Body(scene.system, firstAddress);
        ConstBody second = new Body(scene.system, secondAddress);
        ContactManifold manifold = new ContactManifold(manifoldAddress);
        com.github.stephengold.joltjni.Vec3 normal = manifold.getWorldSpaceNormal();
        PairKey key = PairKey.of(first.getId(), second.getId());
        Vec3 contactPoint;
        synchronized (validationPoints) {
            ArrayDeque<Vec3> points = validationPoints.get(key);
            contactPoint = points == null ? null : points.pollFirst();
            if (points != null && points.isEmpty()) validationPoints.remove(key);
        }
        if (contactPoint == null) {
            synchronized (activePairs) {
                ContactMemory previous = activePairs.get(key);
                contactPoint = previous == null ? null : previous.point;
            }
        }
        if (contactPoint == null) contactPoint = scene.world(manifold.getBaseOffset());
        VelocityPair velocities = surfaceVelocities(first, second, contactPoint);
        configureContact(first, second, normal, velocities.first, velocities.second, settingsAddress);
        scene.recordContact();
        Vec3 contactNormal = JoltScene.apiVec(normal);
        ContactMemory memory;
        boolean firstContact;
        synchronized (activePairs) {
            ContactMemory previous = activePairs.get(key);
            firstContact = previous == null;
            memory = previous == null
                    ? new ContactMemory(1, collider(first.getUserData()), collider(second.getUserData()),
                            contactPoint, contactNormal)
                    : new ContactMemory(previous.count + 1, previous.first, previous.second,
                            contactPoint, contactNormal);
            activePairs.put(key, memory);
        }
        boolean wantsBegin = scene.wantsContact(ContactPhase.BEGIN);
        boolean wantsImpact = scene.wantsContact(ContactPhase.IMPACT);
        if (!wantsBegin && !wantsImpact) return;

        double relative = closingSpeed(velocities, normal);
        if (wantsBegin && firstContact)
            scene.dispatchContact(event(ContactPhase.BEGIN, memory.first, memory.second,
                    contactPoint, contactNormal, relative));
        if (wantsImpact && relative >= .25)
            scene.dispatchContact(event(ContactPhase.IMPACT, memory.first, memory.second,
                    contactPoint, contactNormal, relative));
    }

    @Override
    public void onContactPersisted(long firstAddress, long secondAddress,
            long manifoldAddress, long settingsAddress) {
        ConstBody first = new Body(scene.system, firstAddress);
        ConstBody second = new Body(scene.system, secondAddress);
        ContactManifold manifold = new ContactManifold(manifoldAddress);
        PairKey key = PairKey.of(first.getId(), second.getId());
        Vec3 point;
        synchronized (activePairs) {
            ContactMemory memory = activePairs.get(key);
            point = memory == null ? null : memory.point;
        }
        if (point == null) point = scene.world(manifold.getBaseOffset());
        VelocityPair velocities = surfaceVelocities(first, second, point);
        configureContact(first, second, manifold.getWorldSpaceNormal(),
                velocities.first, velocities.second, settingsAddress);
        scene.recordContact();
    }

    @Override
    public void onContactRemoved(long pairAddress) {
        SubShapeIdPair pair = new SubShapeIdPair(pairAddress);
        PairKey key = PairKey.of(pair.getBody1Id(), pair.getBody2Id());
        synchronized (validationPoints) {
            validationPoints.remove(key);
        }
        ContactMemory memory;
        synchronized (activePairs) {
            memory = activePairs.get(key);
            if (memory == null) return;
            if (memory.count > 1) {
                activePairs.put(key, new ContactMemory(memory.count - 1, memory.first, memory.second,
                        memory.point, memory.normal));
                return;
            }
            activePairs.remove(key);
        }
        if (scene.wantsContact(ContactPhase.END))
            scene.dispatchContact(event(ContactPhase.END, memory.first, memory.second,
                    memory.point, memory.normal, 0));
    }

    private void configureContact(ConstBody first, ConstBody second,
            com.github.stephengold.joltjni.Vec3 normal, Vec3 firstVelocity,
            Vec3 secondVelocity, long settingsAddress) {
        double dx = secondVelocity.x() - firstVelocity.x();
        double dy = secondVelocity.y() - firstVelocity.y();
        double dz = secondVelocity.z() - firstVelocity.z();
        double normalSpeed = dx * normal.getX() + dy * normal.getY() + dz * normal.getZ();
        double tx = dx - normalSpeed * normal.getX();
        double ty = dy - normalSpeed * normal.getY();
        double tz = dz - normalSpeed * normal.getZ();
        boolean staticContact = Math.sqrt(tx * tx + ty * ty + tz * tz) < 0.05;
        JoltBody a = scene.byPublicId.get(first.getUserData());
        JoltBody b = scene.byPublicId.get(second.getUserData());
        double firstFriction = coefficient(a, staticContact);
        double secondFriction = coefficient(b, staticContact);
        double firstRestitution = a == null ? 0.02 : a.material.restitution();
        double secondRestitution = b == null ? 0.02 : b.material.restitution();
        ContactSettings settings = new ContactSettings(settingsAddress);
        settings.setCombinedFriction(JoltScene.nativeFloat(
                Math.sqrt(firstFriction) * Math.sqrt(secondFriction), "combinedFriction"));
        settings.setCombinedRestitution((float) Math.sqrt(firstRestitution * secondRestitution));
    }

    private VelocityPair surfaceVelocities(ConstBody first, ConstBody second, Vec3 point) {
        return new VelocityPair(surfaceVelocity(first, point), surfaceVelocity(second, point));
    }

    private Vec3 surfaceVelocity(ConstBody body, Vec3 point) {
        com.github.stephengold.joltjni.Vec3 linear = body.getLinearVelocity();
        com.github.stephengold.joltjni.Vec3 angular = body.getAngularVelocity();
        RVec3 centre = body.getCenterOfMassPosition();
        double rx = point.x() - scene.origin.x() - centre.xx();
        double ry = point.y() - scene.origin.y() - centre.yy();
        double rz = point.z() - scene.origin.z() - centre.zz();
        return new Vec3(
                linear.getX() + angular.getY() * rz - angular.getZ() * ry,
                linear.getY() + angular.getZ() * rx - angular.getX() * rz,
                linear.getZ() + angular.getX() * ry - angular.getY() * rx);
    }

    private static double closingSpeed(VelocityPair velocities,
            com.github.stephengold.joltjni.Vec3 normal) {
        double relative = (velocities.second.x() - velocities.first.x()) * normal.getX()
                + (velocities.second.y() - velocities.first.y()) * normal.getY()
                + (velocities.second.z() - velocities.first.z()) * normal.getZ();
        return Math.max(0.0, -relative);
    }

    private static double coefficient(JoltBody body, boolean staticContact) {
        if (body == null) return 0.7;
        return staticContact ? body.material.staticFriction() : body.material.dynamicFriction();
    }

    private static ContactEvent event(ContactPhase phase, ColliderRef first, ColliderRef second,
            Vec3 point, Vec3 normal, double speed) {
        return new ContactEvent(phase, first, second, point, normal, 0, speed);
    }

    private ColliderRef collider(long id) {
        JoltBody body = id == 0 ? null : scene.byPublicId.get(id);
        return body == null ? ColliderRef.Terrain.INSTANCE : new ColliderRef.Body(body.id);
    }

    private static double midpoint(double first, double second) {
        return first * 0.5 + second * 0.5;
    }

    private record PairKey(int first, int second) {
        static PairKey of(int first, int second) {
            return Integer.compareUnsigned(first, second) <= 0
                    ? new PairKey(first, second) : new PairKey(second, first);
        }
    }

    private record VelocityPair(Vec3 first, Vec3 second) {
    }

    private record ContactMemory(int count, ColliderRef first, ColliderRef second,
            Vec3 point, Vec3 normal) {
    }
}