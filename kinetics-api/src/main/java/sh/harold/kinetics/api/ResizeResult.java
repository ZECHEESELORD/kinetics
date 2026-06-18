package sh.harold.kinetics.api;

import java.util.Objects;

public record ResizeResult(Status status, String reason) {
    public ResizeResult {
        Objects.requireNonNull(status, "status");
        reason = reason == null ? "" : reason;
    }

    public boolean applied() {
        return status == Status.APPLIED;
    }

    public enum Status {
        APPLIED,
        BLOCKED,
        BODY_DESTROYED
    }
}
