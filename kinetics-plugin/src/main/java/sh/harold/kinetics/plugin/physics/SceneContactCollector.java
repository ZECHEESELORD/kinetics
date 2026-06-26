package sh.harold.kinetics.plugin.physics;

import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.enumerate.ValidateResult;
import com.github.stephengold.joltjni.readonly.ConstBody;
import sh.harold.kinetics.api.*;

final class SceneContactCollector extends CustomContactListener {
    private final JoltScene scene;
    private final java.util.Map<PairKey, ContactMemory> activePairs = new java.util.HashMap<>();

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
        return ValidateResult.AcceptAllContactsForThisBodyPair.ordinal();
    }

    @Override
    public void onContactAdded(long firstAddress, long secondAddress,
            long manifoldAddress, long settingsAddress) {
        ConstBody first = new Body(scene.system, firstAddress);
        ConstBody second = new Body(scene.system, secondAddress);
        ContactManifold manifold = new ContactManifold(manifoldAddress);
        com.github.stephengold.joltjni.Vec3 normal = manifold.getWorldSpaceNormal();
        RVec3 point = manifold.getBaseOffset();
        com.github.stephengold.joltjni.Vec3 av = first.getLinearVelocity();
        com.github.stephengold.joltjni.Vec3 bv = second.getLinearVelocity();
        configureContact(first, second, normal, av, bv, settingsAddress);
        scene.recordContact();
        boolean wantsBegin = scene.wantsContact(ContactPhase.BEGIN);
        boolean wantsImpact = scene.wantsContact(ContactPhase.IMPACT);
        boolean wantsEnd = scene.wantsContact(ContactPhase.END);
        if (!wantsBegin && !wantsImpact && !wantsEnd) return;
        double relative = Math.abs((bv.getX() - av.getX()) * normal.getX()
                + (bv.getY() - av.getY()) * normal.getY()
                + (bv.getZ() - av.getZ()) * normal.getZ());
        sh.harold.kinetics.api.Vec3 contactPoint = scene.world(point);
        sh.harold.kinetics.api.Vec3 contactNormal = JoltScene.apiVec(normal);
        PairKey key = PairKey.of(first.getUserData(), second.getUserData());
        boolean firstContact;
        synchronized (activePairs) {
            ContactMemory previous = activePairs.get(key);
            firstContact = previous == null;
            activePairs.put(key, new ContactMemory(firstContact ? 1 : previous.count + 1,
                    contactPoint, contactNormal));
        }
        if (wantsBegin && firstContact)
            scene.dispatchContact(event(ContactPhase.BEGIN, first.getUserData(), second.getUserData(),
                    contactPoint, contactNormal, relative));
        if (wantsImpact && relative >= .25) {
            scene.dispatchContact(event(ContactPhase.IMPACT, first.getUserData(), second.getUserData(),
                    contactPoint, contactNormal, relative));
        }
    }

    @Override
    public void onContactPersisted(long firstAddress, long secondAddress,
            long manifoldAddress, long settingsAddress) {
        ConstBody first = new Body(scene.system, firstAddress);
        ConstBody second = new Body(scene.system, secondAddress);
        ContactManifold manifold = new ContactManifold(manifoldAddress);
        configureContact(first, second, manifold.getWorldSpaceNormal(),
                first.getLinearVelocity(), second.getLinearVelocity(), settingsAddress);
        scene.recordContact();
    }

    @Override
    public void onContactRemoved(long pairAddress) {
        SubShapeIdPair pair = new SubShapeIdPair(pairAddress);
        JoltBody first = scene.byJoltId.get(pair.getBody1Id());
        JoltBody second = scene.byJoltId.get(pair.getBody2Id());
        long firstId = first == null ? 0 : first.id.value();
        long secondId = second == null ? 0 : second.id.value();
        PairKey key = PairKey.of(firstId, secondId);
        ContactMemory memory;
        synchronized (activePairs) {
            memory = activePairs.get(key);
            if (memory == null) return;
            if (memory.count > 1) {
                activePairs.put(key, new ContactMemory(memory.count - 1, memory.point, memory.normal));
                return;
            }
            activePairs.remove(key);
        }
        if (scene.wantsContact(ContactPhase.END))
            scene.dispatchContact(event(ContactPhase.END, firstId, secondId,
                    memory.point, memory.normal, 0));
    }

    private void configureContact(ConstBody first, ConstBody second,
            com.github.stephengold.joltjni.Vec3 normal,
            com.github.stephengold.joltjni.Vec3 firstVelocity,
            com.github.stephengold.joltjni.Vec3 secondVelocity, long settingsAddress) {
        double dx = secondVelocity.getX() - firstVelocity.getX();
        double dy = secondVelocity.getY() - firstVelocity.getY();
        double dz = secondVelocity.getZ() - firstVelocity.getZ();
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

    private static double coefficient(JoltBody body, boolean staticContact) {
        if (body == null) return 0.7;
        return staticContact ? body.material.staticFriction() : body.material.dynamicFriction();
    }

    private ContactEvent event(ContactPhase phase, long first, long second,
            sh.harold.kinetics.api.Vec3 point, sh.harold.kinetics.api.Vec3 normal, double speed) {
        return new ContactEvent(phase, collider(first), collider(second), point, normal, 0, speed);
    }

    private ColliderRef collider(long id) {
        JoltBody body = id == 0 ? null : scene.byPublicId.get(id);
        return body == null ? ColliderRef.Terrain.INSTANCE : new ColliderRef.Body(body.id);
    }

    private record PairKey(long first, long second) {
        static PairKey of(long first, long second) {
            return first <= second ? new PairKey(first, second) : new PairKey(second, first);
        }
    }

    private record ContactMemory(int count, sh.harold.kinetics.api.Vec3 point,
            sh.harold.kinetics.api.Vec3 normal) {
    }
}
