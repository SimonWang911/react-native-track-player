package com.guichaguri.trackplayer.service.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PlaybackLifecycleControllerTest {

    @Test
    public void setupRecoveryRebuildAndDestroyAreSerialized() {
        ManualSerialQueue queue = new ManualSerialQueue();
        RecordingFactory factory = new RecordingFactory();
        RecordingOwner owner = new RecordingOwner();
        RecordingListener listener = new RecordingListener();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true);
        assertEquals(1, queue.size());
        assertEquals(0, factory.players.size());
        queue.runNext();

        AudioOutputControllerTest.FakePlayer first = factory.players.get(0);
        populate(first, true);
        controller.setUserPlayIntent(PlaybackSnapshot.UserPlayIntent.PLAY);

        controller.onAudioSinkError();
        controller.onAudioSinkError();
        assertEquals(2, queue.size());
        assertEquals(0, first.disableOffloadCalls);

        queue.runNext();
        assertEquals(1, first.disableOffloadCalls);
        assertEquals(1, listener.events.size());
        assertEquals("true:false", listener.events.get(0));

        queue.runNext();
        assertEquals(2, factory.players.size());
        AudioOutputControllerTest.FakePlayer rebuilt = factory.players.get(1);
        assertSame(rebuilt, owner.current);
        assertEquals(1, first.releaseCalls);
        assertFalse(rebuilt.offloadEnabled);
        assertPlaybackState(rebuilt, true);
        assertEquals(2, listener.events.size());
        assertEquals("true:true", listener.events.get(1));

        controller.onAudioSinkError();
        queue.runNext();
        assertEquals(2, factory.players.size());
        assertEquals(1, listener.unrecoveredErrors);

        controller.destroy();
        queue.runNext();
        assertEquals(1, rebuilt.releaseCalls);
        assertEquals(Arrays.asList("install", "swap", "destroy"), owner.operations);
    }

    @Test
    public void recoveryDoesNotEmitFalseUserCommands() {
        ManualSerialQueue queue = new ManualSerialQueue();
        RecordingFactory factory = new RecordingFactory();
        RecordingOwner owner = new RecordingOwner();
        RecordingListener listener = new RecordingListener();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true);
        queue.runNext();
        AudioOutputControllerTest.FakePlayer player = factory.players.get(0);
        populate(player, false);
        controller.setUserPlayIntent(PlaybackSnapshot.UserPlayIntent.PAUSE);

        controller.onAudioSinkError();
        queue.runNext();

        assertFalse(player.playWhenReady);
        assertEquals(0, listener.userPlayCommands);
        assertEquals(0, listener.userPauseCommands);
    }

    @Test
    public void destroyCancelsQueuedRecoveryAndReleasesCurrentGenerationOnce() {
        ManualSerialQueue queue = new ManualSerialQueue();
        RecordingFactory factory = new RecordingFactory();
        RecordingOwner owner = new RecordingOwner();
        RecordingListener listener = new RecordingListener();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(queue, factory, owner, listener);

        controller.setup(true);
        queue.runNext();
        AudioOutputControllerTest.FakePlayer player = factory.players.get(0);

        controller.onAudioSinkError();
        assertEquals(1, queue.size());
        controller.destroy();

        assertEquals(1, queue.size());
        queue.runNext();
        assertEquals(0, player.disableOffloadCalls);
        assertEquals(1, player.releaseCalls);
        assertTrue(listener.events.isEmpty());
    }

    private static void populate(AudioOutputControllerTest.FakePlayer player, boolean playWhenReady) {
        player.queue.addAll(Arrays.asList("a", "b"));
        player.index = 1;
        player.positionMs = 7_654L;
        player.repeatMode = 1;
        player.volume = 0.7F;
        player.rate = 1.1F;
        player.playWhenReady = playWhenReady;
    }

    private static void assertPlaybackState(AudioOutputControllerTest.FakePlayer player, boolean playWhenReady) {
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

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }

    static class RecordingFactory implements PlaybackLifecycleController.PlayerFactory {
        final List<AudioOutputControllerTest.FakePlayer> players = new ArrayList<>();

        @Override
        public AudioOutputController.PlayerAdapter create(boolean audioOffloadEnabled) {
            AudioOutputControllerTest.FakePlayer player = new AudioOutputControllerTest.FakePlayer(audioOffloadEnabled);
            players.add(player);
            return player;
        }
    }

    static class RecordingOwner implements PlaybackLifecycleController.PlayerOwner {
        AudioOutputController.PlayerAdapter current;
        final List<String> operations = new ArrayList<>();

        @Override
        public void install(AudioOutputController.PlayerAdapter player) {
            current = player;
            operations.add("install");
        }

        @Override
        public void swap(AudioOutputController.PlayerAdapter oldPlayer, AudioOutputController.PlayerAdapter newPlayer) {
            assertSame(current, oldPlayer);
            current = newPlayer;
            operations.add("swap");
        }

        @Override
        public void clear(AudioOutputController.PlayerAdapter player) {
            assertSame(current, player);
            current = null;
            operations.add("destroy");
        }
    }

    static class RecordingListener implements PlaybackLifecycleController.Listener {
        final List<String> events = new ArrayList<>();
        int unrecoveredErrors;
        int userPlayCommands;
        int userPauseCommands;

        @Override
        public void onCompatibilityChanged(boolean recovered, boolean rebuilt) {
            events.add(recovered + ":" + rebuilt);
        }

        @Override
        public void onUnrecoveredAudioSinkError() {
            unrecoveredErrors++;
        }
    }
}
