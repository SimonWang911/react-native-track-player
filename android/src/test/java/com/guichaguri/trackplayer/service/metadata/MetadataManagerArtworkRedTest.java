package com.guichaguri.trackplayer.service.metadata;

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.core.app.NotificationCompat;

import com.bumptech.glide.request.target.CustomTarget;
import com.facebook.react.bridge.Promise;
import com.guichaguri.trackplayer.service.MusicBinder;
import com.guichaguri.trackplayer.service.MusicManager;
import com.guichaguri.trackplayer.service.MusicService;
import com.guichaguri.trackplayer.service.models.NowPlayingMetadata;
import com.guichaguri.trackplayer.service.player.AudioOutputController;
import com.guichaguri.trackplayer.service.player.ExoPlayback;
import com.guichaguri.trackplayer.service.player.PlaybackEventHandler;
import com.guichaguri.trackplayer.service.player.PlaybackLifecycleController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
@UnstableApi
public class MetadataManagerArtworkRedTest {

    @Test
    public void sameUriWhileLoadingKeepsTheExistingRequest() {
        Harness harness = harness();
        harness.update("A", "https://art/a");
        harness.update("A", "https://art/a");

        assertEquals("same URI must not clear the active request", 0, harness.loader.clearCalls);
        assertEquals("same URI must not start a second request", 1, harness.loader.requests.size());

        harness.loader.ready(0, 0xff00ff00);
        harness.update("A ready", "https://art/a");

        assertEquals("same ready URI must reuse the completed request", 0, harness.loader.clearCalls);
        assertEquals("same ready URI must not start a second request", 1, harness.loader.requests.size());
        assertNotNull("same ready URI must retain its bitmap", harness.sink.lastMetadata().getBitmap(METADATA_KEY_ART));
    }

    @Test
    public void deactivatingReadyArtworkPreservesSnapshotAndReactivatesNotification() {
        Harness harness = harness();
        harness.update("A", "https://art/a");
        harness.loader.ready(0, 0xff00ff00);

        MediaMetadataCompat ready = harness.sink.lastMetadata();
        int clearCallsBeforeDeactivate = harness.loader.clearCalls;
        harness.metadata.setActive(false);

        assertFalse("deactivation must make the session inactive", harness.session().isActive());
        assertEquals("deactivation must not clear the target", clearCallsBeforeDeactivate,
                harness.loader.clearCalls);
        assertSame("deactivation must retain the latest metadata snapshot", ready,
                harness.sink.lastMetadata());
        assertNotNull("deactivation must retain the ready Bitmap", harness.sink.lastMetadata().getBitmap(METADATA_KEY_ART));
        assertEquals("https://art/a", harness.sink.lastMetadata().getString(METADATA_KEY_ART_URI));

        int notificationsBeforeReactivate = harness.sink.notifications.size();
        harness.metadata.setActive(true);

        assertTrue("reactivation must make the session active", harness.session().isActive());
        assertEquals("reactivation must publish the existing notification", notificationsBeforeReactivate + 1,
                harness.sink.notifications.size());
        assertNotNull("reactivation must restore the notification largeIcon",
                harness.sink.lastNotification().largeIcon);
        assertSame("reactivation must retain the same metadata snapshot", ready,
                harness.sink.lastMetadata());
    }

    @Test
    public void deactivatingLoadingArtworkKeepsRequestForLateReady() {
        Harness harness = harness();
        harness.update("A", "https://art/a");

        harness.metadata.setActive(false);
        harness.metadata.setActive(true);

        assertEquals("deactivate/reactivate must not clear the loading target", 0,
                harness.loader.clearCalls);
        assertEquals("deactivate/reactivate must not restart the loading request", 1,
                harness.loader.requests.size());

        harness.loader.ready(0, 0xff0000ff);

        assertNotNull("the retained loading request must still publish its Bitmap",
                harness.sink.lastMetadata().getBitmap(METADATA_KEY_ART));
        assertEquals("https://art/a", harness.sink.lastMetadata().getString(METADATA_KEY_ART_URI));
        assertNotNull("the retained loading request must publish a notification largeIcon",
                harness.sink.lastNotification().largeIcon);
    }

