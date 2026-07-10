package com.guichaguri.trackplayer.service.errors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.os.Bundle;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import java.io.IOException;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class PlaybackErrorClassifierRegistryTest {
    @After
    public void clearClassifiers() {
        PlaybackErrorClassifierRegistry.clear();
    }

    @Test
    public void registeredClassifierMapsCustomIOExceptionToStructuredFields() {
        PlaybackErrorClassifierRegistry.add(error -> {
            if (!(error instanceof CustomIOException)) return null;
            return new StructuredPlaybackError(
                    "playback-source",
                    error.getMessage(),
                    "source",
                    "custom_io",
                    true);
        });
        PlaybackException wrapped = new PlaybackException(
                "wrapped",
                new CustomIOException("custom failure"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED);

        StructuredPlaybackError classified = PlaybackErrorClassifierRegistry.classify(wrapped);

        assertEquals("playback-source", classified.getCode());
        assertEquals("custom failure", classified.getMessage());
        assertEquals("source", classified.getDomain());
        assertEquals("custom_io", classified.getReason());
        assertTrue(classified.isRecoverable());
    }

    @Test
    public void unclassifiedHttpDecoderAndUnknownErrorsKeepDistinctDomains() {
        DataSpec dataSpec = new DataSpec(Uri.parse("https://example.invalid/audio"));
        HttpDataSource.HttpDataSourceException httpError =
                new HttpDataSource.HttpDataSourceException(
                        new IOException("http failure"),
                        dataSpec,
                        HttpDataSource.HttpDataSourceException.TYPE_OPEN);
        PlaybackException decoderError = new PlaybackException(
                "decoder failure",
                null,
                PlaybackException.ERROR_CODE_DECODING_FAILED);

        StructuredPlaybackError http = PlaybackErrorClassifierRegistry.classify(httpError);
        StructuredPlaybackError decoder = PlaybackErrorClassifierRegistry.classify(decoderError);
        StructuredPlaybackError unknown =
                PlaybackErrorClassifierRegistry.classify(new IllegalStateException("unknown failure"));

        assertEquals("source", http.getDomain());
        assertEquals("http_error", http.getReason());
        assertTrue(http.isRecoverable());
        assertEquals("decoder", decoder.getDomain());
        assertEquals("decoder_error", decoder.getReason());
        assertFalse(decoder.isRecoverable());
        assertEquals("unknown", unknown.getDomain());
        assertEquals("unknown", unknown.getReason());
        assertFalse(unknown.isRecoverable());
    }

    @Test
    public void structuredPayloadRetainsLegacyFieldsAndAddsClassification() {
        StructuredPlaybackError error = new StructuredPlaybackError(
                "playback-source",
                "source failure",
                "source",
                "custom_io",
                true);

        Bundle payload = error.toBundle();

        assertEquals("playback-source", payload.getString("code"));
        assertEquals("source failure", payload.getString("message"));
        assertEquals("source", payload.getString("domain"));
        assertEquals("custom_io", payload.getString("reason"));
        assertTrue(payload.getBoolean("recoverable"));
    }

    private static final class CustomIOException extends IOException {
        CustomIOException(String message) {
            super(message);
        }
    }
}
