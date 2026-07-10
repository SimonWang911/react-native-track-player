package com.guichaguri.trackplayer.service.errors;

import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.HttpDataSource;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PlaybackErrorClassifierRegistry {
    private static final CopyOnWriteArrayList<PlaybackErrorClassifier> classifiers =
            new CopyOnWriteArrayList<>();

    private PlaybackErrorClassifierRegistry() {}

    public static void add(PlaybackErrorClassifier classifier) {
        if (classifier != null) classifiers.addIfAbsent(classifier);
    }

    public static void clear() {
        classifiers.clear();
    }

    public static StructuredPlaybackError classify(Throwable error) {
        Throwable current = error;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        while (current != null && visited.add(current)) {
            for (PlaybackErrorClassifier classifier : classifiers) {
                StructuredPlaybackError classified = classifier.classify(current);
                if (classified != null) return classified;
            }
            current = current.getCause();
        }

        current = error;
        visited.clear();
        while (current != null && visited.add(current)) {
            if (current instanceof HttpDataSource.HttpDataSourceException) {
                return fallback("playback-source", current, "source", "http_error", true);
            }
            if (isDecoderError(current)) {
                return fallback("playback-renderer", current, "decoder", "decoder_error", false);
            }
            current = current.getCause();
        }

        return fallback("playback", error, "unknown", "unknown", false);
    }

    private static boolean isDecoderError(Throwable error) {
        if (!(error instanceof PlaybackException)) return false;
        int code = ((PlaybackException) error).errorCode;
        return code == PlaybackException.ERROR_CODE_DECODING_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
    }

    private static StructuredPlaybackError fallback(
            String code,
            Throwable error,
            String domain,
            String reason,
            boolean recoverable) {
        String message = error == null || error.getMessage() == null
                ? "Playback error"
                : error.getMessage();
        return new StructuredPlaybackError(code, message, domain, reason, recoverable);
    }
}
