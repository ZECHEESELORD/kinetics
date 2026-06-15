package sh.harold.kinetics.api;

/** Opaque, generation-checked body handle. */
public record BodyId(long value) {
    public BodyId {
        if (value == 0L) {
            throw new IllegalArgumentException("Body id 0 is reserved");
        }
    }

    @Override
    public String toString() {
        return Long.toUnsignedString(value, 16);
    }
}