    @Test
    public void defaultArtworkLoaderCreatesItsDelegateOnlyOnFirstUse() {
        RecordingLoader delegate = new RecordingLoader();
        int[] createCalls = { 0 };
        ArtworkRequestLoader loader = new LazyArtworkRequestLoader(() -> {
            createCalls[0]++;
            return delegate;
        });

        assertEquals("lazy loader must not create a delegate during construction", 0, createCalls[0]);
        CustomTarget<Bitmap> target = emptyTarget();
        loader.clear(target);
        assertEquals("clear is the first use and creates the delegate once", 1, createCalls[0]);
        assertEquals(1, delegate.clearCalls);
        loader.clear(target);
        assertEquals("the delegate must be cached after first use", 1, createCalls[0]);
    }

    @Test
    public void publicConstructorInstallsTheLazyDefaultArtworkLoader() throws ReflectiveOperationException {
        MusicService service = Robolectric.buildService(MusicService.class).get();
        MusicManager manager = new MusicManager(service);
        Field field = MetadataManager.class.getDeclaredField("artworkLoader");
        field.setAccessible(true);

        assertTrue(field.get(manager.getMetadata()) instanceof LazyArtworkRequestLoader);
    }

    @Test
    public void staleArtworkCannotRenderOrReleaseTheBTarget() {
        Harness harness = harness();
        harness.update("A", "https://art/a");
        harness.update("B", "https://art/b");

        assertEquals("A to B must keep exactly one request per URI", 2, harness.loader.requests.size());
        int rendersBeforeStaleCallback = harness.sink.metadata.size();
        harness.loader.ready(0, 0xffff0000);

        MediaMetadataCompat afterStaleReady = harness.sink.lastMetadata();
        assertEquals("stale A callback must not replace B", "B", afterStaleReady.getString(METADATA_KEY_TITLE));
        assertEquals("stale A callback must not replace B artwork URI", "https://art/b",
                afterStaleReady.getString(METADATA_KEY_ART_URI));
        assertEquals("stale A callback must not render a new snapshot", rendersBeforeStaleCallback,
                harness.sink.metadata.size());
        assertEquals("B loading target must remain the current request", 2, harness.loader.requests.size());
    }

    @Test
    public void staleArtworkCallbackCannotReleaseTheCurrentTarget() {
        Harness harness = harness();
        harness.update("A", "https://art/a");
        harness.update("B", "https://art/b");

        harness.loader.ready(0, 0xffff0000);
        harness.update("C", "https://art/c");

        assertEquals("replacing C must clear the still-owned B target", 2, harness.loader.clearCalls);
        assertEquals("A to B to C must retain one request per URI", 3, harness.loader.requests.size());
    }

    @Test
    public void replacingReadyAWithLoadingBClearsBitmapAndLargeIconBeforeBCompletes() {
        Harness harness = harness();
        harness.update("A", "https://art/a");
        harness.loader.ready(0, 0xffff0000);
        harness.update("B", "https://art/b");

        assertLoadingArtworkIsCleared(harness, "B loading");

        harness.loader.fail(1);
        assertLoadingArtworkIsCleared(harness, "B failure");
    }

