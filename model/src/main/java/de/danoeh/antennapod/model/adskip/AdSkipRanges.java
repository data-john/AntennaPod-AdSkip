package de.danoeh.antennapod.model.adskip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Collections;

public final class AdSkipRanges {
    public record Range(long startMs, long endMs) {
    }

    private final List<Range> ranges;

    public AdSkipRanges(List<Range> input, long durationMs) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("Invalid duration");
        }
        List<Range> sorted = new ArrayList<>(input);
        for (Range range : sorted) {
            if (range.startMs() < 0 || range.startMs() >= range.endMs() || range.endMs() > durationMs) {
                throw new IllegalArgumentException("Invalid range");
            }
        }
        sorted.sort(Comparator.comparingLong(Range::startMs));
        List<Range> merged = new ArrayList<>();
        for (Range range : sorted) {
            if (!merged.isEmpty() && range.startMs() <= merged.get(merged.size() - 1).endMs()) {
                Range previous = merged.remove(merged.size() - 1);
                merged.add(new Range(previous.startMs(), Math.max(previous.endMs(), range.endMs())));
            } else {
                merged.add(range);
            }
        }
        ranges = Collections.unmodifiableList(merged);
    }

    public long skipTarget(long positionMs) {
        for (Range range : ranges) {
            if (range.startMs() <= positionMs && positionMs < range.endMs()) {
                return range.endMs();
            }
        }
        return -1;
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }
}
