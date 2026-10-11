package de.danoeh.antennapod.model.adskip;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AdSkipRangesTest {
    @Test
    public void boundariesAndUnionPreserveGapsAndEpisodeEnd() {
        AdSkipRanges ranges = new AdSkipRanges(List.of(new AdSkipRanges.Range(200, 300),
                new AdSkipRanges.Range(0, 100), new AdSkipRanges.Range(50, 200),
                new AdSkipRanges.Range(400, 500)), 500);
        assertEquals(300, ranges.skipTarget(0));
        assertEquals(300, ranges.skipTarget(299));
        assertEquals(-1, ranges.skipTarget(300));
        assertEquals(-1, ranges.skipTarget(399));
        assertEquals(500, ranges.skipTarget(400));
        assertEquals(-1, ranges.skipTarget(500));
        assertEquals(-1, ranges.skipTarget(600));
    }

    @Test
    public void invalidRangesAreRejectedWithoutClamping() {
        for (AdSkipRanges.Range range : List.of(new AdSkipRanges.Range(-1, 100),
                new AdSkipRanges.Range(100, 100), new AdSkipRanges.Range(200, 100),
                new AdSkipRanges.Range(0, 501))) {
            assertThrows(IllegalArgumentException.class, () -> new AdSkipRanges(List.of(range), 500));
        }
        assertThrows(IllegalArgumentException.class, () -> new AdSkipRanges(List.of(), 0));
        assertTrue(new AdSkipRanges(List.of(), 500).isEmpty());
    }
}
