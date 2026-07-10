package com.guichaguri.trackplayer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.guichaguri.trackplayer.module.MusicEvents;
import com.guichaguri.trackplayer.service.player.AudioOutputCompatibilityEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.robolectric.Shadows.shadowOf;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class MusicServiceEventBridgeTest {

    @Test
    public void serviceEmitDeliversExactStructuredCompatibilityPayloadOnce() {
        MusicService service = Robolectric.buildService(MusicService.class).get();
        LocalBroadcastManager broadcasts = LocalBroadcastManager.getInstance(service);
        List<Intent> received = new ArrayList<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                received.add(intent);
            }
        };
        broadcasts.registerReceiver(receiver, new IntentFilter(Utils.EVENT_INTENT));
        AudioOutputCompatibilityEvent event = new AudioOutputCompatibilityEvent(true, true);

        service.emit(MusicEvents.PLAYBACK_AUDIO_OUTPUT_COMPATIBILITY, event.toBundle());
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, received.size());
        Intent intent = received.get(0);
        assertEquals(
                MusicEvents.PLAYBACK_AUDIO_OUTPUT_COMPATIBILITY,
                intent.getStringExtra("event")
        );
        Bundle payload = intent.getBundleExtra("data");
        assertNotNull(payload);
        assertEquals("audio_sink_offload_failed", payload.getString("reason"));
        assertFalse(payload.getBoolean("effectiveAudioOffload"));
        assertEquals(true, payload.getBoolean("recovered"));
        assertEquals(true, payload.getBoolean("rebuilt"));

        broadcasts.unregisterReceiver(receiver);
    }
}
