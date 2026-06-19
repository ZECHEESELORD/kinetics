package sh.harold.kinetics.api;

import java.util.Objects;

@FunctionalInterface
public interface InteractionFilter {
    boolean test(InteractionEvent event);

    static InteractionFilter any() {
        return ignored -> true;
    }

    static InteractionFilter body(BodyId id) {
        Objects.requireNonNull(id, "id");
        return event -> event.body().equals(id);
    }
}
