package com.guichaguri.trackplayer.service.player;

import android.content.Context;

import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;
import java.util.NavigableSet;

public final class PlaybackCache {
    interface CacheFactory {
        Cache create(Context context, long maxSize);
    }

    private final Context context;
    private final long maxSize;
    private final CacheFactory cacheFactory;
    private Cache cache;
    private int references;

    public PlaybackCache(Context context, long maxSize) {
        this(context, maxSize, PlaybackCache::createCache);
    }

    PlaybackCache(Context context, long maxSize, CacheFactory cacheFactory) {
        this.context = context.getApplicationContext();
        this.maxSize = maxSize;
        this.cacheFactory = cacheFactory;
    }

    private static Cache createCache(Context context, long maxSize) {
        File cacheDir = new File(context.getFilesDir(), "TrackPlayer");
        DatabaseProvider database = new StandaloneDatabaseProvider(context);
        return new SimpleCache(cacheDir, new LeastRecentlyUsedCacheEvictor(maxSize), database);
    }

    public synchronized void acquire() {
        if (maxSize > 0 && cache == null) {
            cache = cacheFactory.create(context, maxSize);
        }
        references++;
    }

    public synchronized void release() {
        if (references == 0) return;
        if (references > 1) {
            references--;
            return;
        }
        if (cache != null) {
            cache.release();
            cache = null;
        }
        references = 0;
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
