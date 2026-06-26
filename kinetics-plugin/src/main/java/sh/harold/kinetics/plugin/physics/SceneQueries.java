package sh.harold.kinetics.plugin.physics;

import com.github.stephengold.joltjni.*;
import java.util.Optional;
import java.util.concurrent.*;
import sh.harold.kinetics.api.*;

final class SceneQueries implements AutoCloseable {
    private final JoltScene scene;
    private final BroadPhaseLayerFilter broadphase = new BroadPhaseLayerFilter();
    private final ObjectLayerFilter objects = new ObjectLayerFilter();
    private final BodyFilter bodyFilter = new BodyFilter();
    private final CollideShapeSettings collideSettings = new CollideShapeSettings();
    private final AnyHitCollideShapeCollector overlap = new AnyHitCollideShapeCollector();
    private final RayCastSettings raySettings = new RayCastSettings();
    private final AllHitCastRayCollector rayHits = new AllHitCastRayCollector();

    SceneQueries(JoltScene scene) {
        this.scene = scene;
    }

    CompletionStage<Optional<RaycastHit>> cast(RaycastQuery query) {
        CompletableFuture<Optional<RaycastHit>> future = new CompletableFuture<>();
        scene.enqueue(() -> {
            try {
                scene.complete(future, castNow(query), null);
            } catch (Throwable failure) {
                scene.complete(future, null, failure);
            }
        });
        return future;
    }

    boolean overlaps(ShapeFactory.CachedShape shape, Pose pose, int ignoredJoltId) {
        overlap.reset();
        sh.harold.kinetics.api.Vec3 centre = pose.position().add(rotate(pose.rotation(), shape.centreOfMass()));
        try (RMat44 transform = RMat44.sRotationTranslation(
                JoltScene.nativeQuat(pose.rotation()), scene.local(centre))) {
            ((NarrowPhaseQuery) scene.system.getNarrowPhaseQuery()).collideShape(
                    shape.shape(), com.github.stephengold.joltjni.Vec3.sReplicate(1f),
                    transform, collideSettings, RVec3.sZero(), overlap,
                    broadphase, objects, bodyFilter);
        }
        return overlap.hadHit();
    }

    private Optional<RaycastHit> castNow(RaycastQuery query) {
        sh.harold.kinetics.api.Vec3 offset = query.direction().multiply(query.maximumDistance());
        rayHits.reset();
        try (RRayCast ray = new RRayCast(scene.local(query.origin()), JoltScene.nativeVec(offset))) {
            ((NarrowPhaseQuery) scene.system.getNarrowPhaseQuery()).castRay(
                    ray, raySettings, rayHits, broadphase, objects, bodyFilter);
            rayHits.sort();
            for (int i = 0; i < rayHits.countHits(); i++) {
                RayCastResult result = rayHits.get(i);
                long publicId = scene.bodies.getUserData(result.getBodyId());
                JoltBody body = publicId == 0 ? null : scene.byPublicId.get(publicId);
                if (body == null && !query.includeTerrain()) continue;
                if (body != null && (query.collisionMask() & (1 << body.spec.collisionLayer())) == 0)
                    continue;
                RVec3 localPoint = ray.getPointOnRay(result.getFraction());
                com.github.stephengold.joltjni.Vec3 normal
                        = com.github.stephengold.joltjni.Vec3.sAxisY();
                try (BodyLockRead lock = new BodyLockRead(
                        scene.system.getBodyLockInterface(), result.getBodyId())) {
                    if (lock.succeeded()) normal = lock.getBody().getWorldSpaceSurfaceNormal(
                            result.getSubShapeId2(), localPoint);
                }
                ColliderRef collider = body == null
                        ? ColliderRef.Terrain.INSTANCE : new ColliderRef.Body(body.id);
                return Optional.of(new RaycastHit(collider, scene.world(localPoint),
                        JoltScene.apiVec(normal), result.getFraction() * query.maximumDistance()));
            }
            return Optional.empty();
        }
    }

    private static sh.harold.kinetics.api.Vec3 rotate(Rotation rotation, sh.harold.kinetics.api.Vec3 vector) {
        double tx = 2.0 * (rotation.y() * vector.z() - rotation.z() * vector.y());
        double ty = 2.0 * (rotation.z() * vector.x() - rotation.x() * vector.z());
        double tz = 2.0 * (rotation.x() * vector.y() - rotation.y() * vector.x());
        return new sh.harold.kinetics.api.Vec3(
                vector.x() + rotation.w() * tx + rotation.y() * tz - rotation.z() * ty,
                vector.y() + rotation.w() * ty + rotation.z() * tx - rotation.x() * tz,
                vector.z() + rotation.w() * tz + rotation.x() * ty - rotation.y() * tx);
    }

    @Override
    public void close() {
        overlap.close();
        rayHits.close();
        raySettings.close();
        collideSettings.close();
        bodyFilter.close();
        objects.close();
        broadphase.close();
    }
}
