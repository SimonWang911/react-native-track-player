package com.guichaguri.trackplayer.service.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AudioOutputControllerTest {

    @Test
    public void setupUsesEffectiveOffloadMode() {
        FakePlayer disabled = new FakePlayer(false);
        AudioOutputController disabledController = new AudioOutputController(disabled);
        assertFalse(disabledController.isEffectiveAudioOffloadEnabled());
        assertFalse(disabled.offloadEnabled);

        FakePlayer enabled = new FakePlayer(true);
        AudioOutputController enabledController = new AudioOutputController(enabled);
        assertTrue(enabledController.isEffectiveAudioOffloadEnabled());
        assertTrue(enabled.offloadEnabled);
    }

    @Test
    public void firstSinkFailureDowngradesInInstanceAndPreservesState() {
        FakePlayer player = populatedPlayer(true, true);
        AudioOutputController controller = new AudioOutputController(player);

        AudioOutputController.RecoveryAction action = controller.onAudioSinkError(
                PlaybackSnapshot.UserPlayIntent.PLAY
        );

        assertEquals(AudioOutputController.RecoveryAction.IN_INSTANCE_RECOVERED, action);
        assertFalse(player.offloadEnabled);
        assertEquals(1, player.disableOffloadCalls);
        assertEquals(1, player.prepareCalls);
        assertEquals(Arrays.asList("a", "b"), player.queue);
        assertEquals(1, player.index);
        assertEquals(12_345L, player.positionMs);
        assertEquals(2, player.repeatMode);
        assertEquals(0.4F, player.volume, 0.0001F);
        assertEquals(1.2F, player.rate, 0.0001F);
        assertTrue(player.playWhenReady);
        assertEquals(AudioOutputController.AudioOutputPhase.COMPATIBILITY_MODE, controller.getPhase());
    }

    @Test
    public void onlySinkErrorsConsumeRecoveryBudget() {
        FakePlayer player = populatedPlayer(true, false);
        AudioOutputController controller = new AudioOutputController(player);

        assertEquals(
                AudioOutputController.RecoveryAction.IGNORED,
                controller.onPlaybackError("source", "http_error")
        );
        assertEquals(
                AudioOutputController.RecoveryAction.IGNORED,
                controller.onPlaybackError("decoder", "decoder_error")
        );
        assertEquals(
                AudioOutputController.RecoveryAction.IGNORED,
                controller.onPlaybackError("decryption", "audio_decrypt_stream_invalid")
        );
        assertTrue(player.offloadEnabled);
        assertEquals(0, player.disableOffloadCalls);

        assertEquals(
                AudioOutputController.RecoveryAction.IN_INSTANCE_RECOVERED,
                controller.onAudioSinkError(PlaybackSnapshot.UserPlayIntent.PAUSE)
        );
        assertEquals(
                AudioOutputController.RecoveryAction.REBUILD_REQUIRED,
                controller.onAudioSinkError(PlaybackSnapshot.UserPlayIntent.PAUSE)
        );
    }

    static FakePlayer populatedPlayer(boolean offloadEnabled, boolean playWhenReady) {
        FakePlayer player = new FakePlayer(offloadEnabled);
        player.queue.addAll(Arrays.asList("a", "b"));
        player.index = 1;
        player.positionMs = 12_345L;
        player.repeatMode = 2;
        player.volume = 0.4F;
        player.rate = 1.2F;
        player.playWhenReady = playWhenReady;
        return player;
    }

    static class FakePlayer extends PlaybackSnapshotTest.FakeStateAdapter
            implements AudioOutputController.PlayerAdapter {
        boolean offloadEnabled;
        int disableOffloadCalls;
        int prepareCalls;
        int releaseCalls;

        FakePlayer(boolean offloadEnabled) {
            this.offloadEnabled = offloadEnabled;
        }

        @Override
        public boolean isAudioOffloadEnabled() {
            return offloadEnabled;
        }

        @Override
        public void setAudioOffloadEnabled(boolean enabled) {
            offloadEnabled = enabled;
            if (!enabled) disableOffloadCalls++;
        }

        @Override
        public void prepare() {
            prepareCalls++;
        }

        @Override
        public void release() {
            releaseCalls++;
        }
    }
}
