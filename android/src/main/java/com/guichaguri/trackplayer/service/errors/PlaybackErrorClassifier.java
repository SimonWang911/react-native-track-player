package com.guichaguri.trackplayer.service.errors;

public interface PlaybackErrorClassifier {
    StructuredPlaybackError classify(Throwable error);
}
