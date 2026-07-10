package com.guichaguri.trackplayer.service.source;

import android.content.Context;
import android.os.Bundle;
import androidx.media3.datasource.DataSource;

public final class TrackDataSourceFactoryRegistry {
    private static volatile TrackDataSourceFactoryProvider provider;

    private TrackDataSourceFactoryRegistry() {}

    public static void setProvider(TrackDataSourceFactoryProvider nextProvider) {
        provider = nextProvider;
    }

    public static DataSource.Factory wrap(
            Context context,
            Bundle originalTrack,
            DataSource.Factory upstream) {
        TrackDataSourceFactoryProvider currentProvider = provider;
        return currentProvider == null
                ? upstream
                : currentProvider.wrap(context, originalTrack, upstream);
    }
}
