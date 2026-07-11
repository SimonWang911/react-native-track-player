package com.guichaguri.trackplayer.service.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class PlaybackLifecycleCleanupIntegrationTest {

    @Test
    public void failedSetupCandidateCleanupIsRetainedAndRetriedBeforeNextGeneration() {
        LocalPlaybackCleanupIntegrationTest.RecordingCache cacheResource =
                new LocalPlaybackCleanupIntegrationTest.RecordingCache();
        cacheResource.releaseFailures = 1;
        PlaybackCache cache = new PlaybackCache(
                RuntimeEnvironment.getApplication(),
                1_024,
                (context, maxSize) -> cacheResource.value
        );
        LocalPlaybackCleanupIntegrationTest.RecordingExoPlayer failedPlayer =
                new LocalPlaybackCleanupIntegrationTest.RecordingExoPlayer();
        failedPlayer.analyticsAttachmentFailures = 1;
        failedPlayer.releaseFailures = 1;
        LocalPlaybackCleanupIntegrationTest.RecordingExoPlayer activePlayer =
                new LocalPlaybackCleanupIntegrationTest.RecordingExoPlayer();
        QueueFactory factory = new QueueFactory(
                playback(failedPlayer, cache),
                playback(activePlayer, cache)
        );
        PlaybackLifecycleControllerTest.ManualSerialQueue queue =
                new PlaybackLifecycleControllerTest.ManualSerialQueue();
        RecordingOwner owner = new RecordingOwner();
        RecordingListener listener = new RecordingListener();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(
                queue,
                factory,
                owner,
                listener
        );
        RecordingSetupCallback failed = new RecordingSetupCallback();
        RecordingSetupCallback active = new RecordingSetupCallback();

        controller.setup(true, failed);
        queue.runAll();

        assertEquals(1, failed.failures);
        assertEquals(1, failedPlayer.removeListenerCalls);
        assertEquals(1, failedPlayer.releaseCalls);
        assertEquals(1, cacheResource.releaseCalls);
        assertEquals(1, listener.internalErrors.size());
        assertEquals("playback_generation_cleanup_failed", listener.internalErrors.get(0));

        controller.setup(true, active);
        queue.runAll();

        assertEquals(1, active.successes);
        assertEquals(2, failedPlayer.releaseCalls);
        assertEquals(1, failedPlayer.removeListenerCalls);
        assertEquals(2, cacheResource.releaseCalls);
        assertSame(factory.created.get(1), owner.current);

        controller.destroy();
        queue.runAll();
        assertEquals(1, activePlayer.releaseCalls);
        assertEquals(1, owner.clearCalls);
    }

    @Test
    public void repeatedDestroyRetainsActiveAndRetiredGenerationsUntilCleanupSucceeds() {
        LocalPlaybackCleanupIntegrationTest.RecordingCache cacheResource =
                new LocalPlaybackCleanupIntegrationTest.RecordingCache();
        cacheResource.releaseFailures = 1;
        PlaybackCache cache = new PlaybackCache(
                RuntimeEnvironment.getApplication(),
                1_024,
                (context, maxSize) -> cacheResource.value
        );
        LocalPlaybackCleanupIntegrationTest.RecordingExoPlayer retiredPlayer =
                new LocalPlaybackCleanupIntegrationTest.RecordingExoPlayer();
        retiredPlayer.releaseFailures = 2;
        LocalPlaybackCleanupIntegrationTest.RecordingExoPlayer activePlayer =
                new LocalPlaybackCleanupIntegrationTest.RecordingExoPlayer();
        activePlayer.releaseFailures = 1;
        QueueFactory factory = new QueueFactory(
                playback(retiredPlayer, cache),
                playback(activePlayer, cache)
        );
        PlaybackLifecycleControllerTest.ManualSerialQueue queue =
                new PlaybackLifecycleControllerTest.ManualSerialQueue();
        RecordingOwner owner = new RecordingOwner();
        RecordingListener listener = new RecordingListener();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(
                queue,
                factory,
                owner,
                listener
        );

        controller.setup(true);
        queue.runAll();
        controller.setup(false);
        queue.runAll();

        assertEquals(1, retiredPlayer.releaseCalls);
        assertSame(factory.created.get(1), owner.current);

        controller.destroy();
        controller.destroy();
        assertEquals(2, queue.tasks.size());
        queue.runNext();

        assertEquals(0, owner.clearCalls);
        assertSame(factory.created.get(1), owner.current);
        assertEquals(2, retiredPlayer.releaseCalls);
        assertEquals(1, activePlayer.releaseCalls);
        assertEquals(1, cacheResource.releaseCalls);

        queue.runNext();
        controller.destroy();
        queue.runAll();

        assertEquals(1, owner.clearCalls);
        assertEquals(null, owner.current);
        assertEquals(3, retiredPlayer.releaseCalls);
        assertEquals(2, activePlayer.releaseCalls);
        assertEquals(2, cacheResource.releaseCalls);
        assertEquals(1, retiredPlayer.removeAnalyticsListenerCalls);
        assertEquals(1, retiredPlayer.removeListenerCalls);
        assertEquals(1, activePlayer.removeAnalyticsListenerCalls);
        assertEquals(1, activePlayer.removeListenerCalls);
    }

    @Test
    public void ownerClearFailureRetriesWithoutRepeatingCompletedActiveCleanup() {
        LocalPlaybackCleanupIntegrationTest.RecordingCache cacheResource =
                new LocalPlaybackCleanupIntegrationTest.RecordingCache();
        PlaybackCache cache = new PlaybackCache(
                RuntimeEnvironment.getApplication(),
                1_024,
                (context, maxSize) -> cacheResource.value
        );
        LocalPlaybackCleanupIntegrationTest.RecordingExoPlayer activePlayer =
                new LocalPlaybackCleanupIntegrationTest.RecordingExoPlayer();
        QueueFactory factory = new QueueFactory(playback(activePlayer, cache));
        PlaybackLifecycleControllerTest.ManualSerialQueue queue =
                new PlaybackLifecycleControllerTest.ManualSerialQueue();
        RecordingOwner owner = new RecordingOwner();
        owner.clearFailures = 1;
        RecordingListener listener = new RecordingListener();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(
                queue,
                factory,
                owner,
                listener
        );

        controller.setup(true);
        queue.runAll();
        controller.destroy();
        queue.runAll();

        assertSame(factory.created.get(0), owner.current);
        assertEquals(1, owner.clearCalls);
        assertEquals(1, activePlayer.releaseCalls);
        assertEquals(1, cacheResource.releaseCalls);
        assertEquals("playback_generation_owner_clear_failed", listener.internalErrors.get(0));

        controller.destroy();
        queue.runAll();
        controller.destroy();
        queue.runAll();

        assertEquals(null, owner.current);
        assertEquals(2, owner.clearCalls);
        assertEquals(1, activePlayer.releaseCalls);
        assertEquals(1, cacheResource.releaseCalls);
    }

    private static LocalPlayback playback(
            LocalPlaybackCleanupIntegrationTest.RecordingExoPlayer player,
            PlaybackCache cache
    ) {
        return new LocalPlayback(
                RuntimeEnvironment.getApplication(),
                new PlaybackEventHandler() {},
                player.value,
                cache,
                false,
                true
        );
    }

    private static final class QueueFactory implements PlaybackLifecycleController.PlayerFactory {
        final ArrayDeque<LocalPlayback> queued = new ArrayDeque<>();
        final List<LocalPlayback> created = new ArrayList<>();

        QueueFactory(LocalPlayback... players) {
            for (LocalPlayback player : players) queued.add(player);
        }

        @Override
        public AudioOutputController.PlayerAdapter create(PlaybackSetupSpec setupSpec) {
            LocalPlayback player = queued.remove();
            created.add(player);
            return player;
        }
    }

    private static final class RecordingOwner implements PlaybackLifecycleController.PlayerOwner {
        AudioOutputController.PlayerAdapter current;
        int clearFailures;
        int clearCalls;

        @Override
        public void install(AudioOutputController.PlayerAdapter player) {
            current = player;
        }

        @Override
        public void swap(
                AudioOutputController.PlayerAdapter oldPlayer,
                AudioOutputController.PlayerAdapter newPlayer
        ) {
            assertSame(current, oldPlayer);
            current = newPlayer;
        }

        @Override
        public void clear(AudioOutputController.PlayerAdapter player) {
            assertSame(current, player);
            clearCalls++;
            if (clearFailures > 0) {
                clearFailures--;
                throw new IllegalStateException("owner clear failed");
            }
            current = null;
        }
    }

    private static final class RecordingListener implements PlaybackLifecycleController.Listener {
        final List<String> internalErrors = new ArrayList<>();

        @Override
        public void onCompatibilityChanged(AudioOutputCompatibilityEvent event) {}

        @Override
        public void onUnrecoveredAudioSinkError() {}

        @Override
        public void onInternalError(String code, RuntimeException error) {
            internalErrors.add(code);
        }
    }

    private static final class RecordingSetupCallback
            implements PlaybackLifecycleController.SetupCallback {
        int successes;
        int failures;

        @Override
        public void onSuccess(AudioOutputController.PlayerAdapter player) {
            successes++;
        }

        @Override
        public void onFailure(RuntimeException error) {
            failures++;
        }
    }
}
