package com.guichaguri.trackplayer.service.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import com.facebook.react.bridge.Promise;
import com.guichaguri.trackplayer.service.errors.PlaybackErrorClassifierRegistry;
import com.guichaguri.trackplayer.service.errors.StructuredPlaybackError;
import com.guichaguri.trackplayer.service.models.Track;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ExoPlaybackRecoveryIntegrationTest {

    @After
    public void clearClassifiers() {
        PlaybackErrorClassifierRegistry.clear();
    }

    @Test
    public void prepareSeekAndIntentCallbacksDuringRecoveryDoNotEmitFalsePlayPauseEvents() {
        RecordingPlaybackEventHandler events = new RecordingPlaybackEventHandler();
        FakePlayer fakePlayer = new FakePlayer();
        fakePlayer.playbackState = Player.STATE_READY;
        fakePlayer.playWhenReady = true;
        fakePlayer.isPlaying = true;
        TestPlayback playback = new TestPlayback(events, fakePlayer);
        playback.initialize();
        fakePlayer.dispatchPlaybackStateChanged();
        events.resetUserFacingStateEvents();

        AudioOutputController controller = new AudioOutputController(playback);
        AudioOutputController.RecoveryAction action = controller.onAudioSinkError(
                PlaybackSnapshot.UserPlayIntent.PAUSE
        );
        fakePlayer.dispatchPlaybackStateChanged();

        assertEquals(AudioOutputController.RecoveryAction.IN_INSTANCE_RECOVERED, action);
        assertEquals(0, events.playEvents);
        assertEquals(0, events.pauseEvents);
        assertEquals(0, events.stateEvents);
        assertFalse(fakePlayer.playWhenReady);
        assertEquals(ExoPlayback.PlayerPhase.PAUSED, playback.getPlayerPhase());
    }

    @Test
    public void classifiedSourceDecoderAndDecryptionErrorsLeaveOffloadStateAndBudgetsUntouched() {
        Context context = RuntimeEnvironment.getApplication();
        RecordingPlaybackEventHandler events = new RecordingPlaybackEventHandler();
        FakePlayer fakePlayer = new FakePlayer();
        TestPlayback playback = new TestPlayback(events, fakePlayer);
        PlaybackLifecycleControllerTest.ManualSerialQueue queue =
                new PlaybackLifecycleControllerTest.ManualSerialQueue();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(
                queue,
                enabled -> playback,
                new NoOpOwner(),
                new NoOpLifecycleListener()
        );
        events.errorConsumer = error -> controller.onPlaybackError(error.getDomain(), error.getReason());
        controller.setup(true);
        queue.runAll();

        DataSpec dataSpec = new DataSpec(Uri.parse("https://example.invalid/audio"));
        HttpDataSource.HttpDataSourceException httpError =
                new HttpDataSource.HttpDataSourceException(
                        new IOException("http failure"),
                        dataSpec,
                        HttpDataSource.HttpDataSourceException.TYPE_OPEN
                );
        PlaybackException source = new PlaybackException(
                "source",
                httpError,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        );
        PlaybackException decoder = new PlaybackException(
                "decoder",
                null,
                PlaybackException.ERROR_CODE_DECODING_FAILED
        );
        PlaybackErrorClassifierRegistry.add(error -> {
            if (!(error instanceof DecryptionFailure)) return null;
            return new StructuredPlaybackError(
                    "audio-decryption",
                    error.getMessage(),
                    "decryption",
                    "decrypt_failed",
                    false
            );
        });
        PlaybackException decryption = new PlaybackException(
                "decryption",
                new DecryptionFailure("decrypt failure"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        );

        playback.onPlayerError(source);
        queue.runAll();
        playback.onPlayerError(decoder);
        queue.runAll();
        playback.onPlayerError(decryption);
        queue.runAll();

        assertEquals("source", events.errors.get(0).getDomain());
        assertEquals("decoder", events.errors.get(1).getDomain());
        assertEquals("decryption", events.errors.get(2).getDomain());
        assertEquals(AudioOutputController.AudioOutputPhase.NORMAL, controller.getAudioOutputPhase());
        assertTrue(controller.isEffectiveAudioOffloadEnabled());
        assertFalse(controller.hasUsedInInstanceRecovery());
        assertFalse(controller.hasUsedRebuildRecovery());
    }

    private static final class DecryptionFailure extends IOException {
        DecryptionFailure(String message) {
            super(message);
        }
    }

    private static final class NoOpOwner implements PlaybackLifecycleController.PlayerOwner {
        @Override
        public void install(AudioOutputController.PlayerAdapter player) {}

        @Override
        public void swap(
                AudioOutputController.PlayerAdapter oldPlayer,
                AudioOutputController.PlayerAdapter newPlayer
        ) {}

        @Override
        public void clear(AudioOutputController.PlayerAdapter player) {}
    }

    private static final class NoOpLifecycleListener implements PlaybackLifecycleController.Listener {
        @Override
        public void onCompatibilityChanged(AudioOutputCompatibilityEvent event) {}

        @Override
        public void onUnrecoveredAudioSinkError() {}
    }

    private static final class RecordingPlaybackEventHandler implements PlaybackEventHandler {
        int playEvents;
        int pauseEvents;
        int stateEvents;
        final List<StructuredPlaybackError> errors = new ArrayList<>();
        Consumer<StructuredPlaybackError> errorConsumer;

        @Override
        public void onPlay() {
            playEvents++;
        }

        @Override
        public void onPause() {
            pauseEvents++;
        }

        @Override
        public void onStateChange(int state) {
            stateEvents++;
        }

        @Override
        public void onError(StructuredPlaybackError error) {
            errors.add(error);
            if (errorConsumer != null) errorConsumer.accept(error);
        }

        void resetUserFacingStateEvents() {
            playEvents = 0;
            pauseEvents = 0;
            stateEvents = 0;
        }
    }

    private static final class TestPlayback extends ExoPlayback<Player>
            implements AudioOutputController.PlayerAdapter {
        private final FakePlayer fakePlayer;
        private final List<Object> snapshotQueue = new ArrayList<>();
        private boolean offloadEnabled = true;
        private int repeatMode;
        private float volume = 1F;

        TestPlayback(PlaybackEventHandler events, FakePlayer fakePlayer) {
            super(RuntimeEnvironment.getApplication(), events, fakePlayer.player, false);
            this.fakePlayer = fakePlayer;
        }

        @Override
        public void add(Track track, int index, Promise promise) {}

        @Override
        public void add(Collection<Track> tracks, int index, Promise promise) {}

        @Override
        public void remove(List<Integer> indexes, Promise promise) {}

        @Override
        public void removeUpcomingTracks() {}

        @Override
        public void setRepeatMode(int repeatMode) {
            this.repeatMode = repeatMode;
        }

        @Override
        public int getRepeatMode() {
            return repeatMode;
        }

        @Override
        public void isCached(String url, Promise promise) {}

        @Override
        public void getCacheSize(Promise promise) {}

        @Override
        public void clearCache(Promise promise) {}

        @Override
        public float getPlayerVolume() {
            return volume;
        }

        @Override
        public void setPlayerVolume(float volume) {
            this.volume = volume;
        }

        @Override
        public List<Object> getQueueSnapshot() {
            return new ArrayList<>(snapshotQueue);
        }

        @Override
        public int getCurrentIndex() {
            return fakePlayer.currentIndex;
        }

        @Override
        public long getPositionMs() {
            return fakePlayer.positionMs;
        }

        @Override
        public void restoreQueue(List<Object> queue) {
            snapshotQueue.clear();
            snapshotQueue.addAll(queue);
        }

        @Override
        public void seekTo(int index, long positionMs) {
            fakePlayer.currentIndex = index;
            fakePlayer.positionMs = positionMs;
            fakePlayer.dispatchPlaybackStateChanged();
        }

        @Override
        public void setPlayWhenReady(boolean playWhenReady) {
            fakePlayer.setPlayWhenReady(playWhenReady);
        }

        @Override
        public boolean isAudioOffloadEnabled() {
            return offloadEnabled;
        }

        @Override
        public void setAudioOffloadEnabled(boolean enabled) {
            offloadEnabled = enabled;
        }

        @Override
        public void prepare() {
            fakePlayer.prepare();
        }

        @Override
        public void activate() {
            initialize();
        }

        @Override
        public void validateReady() {
            if (fakePlayer.playbackState != Player.STATE_READY) {
                throw new IllegalStateException("player not ready");
            }
        }

        @Override
        public void release() {
            destroy();
        }
    }

    private static final class FakePlayer implements InvocationHandler {
        final Player player;
        final List<Player.Listener> listeners = new ArrayList<>();
        int playbackState = Player.STATE_READY;
        boolean playWhenReady;
        boolean isPlaying;
        int currentIndex;
        long positionMs;
        PlaybackParameters playbackParameters = PlaybackParameters.DEFAULT;

        FakePlayer() {
            player = (Player)Proxy.newProxyInstance(
                    Player.class.getClassLoader(),
                    new Class<?>[] { Player.class },
                    this
            );
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("addListener")) {
                listeners.add((Player.Listener)args[0]);
                return null;
            }
            if (name.equals("removeListener")) {
                listeners.remove(args[0]);
                return null;
            }
            if (name.equals("getPlaybackState")) return playbackState;
            if (name.equals("getPlayWhenReady")) return playWhenReady;
            if (name.equals("isPlaying")) return isPlaying;
            if (name.equals("getCurrentMediaItemIndex")) return currentIndex;
            if (name.equals("getCurrentPosition")) return positionMs;
            if (name.equals("getBufferedPosition")) return positionMs;
            if (name.equals("getDuration")) return 0L;
            if (name.equals("getPlaybackParameters")) return playbackParameters;
            if (name.equals("setPlaybackParameters")) {
                playbackParameters = (PlaybackParameters)args[0];
                return null;
            }
            if (name.equals("getCurrentTimeline")) return Timeline.EMPTY;
            if (name.equals("setPlayWhenReady")) {
                setPlayWhenReady((Boolean)args[0]);
                return null;
            }
            if (name.equals("prepare")) {
                prepare();
                return null;
            }
            if (name.equals("release") || name.equals("stop") || name.equals("clearMediaItems")) {
                return null;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return C.INDEX_UNSET;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0F;
            if (returnType == double.class) return 0D;
            return null;
        }

        void prepare() {
            playbackState = Player.STATE_BUFFERING;
            dispatchPlaybackStateChanged();
            playbackState = Player.STATE_READY;
            isPlaying = playWhenReady;
            dispatchPlaybackStateChanged();
        }

        void setPlayWhenReady(boolean playWhenReady) {
            this.playWhenReady = playWhenReady;
            isPlaying = playWhenReady && playbackState == Player.STATE_READY;
            for (Player.Listener listener : new ArrayList<>(listeners)) {
                listener.onPlayWhenReadyChanged(
                        playWhenReady,
                        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
                );
            }
        }

        void dispatchPlaybackStateChanged() {
            for (Player.Listener listener : new ArrayList<>(listeners)) {
                listener.onPlaybackStateChanged(playbackState);
            }
        }
    }
}
