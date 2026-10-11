package de.danoeh.antennapod.playback.service.adskip;

import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import de.danoeh.antennapod.model.adskip.AdSkipRanges;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.service.Media3PlaybackService;
import de.danoeh.antennapod.storage.preferences.AdSkipPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
public class AdSkipSeekingTest {
    private Media3PlaybackService service;
    private Player player;
    private MockedStatic<AdSkipPreferences> preferences;

    @Before
    public void setUp() throws Exception {
        service = new Media3PlaybackService();
        player = Mockito.mock(Player.class);
        FeedMedia media = Mockito.mock(FeedMedia.class);
        when(media.getId()).thenReturn(5L);
        when(media.localFileAvailable()).thenReturn(true);
        when(media.getLocalFileUrl()).thenReturn("/local/audio.mp3");
        when(player.getCurrentMediaItem()).thenReturn(new MediaItem.Builder()
                .setMediaId("5").setUri("/local/audio.mp3").build());
        when(player.getDeviceInfo()).thenReturn(DeviceInfo.UNKNOWN);
        when(player.getCurrentPosition()).thenReturn(1500L);
        when(player.isPlaying()).thenReturn(true);
        set("player", player);
        set("currentPlayable", media);
        set("adSkipRanges", new AdSkipRanges(Collections.singletonList(new AdSkipRanges.Range(1000, 2000)), 10000));
        preferences = Mockito.mockStatic(AdSkipPreferences.class);
        preferences.when(() -> AdSkipPreferences.isEnabled(service)).thenReturn(true);
        preferences.when(() -> AdSkipPreferences.autoSkip(service)).thenReturn(true);
    }

    @After
    public void tearDown() {
        preferences.close();
    }

    @Test
    public void automaticSeekUsesRangeEndAndSuppressesUnacknowledgedRepeats() throws Exception {
        assertTrue(check(false));
        assertFalse(check(false));
        verify(player).seekTo(2000L);
    }

    @Test
    public void automaticOffStillAllowsManualSkipWhilePaused() throws Exception {
        preferences.when(() -> AdSkipPreferences.autoSkip(service)).thenReturn(false);
        when(player.isPlaying()).thenReturn(false);
        assertFalse(check(false));
        assertTrue(check(true));
        verify(player).seekTo(2000L);
    }

    @Test
    public void pausedAutomaticPlaybackDoesNotSeek() throws Exception {
        when(player.isPlaying()).thenReturn(false);
        assertFalse(check(false));
        verify(player, never()).seekTo(2000L);
    }

    @Test
    public void staleEpisodeStreamingAndCastCannotUseLocalRanges() throws Exception {
        when(player.getCurrentMediaItem()).thenReturn(new MediaItem.Builder()
                .setMediaId("6").setUri("/local/audio.mp3").build());
        assertFalse(check(true));
        when(player.getCurrentMediaItem()).thenReturn(new MediaItem.Builder()
                .setMediaId("5").setUri("https://example.com/audio.mp3").build());
        assertFalse(check(true));
        when(player.getCurrentMediaItem()).thenReturn(new MediaItem.Builder()
                .setMediaId("5").setUri("/local/audio.mp3").build());
        when(player.getDeviceInfo()).thenReturn(new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).build());
        assertFalse(check(true));
        verify(player, never()).seekTo(2000L);
    }

    @Test
    public void rangeEndAndDisabledIntegrationNeverSeek() throws Exception {
        when(player.getCurrentPosition()).thenReturn(2000L);
        assertFalse(check(true));
        when(player.getCurrentPosition()).thenReturn(1500L);
        preferences.when(() -> AdSkipPreferences.isEnabled(service)).thenReturn(false);
        assertFalse(check(true));
        verify(player, never()).seekTo(2000L);
    }

    private void set(String name, Object value) throws Exception {
        Field field = Media3PlaybackService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private boolean check(boolean manual) throws Exception {
        Method method = Media3PlaybackService.class.getDeclaredMethod("checkAdSkip", boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, manual);
    }
}
