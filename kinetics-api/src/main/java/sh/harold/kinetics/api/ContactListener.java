package sh.harold.kinetics.api;

@FunctionalInterface
public interface ContactListener {
    void onContact(ContactEvent event);
}
