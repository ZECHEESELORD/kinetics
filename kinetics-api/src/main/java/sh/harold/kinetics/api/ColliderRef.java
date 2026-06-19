package sh.harold.kinetics.api;

import java.util.Objects;

public sealed interface ColliderRef permits ColliderRef.Body, ColliderRef.Terrain {
    record Body(BodyId id) implements ColliderRef {
        public Body {
            Objects.requireNonNull(id, "id");
        }
    }

    enum Terrain implements ColliderRef {
        INSTANCE
    }
}
