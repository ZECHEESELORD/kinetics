package sh.harold.kinetics.plugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DisplayLodTest {
    @Test
    void appliesDistanceBandsAndHysteresis() {
        assertEquals(DisplayLod.NEAR, DisplayLod.select(32 * 32, null));
        assertEquals(DisplayLod.MID, DisplayLod.select(33 * 33, null));
        assertEquals(DisplayLod.NEAR, DisplayLod.select(36 * 36, DisplayLod.NEAR));
        assertEquals(DisplayLod.MID, DisplayLod.select(37 * 37, DisplayLod.NEAR));
        assertEquals(DisplayLod.FAR, DisplayLod.select(60 * 60, DisplayLod.FAR));
        assertEquals(DisplayLod.MID, DisplayLod.select(59 * 59, DisplayLod.FAR));
        assertThrows(IllegalArgumentException.class,
                () -> DisplayLod.select(Double.NaN, DisplayLod.MID));
    }

    @Test
    void staggersPublicationAtEachCadence() {
        assertTrue(DisplayLod.NEAR.shouldPublish(1, 7));
        assertTrue(DisplayLod.MID.shouldPublish(1, 7));
        assertFalse(DisplayLod.MID.shouldPublish(2, 7));
        assertTrue(DisplayLod.FAR.shouldPublish(1, 7));
        assertFalse(DisplayLod.FAR.shouldPublish(2, 7));
    }
}