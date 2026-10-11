package de.danoeh.antennapod.event.adskip;

public class AdSkipEvent {
    public final long mediaId;

    public AdSkipEvent(long mediaId) {
        this.mediaId = mediaId;
    }
}
