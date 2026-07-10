package com.guichaguri.trackplayer.service.player;

import android.content.Context;
import android.util.Log;

import com.facebook.react.bridge.Promise;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.MediaSource;

import com.guichaguri.trackplayer.service.MusicManager;
import com.guichaguri.trackplayer.service.Utils;
import com.guichaguri.trackplayer.service.models.Track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Guichaguri
 */
@UnstableApi
public class LocalPlayback extends ExoPlayback<ExoPlayer> implements AudioOutputController.PlayerAdapter {

    private final PlaybackCache cache;
    private boolean prepared = false;
    private boolean audioOffloadEnabled;
    private boolean listenerAttached;
    private boolean analyticsAttached;
    private boolean cacheAcquired;
    private boolean released;
    private final AnalyticsListener audioOutputListener = new AnalyticsListener() {
        @Override
        public void onAudioSinkError(EventTime eventTime, Exception audioSinkError) {
            events.onAudioSinkError();
        }
    };
    public LocalPlayback(
            Context context,
            PlaybackEventHandler events,
            ExoPlayer player,
            PlaybackCache cache,
            boolean autoUpdateMetadata,
            boolean audioOffloadEnabled
    ) {
        super(context, events, player, autoUpdateMetadata);
        this.cache = cache;
        this.audioOffloadEnabled = audioOffloadEnabled;
    }

    @Override
    public void initialize() {
        cache.acquire();
        cacheAcquired = true;
        super.initialize();
        listenerAttached = true;
        player.addAnalyticsListener(audioOutputListener);
        analyticsAttached = true;
    }

    public DataSource.Factory enableCaching(DataSource.Factory ds) {
        return cache.enableCaching(ds);
    }

    public void isCached(String url, Promise promise) {
        promise.resolve(cache.isCached(url));
    }

    public void getCacheSize(Promise promise) {
        promise.resolve((double)cache.getSize());
    }

    public void clearCache(Promise promise) {
        try {
            cache.clear();
        } catch (Exception error) {
            Log.e(Utils.LOG, error.getMessage());
        }
        promise.resolve(null);
    }

    private void ensurePrepared() {
        if(!prepared) {
            Log.d(Utils.LOG, "Preparing the media source...");
            player.prepare();
            prepared = true;
        }
    }

    @Override
    public List<Object> getQueueSnapshot() {
        return new ArrayList<>(queue);
    }

    @Override
    public int getCurrentIndex() {
        return player.getCurrentMediaItemIndex();
    }

    @Override
    public long getPositionMs() {
        return player.getCurrentPosition();
    }

    @Override
    public void restoreQueue(List<Object> restoredQueue) {
        queue.clear();
        player.clearMediaItems();
        for (Object item : restoredQueue) {
            Track track = (Track)item;
            queue.add(track);
            player.addMediaSource(track.toMediaSource(context, this));
        }
        player.prepare();
        prepared = !queue.isEmpty();
    }

    @Override
    public void seekTo(int index, long positionMs) {
        if (queue.isEmpty()) return;
        player.seekTo(index, positionMs);
    }

    @Override
    public void setPlayWhenReady(boolean playWhenReady) {
        player.setPlayWhenReady(playWhenReady);
    }

    @Override
    public boolean isAudioOffloadEnabled() {
        return audioOffloadEnabled;
    }

    @Override
    public void setAudioOffloadEnabled(boolean enabled) {
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon()
                        .setAudioOffloadPreferences(MusicManager.audioOffloadPreferences(enabled))
                        .build()
        );
        audioOffloadEnabled = enabled;
    }

    @Override
    public void release() {
        destroy();
    }

    @Override
    public void activate() {
        initialize();
    }

    @Override
    public void validateReady() {
        if (released) throw new IllegalStateException("Playback generation is released");
        if (player.getMediaItemCount() != queue.size()) {
            throw new IllegalStateException("Playback queue was not restored");
        }
        int currentIndex = player.getCurrentMediaItemIndex();
        if (!queue.isEmpty() && (currentIndex < 0 || currentIndex >= queue.size())) {
            throw new IllegalStateException("Playback index was not restored");
        }
    }

    @Override
    public void prepare() {
        player.prepare();
        prepared = !queue.isEmpty();
    }

    @Override
    public void beginRecovery() {
        super.beginRecovery();
    }

    @Override
    public void endRecovery() {
        super.endRecovery();
    }

    @Override
    public void add(Track track, int index, Promise promise) {
        queue.add(index, track);
        MediaSource trackSource = track.toMediaSource(context, this);
        player.addMediaSource(index, trackSource);
        promise.resolve(index);
        ensurePrepared();
    }

    @Override
    public void add(Collection<Track> tracks, int index, Promise promise) {
        List<MediaSource> trackList = new ArrayList<>();

        for(Track track : tracks) {
            trackList.add(track.toMediaSource(context, this));
        }

        queue.addAll(index, tracks);
        player.addMediaSources(index, trackList);
        promise.resolve(index);

        ensurePrepared();
    }

    @Override
    public void remove(List<Integer> indexes, Promise promise) {
        int currentIndex = player.getCurrentMediaItemIndex();

        // Sort the list so we can loop through sequentially
        Collections.sort(indexes);

        for(int i = indexes.size() - 1; i >= 0; i--) {
            int index = indexes.get(i);

            // Skip indexes that are the current track or are out of bounds
            if(index == currentIndex || index < 0 || index >= queue.size()) {
                // Resolve the promise when the last index is invalid
                if(i == 0) promise.resolve(null);
                continue;
            }

            queue.remove(index);

            player.removeMediaItem(index);
            if(i == 0) {
              promise.resolve(index);
            }

            // Fix the window index
            if (index < lastKnownWindow) {
                lastKnownWindow--;
            }
        }
    }

    @Override
    public void removeUpcomingTracks() {
        int currentIndex = player.getCurrentMediaItemIndex();
        if (currentIndex == C.INDEX_UNSET) return;

        for (int i = queue.size() - 1; i > currentIndex; i--) {
            queue.remove(i);
            player.removeMediaItem(i);
        }
    }

    @Override
    public void setRepeatMode(int repeatMode) {
        player.setRepeatMode(repeatMode);
    }

    public int getRepeatMode() {
        return player.getRepeatMode();
    }

    private void resetQueue() {
        queue.clear();


        player.clearMediaItems();
        player.prepare();
        prepared = false; // We set it to false as the queue is now empty

        lastKnownWindow = C.INDEX_UNSET;
        lastKnownPosition = C.INDEX_UNSET;

        events.onReset();
    }

    @Override
    public void play() {
        ensurePrepared();
        super.play();
    }

    @Override
    public void stop() {
        super.stop();
        prepared = false;
    }

    @Override
    public void seekTo(long time) {
        ensurePrepared();
        super.seekTo(time);
    }

    @Override
    public void reset() {
        Integer track = getCurrentTrackIndex();
        long position = player.getCurrentPosition();

        super.reset();
        resetQueue();

        events.onTrackUpdate(track, position, null, null);
    }

    @Override
    public float getPlayerVolume() {
        return player.getVolume();
    }

    @Override
    public void setPlayerVolume(float volume) {
        player.setVolume(volume);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if(playbackState == Player.STATE_ENDED) {
            prepared = false;
        }

        super.onPlaybackStateChanged(playbackState);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        prepared = false;
        super.onPlayerError(error);
    }

    @Override
    public void destroy() {
        if (released) return;
        released = true;
        if (analyticsAttached) player.removeAnalyticsListener(audioOutputListener);
        if (listenerAttached) player.removeListener(this);
        super.destroy();
        if (cacheAcquired) cache.release();
    }
}
