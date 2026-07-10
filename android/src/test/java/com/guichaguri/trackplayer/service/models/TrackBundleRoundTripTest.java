package com.guichaguri.trackplayer.service.models;

import static org.junit.Assert.assertEquals;

import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TrackBundleRoundTripTest {
    @Test
    public void opaqueTransformFieldsSurviveTrackAndQueueRoundTrip() {
        Bundle input = new Bundle();
        input.putString("url", "https://example.invalid/encrypted.audio");
        input.putString("title", "Opaque Track");
        input.putString("sourceTransformType", "cipher-v2");
        input.putString("sourceTransformToken", "opaque-token");

        Track track = new Track(RuntimeEnvironment.getApplication(), input, 0);
        Bundle queued = track.toBundle();

        assertEquals("cipher-v2", queued.getString("sourceTransformType"));
        assertEquals("opaque-token", queued.getString("sourceTransformToken"));

        Bundle metadataUpdate = new Bundle();
        metadataUpdate.putString("title", "Updated Track");
        track.setMetadata(RuntimeEnvironment.getApplication(), metadataUpdate, 0);

        assertEquals("cipher-v2", track.toBundle().getString("sourceTransformType"));
        assertEquals("opaque-token", track.toBundle().getString("sourceTransformToken"));
    }
}