    @Test
    public void lateAFailureAndLoadClearedCannotClearTheBTargetOrResult() {
        List<String> failures = new ArrayList<>();
        for (LateCallback callback : new LateCallback[] {
                LateCallback.FAILURE,
                LateCallback.LOAD_CLEARED,
                LateCallback.SUCCESS
        }) {
            try {
                Harness harness = harness();
                harness.update("A", "https://art/a");
                harness.update("B", "https://art/b");

                callback.run(harness);
                harness.update("C", "https://art/c");

                assertEquals("late A " + callback + " must not lose the B target", 2,
                        harness.loader.clearCalls);
                assertSame("late A " + callback + " must leave B as the owned target",
                        harness.loader.requests.get(1).target,
                        harness.loader.clearedTargets.get(1));
                assertEquals("late A " + callback + " must establish the C request", 3,
                        harness.loader.requests.size());
                assertEquals("https://art/c", harness.loader.requests.get(2).artwork.toString());
            } catch (AssertionError error) {
                failures.add(callback + ": " + error.getMessage());
            }
        }
        assertTrue("late A callbacks must preserve B: " + failures, failures.isEmpty());
    }

    @Test
    public void nullArtworkClearsArtUriBitmapAndNotificationIcon() {
        Harness harness = harness();
        harness.update("A", "https://art/a");
        harness.loader.ready(0, 0xff00ff00);
        harness.update("No art", null);

        assertNullArtwork(harness);

        harness.binder.updateNowPlayingTitles(321, "Lyric", "Singer", "Album");
        assertNullArtwork(harness);
    }

    @Test
    public void failedArtworkDoesNotLeaveThePreviousNotificationIcon() {
        Harness harness = harness();
        harness.update("A", "https://art/a");
        harness.loader.ready(0, 0xffff0000);
        harness.update("B", "https://art/b");
        harness.loader.fail(1);

        assertNotNull("test must observe a notification render", harness.sink.lastNotification());
        assertNull("failed B artwork must not leave A as notification largeIcon",
                harness.sink.lastNotification().largeIcon);
        assertNull("failed B artwork must not leave a Bitmap in session metadata",
                harness.sink.lastMetadata().getBitmap(METADATA_KEY_ART));

        harness.update("B retry", "https://art/b");
        assertEquals("same failed URI must start a new request", 3, harness.loader.requests.size());
        harness.loader.fail(2);
        assertNull("retried failed B artwork must still clear the Bitmap",
                harness.sink.lastMetadata().getBitmap(METADATA_KEY_ART));
    }

    @Test
    public void titleOnlyWhileLoadingMergesWithTheLatestMetadataSnapshot() {
        Harness harness = harness();
        harness.update("Old", "https://art/a");
        harness.binder.updateNowPlayingTitles(321, "New", "Singer 2", "Album 2");
        harness.loader.ready(0, 0xff0000ff);

        MediaMetadataCompat latest = harness.sink.lastMetadata();
        assertEquals("New", latest.getString(METADATA_KEY_TITLE));
        assertEquals("Singer 2", latest.getString(METADATA_KEY_ARTIST));
        assertEquals("Album 2", latest.getString(METADATA_KEY_ALBUM));
        assertEquals(321L, latest.getLong(METADATA_KEY_DURATION));
    }

    @Test
    public void binderClearRejectsLateSuccessFailureAndLoadCleared() {
        assertTerminalExitRejectsAllLateCallbacks(TerminalExit.CLEAR);
    }

    @Test
    public void managerResetRejectsLateSuccessFailureAndLoadCleared() {
        assertTerminalExitRejectsAllLateCallbacks(TerminalExit.RESET);
    }

    @Test
    public void managerDestroyRejectsLateSuccessFailureAndLoadCleared() {
        assertTerminalExitRejectsAllLateCallbacks(TerminalExit.DESTROY);
    }

    @Test
    public void clearResetAndDestroyReleaseTheArtworkTargetThroughTheirPublicExits() {
        List<String> failures = new ArrayList<>();
        for (TerminalExit exit : TerminalExit.values()) {
            try {
                Harness harness = harness();
                harness.update("Pending", "https://art/pending");

                exit.run(harness);

                assertEquals(exit + " must clear its owned artwork target", 1, harness.loader.clearCalls);
            } catch (AssertionError error) {
                failures.add(exit + ": " + error.getMessage());
            }
        }
        assertTrue("all public exits must release their target: " + failures, failures.isEmpty());
    }

