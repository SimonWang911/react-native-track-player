package com.guichaguri.trackplayer.service;

import android.os.Binder;
import android.os.Bundle;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.media3.common.util.UnstableApi;

import com.facebook.react.bridge.Promise;
import com.guichaguri.trackplayer.service.metadata.MetadataManager;
import com.guichaguri.trackplayer.service.models.NowPlayingMetadata;
import com.guichaguri.trackplayer.service.player.AudioOutputController;
import com.guichaguri.trackplayer.service.player.ExoPlayback;
import com.guichaguri.trackplayer.service.player.PlaybackLifecycleController;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Guichaguri
 */
@UnstableApi
public class MusicBinder extends Binder {

    interface PlaybackAccess {
        ExoPlayback getPlayback();
        void setupPlayback(Bundle options, PlaybackLifecycleController.SetupCallback callback);
    }

    public static final class PlaybackNotInitializedException extends IllegalStateException {
        public PlaybackNotInitializedException() {
            super("The player is not initialized");
        }
    }

    private final MusicService service;
    private final MusicManager manager;
    private final PlaybackAccess playbackAccess;

    public MusicBinder(MusicService service, MusicManager manager) {
        this(service, manager, manager);
    }

    MusicBinder(MusicService service, MusicManager manager, PlaybackAccess playbackAccess) {
        this.service = service;
        this.manager = manager;
        this.playbackAccess = playbackAccess;
    }

    public void post(Runnable r) {
        service.handler.post(r);
    }

    public ExoPlayback getPlayback() {
        ExoPlayback playback = playbackAccess.getPlayback();
        if (playback == null) throw new PlaybackNotInitializedException();
        return playback;
    }

    public void setupPlayer(Bundle bundle, Promise promise) {
        AtomicBoolean completed = new AtomicBoolean();
        try {
            playbackAccess.setupPlayback(bundle, new PlaybackLifecycleController.SetupCallback() {
                @Override
                public void onSuccess(AudioOutputController.PlayerAdapter player) {
                    if (completed.compareAndSet(false, true)) promise.resolve(null);
                }

                @Override
                public void onFailure(RuntimeException error) {
                    if (completed.compareAndSet(false, true)) {
                        promise.reject("player_setup_failed", error);
                    }
                }
            });
        } catch (RuntimeException error) {
            if (completed.compareAndSet(false, true)) {
                promise.reject("player_setup_failed", error);
            }
        }
    }

    public void updateOptions(Bundle bundle) {
        manager.setStopWithApp(bundle.getBoolean("stopWithApp", false));
        // manager.setAlwaysPauseOnInterruption(bundle.getBoolean("alwaysPauseOnInterruption", false));
        manager.getMetadata().updateOptions(bundle);
    }

    public void updateNowPlayingMetadata(NowPlayingMetadata nowPlaying, boolean isPlaying) {
        MetadataManager metadata = manager.getMetadata();

        // TODO elapsedTime
        metadata.updateMetadata(getPlayback(), nowPlaying, isPlaying);
        metadata.setActive(true);
    }

    public void clearNowPlayingMetadata() {
        manager.getMetadata().clearNowPlayingMetadata();
    }

    public void updateNowPlayingTitles(long duration, String title, String artist, String album) {
      MetadataManager metadata = manager.getMetadata();
      metadata.updateNowPlayingTitles(getPlayback(), duration, title, artist, album);
      MediaSessionCompat session = metadata.getSession();
      if (session.isActive()) return;
      session.setActive(true);
    }

    public int getRatingType() {
        return manager.getMetadata().getRatingType();
    }

    public void destroy() {
        service.destroy();
        service.stopSelf();
    }

}
