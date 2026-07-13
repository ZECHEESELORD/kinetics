package sh.harold.kinetics.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class KineticsMessagesTest {
    @Test
    void unwrapsUsefulFailureDetails() {
        assertEquals("disk full", KineticsMessages.failureDetail(
                new CompletionException(new IllegalStateException(" disk full "))));
        assertEquals("IllegalArgumentException",
                KineticsMessages.failureDetail(new IllegalArgumentException("  ")));
        assertEquals("unknown failure", KineticsMessages.failureDetail(null));
    }
}