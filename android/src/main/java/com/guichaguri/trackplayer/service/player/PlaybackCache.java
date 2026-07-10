package com.guichaguri.trackplayer.service.player;

import android.content.Context;

import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;
import java.util.NavigableSet;

public final class PlaybackCache {
    private final Context context;
    private final long maxSize;
    private SimpleCache cache;
    private int references;

    public PlaybackCache(Context context, long maxSize) {
        this.context = context.getApplicationContext();
        this.maxSize = maxSize;
    }

    public synchronized void acquire() {
        if (maxSize > 0 && cache == null) {
            File cacheDir = new File(context.getFilesDir(), "TrackPlayer");
            DatabaseProvider database = new StandaloneDatabaseProvider(context);
            cache = new SimpleCache(cacheDir, new LeastRecentlyUsedCacheEvictor(maxSize), database);
        }
        references++;
    }

    public synchronized void release() {
        if (references == 0) return;
        references--;
        if (references == 0 && cache != null) {
            cache.release();
            cache = null;
        }
    }

    public synchronized DataSource.Factory enableCaching(DataSource.Factory upstream) {
        if (cache == null || maxSize <= 0) return upstream;
        return new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    public synchronized boolean isCached(String url) {
        if (cache == null) return false;
        NavigableSet<CacheSpan> spans = cache.getCachedSpans(url);
        return !spans.isEmpty();
    }

    public synchronized long getSize() {
        return cache == null ? 0 : cache.getCacheSpace();
    }

    public synchronized void clear() throws Exception {
        if (cache == null) return;
        for (String key : cache.getKeys()) cache.removeResource(key);
    }
}
