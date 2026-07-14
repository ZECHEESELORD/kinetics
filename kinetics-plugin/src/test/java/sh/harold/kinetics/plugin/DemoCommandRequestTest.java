package sh.harold.kinetics.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DemoCommandRequestTest {
    @Test
    void parsesModesWithDefaultOrExplicitCoordinates() {
        DemoCommandRequest defaults = DemoCommandRequest.parse(new String[] {"sampler"});
        DemoCommandRequest explicit = DemoCommandRequest.parse(
                new String[] {"spectacle", "12.5", "80", "-4.25"});

        assertEquals(DemoCommandRequest.Action.SAMPLER, defaults.action());
        assertTrue(defaults.coordinates().isEmpty());
        assertEquals(DemoCommandRequest.Action.SPECTACLE, explicit.action());
        assertEquals(new DemoCommandRequest.Coordinates(12.5, 80.0, -4.25),
                explicit.coordinates().orElseThrow());
    }

    @Test
    void acceptsLifecycleActionsWithoutCoordinates() {
        assertEquals(DemoCommandRequest.Action.RESET,
                DemoCommandRequest.parse(new String[] {"reset"}).action());
        assertEquals(DemoCommandRequest.Action.STOP,
                DemoCommandRequest.parse(new String[] {"stop"}).action());
    }

    @Test
    void rejectsMalformedOrNonFiniteCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> DemoCommandRequest.parse(new String[] {"sampler", "1", "2"}));
        assertThrows(IllegalArgumentException.class,
                () -> DemoCommandRequest.parse(new String[] {"sampler", "NaN", "2", "3"}));
        assertThrows(IllegalArgumentException.class,
                () -> DemoCommandRequest.parse(new String[] {"stop", "1", "2", "3"}));
    }
}
