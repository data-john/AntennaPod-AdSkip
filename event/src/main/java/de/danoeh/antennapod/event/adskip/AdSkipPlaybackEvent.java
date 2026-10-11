package de.danoeh.antennapod.event.adskip;

public class AdSkipPlaybackEvent {
    public final long mediaId;
    public final boolean canSkip;

    public AdSkipPlaybackEvent(long mediaId, boolean canSkip) {
        this.mediaId = mediaId;
        this.canSkip = canSkip;
    }
}