    private static void assertTerminalExitRejectsAllLateCallbacks(TerminalExit exit) {
        List<String> failures = new ArrayList<>();
        for (LateCallback callback : LateCallback.values()) {
            try {
                Harness harness = harness();
                harness.update("Pending", "https://art/pending");
                exit.run(harness);

                int metadataRendersAfterExit = harness.sink.metadata.size();
                int notificationRendersAfterExit = harness.sink.notifications.size();
                RuntimeException callbackError = null;
                try {
                    callback.run(harness);
                } catch (RuntimeException error) {
                    callbackError = error;
                }

                assertNull(exit + " + " + callback + " must ignore late callbacks", callbackError);
                assertFalse(exit + " must leave the session inactive", harness.session().isActive());
                assertNull(exit + " + " + callback + " must not restore session metadata",
                        harness.session().getController().getMetadata());
                assertEquals(exit + " + " + callback + " must not render metadata after exit",
                        metadataRendersAfterExit, harness.sink.metadata.size());
                assertEquals(exit + " + " + callback + " must not render a notification after exit",
                        notificationRendersAfterExit, harness.sink.notifications.size());
                if (notificationRendersAfterExit > 0) {
                    assertNull(exit + " + " + callback + " must leave largeIcon empty",
                            harness.sink.lastNotification().largeIcon);
                }
            } catch (AssertionError error) {
                failures.add(callback + ": " + error.getMessage());
            }
        }
        assertTrue(exit + " must reject every late callback: " + failures, failures.isEmpty());
    }

    private static void assertNullArtwork(Harness harness) {
        MediaMetadataCompat metadata = harness.sink.lastMetadata();
        assertNull("ART must be empty after explicit null", metadata.getBitmap(METADATA_KEY_ART));
        assertNull("ART_URI must be empty after explicit null", metadata.getString(METADATA_KEY_ART_URI));
        assertNull("notification largeIcon must be empty after explicit null",
                harness.sink.lastNotification().largeIcon);
    }

    private static void assertLoadingArtworkIsCleared(Harness harness, String phase) {
        MediaMetadataCompat metadata = harness.sink.lastMetadata();
        assertNull(phase + " must clear ART", metadata.getBitmap(METADATA_KEY_ART));
        assertEquals(phase + " must publish B ART_URI", "https://art/b",
                metadata.getString(METADATA_KEY_ART_URI));
        assertNull(phase + " must clear the notification largeIcon",
                harness.sink.lastNotification().largeIcon);
    }

    private static Harness harness() {
        MusicService service = Robolectric.buildService(MusicService.class).get();
        MusicManager manager = new MusicManager(service);
        setPrivateField(manager, "lifecycleController", lifecycleController());
        RecordingLoader loader = new RecordingLoader();
        RecordingSink sink = new RecordingSink();
        MetadataManager metadata = new MetadataManager(
                service,
                manager,
                loader,
                sink,
                new RecordingNotificationBuilder(service)
        );
        setPrivateField(manager, "metadata", metadata);
        setPrivateField(manager, "playback", new RecordingPlayback(service));
        return new Harness(service, manager, metadata, new MusicBinder(service, manager), loader, sink);
    }

    private static PlaybackLifecycleController lifecycleController() {
        return new PlaybackLifecycleController(
                new PlaybackLifecycleController.SerialQueue() {
                    @Override
                    public void post(Runnable task) {}

                    @Override
                    public void clearPending() {}
                },
                setupSpec -> new RecordingPlayer(),
                new PlaybackLifecycleController.PlayerOwner() {
                    @Override
                    public void install(AudioOutputController.PlayerAdapter player) {}

                    @Override
                    public void swap(
                            AudioOutputController.PlayerAdapter oldPlayer,
                            AudioOutputController.PlayerAdapter newPlayer
                    ) {}

                    @Override
                    public void clear(AudioOutputController.PlayerAdapter player) {}
                },
                new PlaybackLifecycleController.Listener() {
                    @Override
                    public void onCompatibilityChanged(
                            com.guichaguri.trackplayer.service.player.AudioOutputCompatibilityEvent event
                    ) {}

                    @Override
                    public void onUnrecoveredAudioSinkError() {}
                }
        );
    }

