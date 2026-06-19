package sh.harold.kinetics.api;

import java.util.Objects;

@FunctionalInterface
public interface ContactFilter {
    boolean test(ContactEvent event);

    static ContactFilter any() {
        return ignored -> true;
    }

    static ContactFilter involving(BodyId id) {
        Objects.requireNonNull(id, "id");
        return event -> isBody(event.first(), id) || isBody(event.second(), id);
    }

    default ContactFilter and(ContactFilter other) {
        Objects.requireNonNull(other, "other");
        return event -> test(event) && other.test(event);
    }

    private static boolean isBody(ColliderRef collider, BodyId id) {
        return collider instanceof ColliderRef.Body body && body.id().equals(id);
    }
}
