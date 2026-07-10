package com.guichaguri.trackplayer.service.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlaybackLifecycleControllerTest {

    @Test
    public void synchronousSinkFailureDuringPrepareWaitsForOuterDowngradeToFinish() {
        List<String> operations = new ArrayList<>();
        HandlerSerialQueue queue = new HandlerSerialQueue(
                new Handler(Looper.getMainLooper()),
                new Object()
        );
        RecordingFactory factory = new RecordingFactory(operations);
        RecordingOwner owner = new RecordingOwner(operations);
        RecordingListener listener = new RecordingListener(operations);
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true);
        shadowOf(Looper.getMainLooper()).idle();
        LifecyclePlayer first = factory.players.get(0);
        populate(first, true);
        controller.setUserPlayIntent(PlaybackSnapshot.UserPlayIntent.PLAY);
        first.prepareHook = controller::onAudioSinkError;

        controller.onAudioSinkError();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(2, factory.players.size());
        assertTrue(indexOf(operations, "player-1:prepare:end")
                < indexOf(operations, "compatibility:false"));
        assertTrue(indexOf(operations, "compatibility:false")
                < indexOf(operations, "player-2:activate"));
        assertTrue(indexOf(operations, "player-2:validate")
                < indexOf(operations, "swap"));
        assertTrue(indexOf(operations, "swap")
                < indexOf(operations, "player-1:release"));
    }

    @Test
    public void rebuildActivatesAndValidatesBeforeSwapThenReleasesOldGeneration() {
        List<String> operations = new ArrayList<>();
        ManualSerialQueue queue = new ManualSerialQueue();
        RecordingFactory factory = new RecordingFactory(operations);
        RecordingOwner owner = new RecordingOwner(operations);
        RecordingListener listener = new RecordingListener(operations);
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true);
        queue.runNext();
        LifecyclePlayer first = factory.players.get(0);
        populate(first, true);
        controller.setUserPlayIntent(PlaybackSnapshot.UserPlayIntent.PLAY);

        controller.onAudioSinkError();
        queue.runNext();
        controller.onAudioSinkError();
        queue.runNext();

        LifecyclePlayer rebuilt = factory.players.get(1);
        assertSame(rebuilt, owner.current);
        assertTrue(indexOf(operations, "player-2:restoreQueue")
                < indexOf(operations, "player-2:activate"));
        assertTrue(indexOf(operations, "player-2:activate")
                < indexOf(operations, "player-2:validate"));
        assertTrue(indexOf(operations, "player-2:validate")
                < indexOf(operations, "swap"));
        assertTrue(indexOf(operations, "swap")
                < indexOf(operations, "player-1:release"));
        assertPlaybackState(rebuilt, true);
    }

    @Test
    public void activationFailureKeepsOldGenerationActiveAndUnreleased() {
        List<String> operations = new ArrayList<>();
        ManualSerialQueue queue = new ManualSerialQueue();
        RecordingFactory factory = new RecordingFactory(operations);
        RecordingOwner owner = new RecordingOwner(operations);
        RecordingListener listener = new RecordingListener(operations);
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true);
        queue.runNext();
        LifecyclePlayer first = factory.players.get(0);
        populate(first, false);
        controller.onAudioSinkError();
        queue.runNext();
        factory.failNextActivation = true;

        controller.onAudioSinkError();
        queue.runNext();

        LifecyclePlayer failedReplacement = factory.players.get(1);
        assertSame(first, owner.current);
        assertEquals(0, first.releaseCalls);
        assertEquals(1, failedReplacement.releaseCalls);
        assertEquals(1, listener.unrecoveredErrors);
        assertEquals(1, listener.events.size());
    }

    @Test
    public void rebuildPreservesIndependentIntentPlayerAndCompatibilityDimensions() {
        List<String> operations = new ArrayList<>();
        ManualSerialQueue queue = new ManualSerialQueue();
        RecordingFactory factory = new RecordingFactory(operations);
        RecordingOwner owner = new RecordingOwner(operations);
        RecordingListener listener = new RecordingListener(operations);
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true);
        queue.runNext();
        LifecyclePlayer first = factory.players.get(0);
        populate(first, true);
        controller.setUserPlayIntent(PlaybackSnapshot.UserPlayIntent.PLAY);
        controller.onAudioSinkError();
        queue.runNext();
        controller.onAudioSinkError();
        queue.runNext();

        assertEquals(PlaybackSnapshot.UserPlayIntent.PLAY, controller.getUserPlayIntent());
        assertEquals(ExoPlayback.PlayerPhase.PLAYING, controller.getPlayerPhase());
        assertEquals(
                AudioOutputController.AudioOutputPhase.COMPATIBILITY_MODE,
                controller.getAudioOutputPhase()
        );
        assertFalse(controller.isEffectiveAudioOffloadEnabled());
        assertTrue(controller.hasUsedInInstanceRecovery());
        assertTrue(controller.hasUsedRebuildRecovery());
    }

    @Test
    public void realHandlerTokenCancellationDropsQueuedRecoveryAndReleasesOnce() {
        List<String> operations = new ArrayList<>();
        HandlerSerialQueue queue = new HandlerSerialQueue(
                new Handler(Looper.getMainLooper()),
                new Object()
        );
        RecordingFactory factory = new RecordingFactory(operations);
        RecordingOwner owner = new RecordingOwner(operations);
        RecordingListener listener = new RecordingListener(operations);
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true);
        shadowOf(Looper.getMainLooper()).idle();
        LifecyclePlayer player = factory.players.get(0);

        controller.onAudioSinkError();
        controller.destroy();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, player.disableOffloadCalls);
        assertEquals(1, player.releaseCalls);
        assertTrue(listener.events.isEmpty());
        assertEquals(Arrays.asList("install", "destroy"), owner.ownerOperations);
    }

    @Test
    public void successfulActionsEmitExactlyOneStructuredCompatibilityEventEach() {
        List<String> operations = new ArrayList<>();
        ManualSerialQueue queue = new ManualSerialQueue();
        RecordingFactory factory = new RecordingFactory(operations);
        RecordingOwner owner = new RecordingOwner(operations);
        RecordingListener listener = new RecordingListener(operations);
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true);
        queue.runNext();
        LifecyclePlayer first = factory.players.get(0);
        populate(first, true);

        controller.onAudioSinkError();
        queue.runNext();
        controller.onAudioSinkError();
        queue.runNext();

        assertEquals(2, listener.events.size());
        assertCompatibilityEvent(listener.events.get(0), true, false);
        assertCompatibilityEvent(listener.events.get(1), true, true);
    }

    @Test
    public void delayedSetupCompletesOnlyAfterActivationValidationAndInstall() {
        List<String> operations = new ArrayList<>();
        ManualSerialQueue queue = new ManualSerialQueue();
        RecordingFactory factory = new RecordingFactory(operations);
        RecordingOwner owner = new RecordingOwner(operations);
        RecordingListener listener = new RecordingListener(operations);
        RecordingSetupCallback callback = new RecordingSetupCallback();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true, callback);

        assertEquals(0, callback.successes);
        assertEquals(0, callback.failures);
        assertEquals(null, owner.current);
        queue.runNext();

        assertEquals(1, callback.successes);
        assertEquals(0, callback.failures);
        assertSame(factory.players.get(0), callback.player);
        assertSame(factory.players.get(0), owner.current);
        assertTrue(indexOf(operations, "player-1:validate") < indexOf(operations, "install"));
    }

    @Test
    public void concurrentSetupCallsJoinOneInFlightGeneration() {
        List<String> operations = new ArrayList<>();
        ManualSerialQueue queue = new ManualSerialQueue();
        RecordingFactory factory = new RecordingFactory(operations);
        RecordingOwner owner = new RecordingOwner(operations);
        RecordingListener listener = new RecordingListener(operations);
        RecordingSetupCallback firstCallback = new RecordingSetupCallback();
        RecordingSetupCallback secondCallback = new RecordingSetupCallback();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true, firstCallback);
        controller.setup(true, secondCallback);

        assertEquals(1, queue.tasks.size());
        queue.runAll();

        assertEquals(1, factory.players.size());
        assertEquals(1, firstCallback.successes);
        assertEquals(1, secondCallback.successes);
        assertSame(factory.players.get(0), firstCallback.player);
        assertSame(factory.players.get(0), secondCallback.player);
        assertEquals(0, factory.players.get(0).releaseCalls);
    }

    @Test
    public void activationFailureCompletesFailureQueueStaysLiveAndGenerationReleasesOnce() {
        List<String> operations = new ArrayList<>();
        HandlerSerialQueue queue = new HandlerSerialQueue(
                new Handler(Looper.getMainLooper()),
                new Object()
        );
        RecordingFactory factory = new RecordingFactory(operations);
        factory.failNextActivation = true;
        RecordingOwner owner = new RecordingOwner(operations);
        RecordingListener listener = new RecordingListener(operations);
        RecordingSetupCallback callback = new RecordingSetupCallback();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true, callback);
        queue.post(() -> operations.add("after-setup-failure"));
        shadowOf(Looper.getMainLooper()).idle();

        LifecyclePlayer failed = factory.players.get(0);
        assertEquals(0, callback.successes);
        assertEquals(1, callback.failures);
        assertEquals("activation failed", callback.error.getMessage());
        assertEquals(1, failed.releaseCalls);
        assertTrue(operations.contains("after-setup-failure"));
        assertEquals(null, owner.current);

        controller.destroy();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, failed.releaseCalls);
    }

    @Test
    public void destroyRejectsQueuedSetupAfterHandlerTokenCancellation() {
        List<String> operations = new ArrayList<>();
        HandlerSerialQueue queue = new HandlerSerialQueue(
                new Handler(Looper.getMainLooper()),
                new Object()
        );
        RecordingFactory factory = new RecordingFactory(operations);
        RecordingOwner owner = new RecordingOwner(operations);
        RecordingListener listener = new RecordingListener(operations);
        RecordingSetupCallback callback = new RecordingSetupCallback();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true, callback);
        controller.destroy();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, factory.players.size());
        assertEquals(0, callback.successes);
        assertEquals(1, callback.failures);
        assertEquals("Playback lifecycle is destroyed", callback.error.getMessage());
        assertEquals(null, owner.current);
    }

    @Test
    public void downgradeExceptionReportsTerminalQueueStaysLiveAndDestroyReleasesOnce() {
        List<String> operations = new ArrayList<>();
        HandlerSerialQueue queue = new HandlerSerialQueue(
                new Handler(Looper.getMainLooper()),
                new Object()
        );
        RecordingFactory factory = new RecordingFactory(operations);
        RecordingOwner owner = new RecordingOwner(operations);
        RecordingListener listener = new RecordingListener(operations);
        RecordingSetupCallback callback = new RecordingSetupCallback();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true, callback);
        shadowOf(Looper.getMainLooper()).idle();
        LifecyclePlayer player = factory.players.get(0);
        player.failPrepare = true;

        controller.onAudioSinkError();
        queue.post(() -> operations.add("after-recovery-failure"));
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, listener.unrecoveredErrors);
        assertTrue(listener.events.isEmpty());
        assertTrue(operations.contains("after-recovery-failure"));
        assertEquals(0, player.releaseCalls);

        controller.onAudioSinkError();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, factory.players.size());
        assertEquals(1, listener.unrecoveredErrors);
        assertTrue(listener.events.isEmpty());

        controller.destroy();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, player.releaseCalls);
    }

    @Test
    public void rebuildReleaseFailureReportsTerminalAndStopsFurtherRecovery() {
        List<String> operations = new ArrayList<>();
        HandlerSerialQueue queue = new HandlerSerialQueue(
                new Handler(Looper.getMainLooper()),
                new Object()
        );
        RecordingFactory factory = new RecordingFactory(operations);
        RecordingOwner owner = new RecordingOwner(operations);
        RecordingListener listener = new RecordingListener(operations);
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true);
        shadowOf(Looper.getMainLooper()).idle();
        LifecyclePlayer first = factory.players.get(0);
        controller.onAudioSinkError();
        shadowOf(Looper.getMainLooper()).idle();
        first.failRelease = true;

        controller.onAudioSinkError();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, listener.unrecoveredErrors);
        assertEquals(1, listener.events.size());

        controller.onAudioSinkError();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(2, factory.players.size());
        assertEquals(1, first.releaseCalls);
        assertEquals(1, listener.events.size());
        assertEquals(1, listener.unrecoveredErrors);
    }

    private static void assertCompatibilityEvent(
            AudioOutputCompatibilityEvent event,
            boolean recovered,
            boolean rebuilt
    ) {
        assertEquals("audio_sink_offload_failed", event.getReason());
        assertFalse(event.isEffectiveAudioOffloadEnabled());
        assertEquals(recovered, event.isRecovered());
        assertEquals(rebuilt, event.isRebuilt());
    }

    private static int indexOf(List<String> operations, String operation) {
        int index = operations.indexOf(operation);
        assertTrue("missing operation: " + operation + " in " + operations, index >= 0);
        return index;
    }

    private static void populate(LifecyclePlayer player, boolean playWhenReady) {
        player.queue.addAll(Arrays.asList("a", "b"));
        player.index = 1;
        player.positionMs = 7_654L;
        player.repeatMode = 1;
        player.volume = 0.7F;
        player.rate = 1.1F;
        player.playWhenReady = playWhenReady;
        player.phase = playWhenReady ? ExoPlayback.PlayerPhase.PLAYING : ExoPlayback.PlayerPhase.PAUSED;
    }

    private static void assertPlaybackState(LifecyclePlayer player, boolean playWhenReady) {
        assertEquals(Arrays.asList("a", "b"), player.queue);
        assertEquals(1, player.index);
        assertEquals(7_654L, player.positionMs);
        assertEquals(1, player.repeatMode);
        assertEquals(0.7F, player.volume, 0.0001F);
        assertEquals(1.1F, player.rate, 0.0001F);
        assertEquals(playWhenReady, player.playWhenReady);
    }

    static class ManualSerialQueue implements PlaybackLifecycleController.SerialQueue {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void post(Runnable task) {
            tasks.add(task);
        }

        @Override
        public void clearPending() {
            tasks.clear();
        }

        void runNext() {
            tasks.remove().run();
        }

        void runAll() {
            while (!tasks.isEmpty()) runNext();
        }
    }

    static class RecordingFactory implements PlaybackLifecycleController.PlayerFactory {
        final List<LifecyclePlayer> players = new ArrayList<>();
        final List<String> operations;
        boolean failNextActivation;

        RecordingFactory(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public AudioOutputController.PlayerAdapter create(boolean audioOffloadEnabled) {
            LifecyclePlayer player = new LifecyclePlayer(
                    "player-" + (players.size() + 1),
                    audioOffloadEnabled,
                    operations
            );
            player.failActivation = failNextActivation;
            failNextActivation = false;
            players.add(player);
            return player;
        }
    }

    static class RecordingOwner implements PlaybackLifecycleController.PlayerOwner {
        AudioOutputController.PlayerAdapter current;
        final List<String> operations;
        final List<String> ownerOperations = new ArrayList<>();

        RecordingOwner(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public void install(AudioOutputController.PlayerAdapter player) {
            current = player;
            operations.add("install");
            ownerOperations.add("install");
        }

        @Override
        public void swap(AudioOutputController.PlayerAdapter oldPlayer, AudioOutputController.PlayerAdapter newPlayer) {
            assertSame(current, oldPlayer);
            current = newPlayer;
            operations.add("swap");
            ownerOperations.add("swap");
        }

        @Override
        public void clear(AudioOutputController.PlayerAdapter player) {
            assertSame(current, player);
            current = null;
            operations.add("destroy");
            ownerOperations.add("destroy");
        }
    }

    static class RecordingListener implements PlaybackLifecycleController.Listener {
        final List<AudioOutputCompatibilityEvent> events = new ArrayList<>();
        final List<String> operations;
        int unrecoveredErrors;

        RecordingListener(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public void onCompatibilityChanged(AudioOutputCompatibilityEvent event) {
            events.add(event);
            operations.add("compatibility:" + event.isRebuilt());
        }

        @Override
        public void onUnrecoveredAudioSinkError() {
            unrecoveredErrors++;
            operations.add("unrecovered");
        }
    }

    static class RecordingSetupCallback implements PlaybackLifecycleController.SetupCallback {
        int successes;
        int failures;
        AudioOutputController.PlayerAdapter player;
        RuntimeException error;

        @Override
        public void onSuccess(AudioOutputController.PlayerAdapter player) {
            successes++;
            this.player = player;
        }

        @Override
        public void onFailure(RuntimeException error) {
            failures++;
            this.error = error;
        }
    }

    static class LifecyclePlayer implements AudioOutputController.PlayerAdapter {
        final String name;
        final List<String> operations;
        final List<Object> queue = new ArrayList<>();
        boolean offloadEnabled;
        int index;
        long positionMs;
        int repeatMode;
        float volume = 1F;
        float rate = 1F;
        boolean playWhenReady;
        ExoPlayback.PlayerPhase phase = ExoPlayback.PlayerPhase.IDLE;
        int disableOffloadCalls;
        int releaseCalls;
        boolean failActivation;
        boolean failPrepare;
        boolean failRelease;
        Runnable prepareHook;

        LifecyclePlayer(String name, boolean offloadEnabled, List<String> operations) {
            this.name = name;
            this.offloadEnabled = offloadEnabled;
            this.operations = operations;
        }

        @Override
        public List<Object> getQueueSnapshot() {
            return new ArrayList<>(queue);
        }

        @Override
        public int getCurrentIndex() {
            return index;
        }

        @Override
        public long getPositionMs() {
            return positionMs;
        }

        @Override
        public int getRepeatMode() {
            return repeatMode;
        }

        @Override
        public float getVolume() {
            return volume;
        }

        @Override
        public float getRate() {
            return rate;
        }

        @Override
        public void restoreQueue(List<Object> queue) {
            operations.add(name + ":restoreQueue");
            this.queue.clear();
            this.queue.addAll(queue);
        }

        @Override
        public void setRepeatMode(int repeatMode) {
            this.repeatMode = repeatMode;
        }

        @Override
        public void setVolume(float volume) {
            this.volume = volume;
        }

        @Override
        public void setRate(float rate) {
            this.rate = rate;
        }

        @Override
        public void seekTo(int index, long positionMs) {
            operations.add(name + ":seek");
            this.index = index;
            this.positionMs = positionMs;
        }

        @Override
        public void setPlayWhenReady(boolean playWhenReady) {
            this.playWhenReady = playWhenReady;
            phase = playWhenReady ? ExoPlayback.PlayerPhase.PLAYING : ExoPlayback.PlayerPhase.PAUSED;
        }

        @Override
        public boolean isAudioOffloadEnabled() {
            return offloadEnabled;
        }

        @Override
        public void setAudioOffloadEnabled(boolean enabled) {
            operations.add(name + ":disable");
            offloadEnabled = enabled;
            if (!enabled) disableOffloadCalls++;
        }

        @Override
        public void prepare() {
            operations.add(name + ":prepare:start");
            if (failPrepare) throw new IllegalStateException("prepare failed");
            if (prepareHook != null) prepareHook.run();
            operations.add(name + ":prepare:end");
        }

        @Override
        public void activate() {
            operations.add(name + ":activate");
            if (failActivation) throw new IllegalStateException("activation failed");
        }

        @Override
        public void validateReady() {
            operations.add(name + ":validate");
        }

        @Override
        public ExoPlayback.PlayerPhase getPlayerPhase() {
            return phase;
        }

        @Override
        public void beginRecovery() {
            phase = ExoPlayback.PlayerPhase.RECOVERING;
        }

        @Override
        public void endRecovery() {
            phase = playWhenReady ? ExoPlayback.PlayerPhase.PLAYING : ExoPlayback.PlayerPhase.PAUSED;
        }

        @Override
        public void release() {
            releaseCalls++;
            operations.add(name + ":release");
            if (failRelease) throw new IllegalStateException("release failed");
        }
    }
}
