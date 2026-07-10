package com.guichaguri.trackplayer.service.source;

import android.content.Context;
import android.os.Bundle;
import androidx.media3.datasource.DataSource;

public interface TrackDataSourceFactoryProvider {
    DataSource.Factory wrap(
            Context context,
            Bundle originalTrack,
            DataSource.Factory upstream);
}
