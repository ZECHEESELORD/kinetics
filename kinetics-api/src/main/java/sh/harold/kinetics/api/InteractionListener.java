package sh.harold.kinetics.api;

@FunctionalInterface
public interface InteractionListener {
    void onInteraction(InteractionEvent event);
}