    private static void setPrivateField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("test harness could not install " + name, error);
        }
    }

    private enum TerminalExit {
        CLEAR {
            @Override
            void run(Harness harness) {
                harness.binder.clearNowPlayingMetadata();
            }
        },
        RESET {
            @Override
            void run(Harness harness) {
                harness.manager.onReset();
            }
        },
        DESTROY {
            @Override
            void run(Harness harness) {
                harness.manager.destroy();
            }
        };

        abstract void run(Harness harness);
    }

    private enum LateCallback {
        SUCCESS {
            @Override
            void run(Harness harness) {
                harness.loader.ready(0, 0xff0000ff);
            }
        },
        FAILURE {
            @Override
            void run(Harness harness) {
                harness.loader.fail(0);
            }
        },
        LOAD_CLEARED {
            @Override
            void run(Harness harness) {
                harness.loader.cleared(0);
            }
        };

        abstract void run(Harness harness);
    }

    private static final class Harness {
        final MusicService service;
        final MusicManager manager;
        final MetadataManager metadata;
        final MusicBinder binder;
        final RecordingLoader loader;
        final RecordingSink sink;

        Harness(
                MusicService service,
                MusicManager manager,
                MetadataManager metadata,
                MusicBinder binder,
                RecordingLoader loader,
                RecordingSink sink
        ) {
            this.service = service;
            this.manager = manager;
            this.metadata = metadata;
            this.binder = binder;
            this.loader = loader;
            this.sink = sink;
        }

        void update(String title, String artwork) {
            binder.updateNowPlayingMetadata(nowPlaying(service, title, artwork), true);
        }

        MediaSessionCompat session() {
            return metadata.getSession();
        }
    }

    private static NowPlayingMetadata nowPlaying(Context context, String title, String artwork) {
        Bundle bundle = new Bundle();
        bundle.putString("title", title);
        bundle.putString("artist", "Artist " + title);
        bundle.putString("album", "Album " + title);
        bundle.putDouble("duration", 123);
        if (artwork != null) bundle.putString("artwork", artwork);
        return new NowPlayingMetadata(context, bundle, 0);
    }

    private static final class RecordingLoader implements ArtworkRequestLoader {
        final List<Request> requests = new ArrayList<>();
        final List<CustomTarget<Bitmap>> clearedTargets = new ArrayList<>();
        int clearCalls;

        @Override
        public CustomTarget<Bitmap> load(Uri artwork, CustomTarget<Bitmap> target) {
            requests.add(new Request(artwork, target));
            return target;
        }

        @Override
        public void clear(CustomTarget<Bitmap> target) {
            clearCalls++;
            clearedTargets.add(target);
        }

        void ready(int index, int color) {
            requests.get(index).target.onResourceReady(bitmap(color), null);
        }

        void fail(int index) {
            requests.get(index).target.onLoadFailed(null);
        }

        void cleared(int index) {
            requests.get(index).target.onLoadCleared(null);
        }
    }

    private static final class Request {
        final Uri artwork;
        final CustomTarget<Bitmap> target;

        Request(Uri artwork, CustomTarget<Bitmap> target) {
            this.artwork = artwork;
            this.target = target;
        }
    }

    private static final class RecordingSink implements ArtworkRenderSink {
        final List<MediaMetadataCompat> metadata = new ArrayList<>();
        final List<Notification> notifications = new ArrayList<>();

        @Override
        public void renderMetadata(MediaSessionCompat session, MediaMetadataCompat value) {
            metadata.add(value);
            session.setMetadata(value);
        }

        @Override
        public void renderNotification(MusicService service, Notification value) {
            notifications.add(value);
        }

        Notification lastNotification() {
            return notifications.get(notifications.size() - 1);
        }

        MediaMetadataCompat lastMetadata() {
            return metadata.get(metadata.size() - 1);
        }
    }

    private static final class RecordingNotificationBuilder extends NotificationCompat.Builder {
        private Bitmap largeIcon;

        RecordingNotificationBuilder(Context context) {
            super(context, "test");
        }

        @Override
        public NotificationCompat.Builder setLargeIcon(Bitmap icon) {
            largeIcon = icon;
            return this;
        }

        @Override
        public Notification build() {
            Notification notification = new Notification();
            notification.largeIcon = largeIcon;
            return notification;
        }
    }

    private static Bitmap bitmap(int color) {
        return Bitmap.createBitmap(new int[] { color, color, color, color }, 2, 2, Bitmap.Config.ARGB_8888);
    }

    private static CustomTarget<Bitmap> emptyTarget() {
        return new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(
                    @androidx.annotation.NonNull Bitmap resource,
                    com.bumptech.glide.request.transition.Transition<? super Bitmap> transition
            ) {}

            @Override
            public void onLoadCleared(@androidx.annotation.Nullable android.graphics.drawable.Drawable placeholder) {}
        };
    }

    @SuppressWarnings("rawtypes")
    private static final class RecordingPlayback extends ExoPlayback<Player> {
        RecordingPlayback(Context context) {
            super(context, new PlaybackEventHandler() {}, null, false);
        }

        @Override
        public void add(com.guichaguri.trackplayer.service.models.Track track, int index, Promise promise) {}

        @Override
        public void add(Collection<com.guichaguri.trackplayer.service.models.Track> tracks, int index, Promise promise) {}

        @Override
        public void remove(List<Integer> indexes, Promise promise) {}

        @Override
        public void removeUpcomingTracks() {}

        @Override
        public void setRepeatMode(int repeatMode) {}

        @Override
        public int getRepeatMode() {
            return 0;
        }

        @Override
        public void isCached(String url, Promise promise) {}

        @Override
        public void getCacheSize(Promise promise) {}

        @Override
        public void clearCache(Promise promise) {}

        @Override
        public float getPlayerVolume() {
            return 1F;
        }

        @Override
        public void setPlayerVolume(float volume) {}

        @Override
        public int getState() {
            return android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING;
        }

        @Override
        public long getPosition() {
            return 0;
        }

        @Override
        public long getBufferedPosition() {
            return 0;
        }

        @Override
        public float getRate() {
            return 1F;
        }
    }

    private static final class RecordingPlayer implements AudioOutputController.PlayerAdapter {
        @Override
        public List<Object> getQueueSnapshot() {
            return new ArrayList<>();
        }

        @Override
        public int getCurrentIndex() {
            return 0;
        }

        @Override
        public long getPositionMs() {
            return 0;
        }

        @Override
        public int getRepeatMode() {
            return 0;
        }

        @Override
        public float getVolume() {
            return 1F;
        }

        @Override
        public float getRate() {
            return 1F;
        }

        @Override
        public void restoreQueue(List<Object> queue) {}

        @Override
        public void setRepeatMode(int repeatMode) {}

        @Override
        public void setVolume(float volume) {}

        @Override
        public void setRate(float rate) {}

        @Override
        public void seekTo(int index, long positionMs) {}

        @Override
        public void setPlayWhenReady(boolean playWhenReady) {}

        @Override
        public boolean isAudioOffloadEnabled() {
            return false;
        }

        @Override
        public void setAudioOffloadEnabled(boolean enabled) {}

        @Override
        public void prepare() {}

        @Override
        public void release() {}
    }
}
