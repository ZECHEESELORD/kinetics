package sh.harold.kinetics.api;

public interface Subscription extends AutoCloseable {
    boolean active();

    @Override
    void close();
}
