package com.guichaguri.trackplayer.service.metadata;

import android.app.Notification;
import android.graphics.Bitmap;
import android.net.Uri;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.annotation.Nullable;

import androidx.media3.common.util.UnstableApi;

import com.bumptech.glide.request.target.CustomTarget;
import com.guichaguri.trackplayer.service.MusicService;

interface ArtworkRequestLoader {
    CustomTarget<Bitmap> load(Uri artwork, CustomTarget<Bitmap> target);

    void clear(CustomTarget<Bitmap> target);
}

interface ArtworkLoaderFactory {
    ArtworkRequestLoader create();
}

final class LazyArtworkRequestLoader implements ArtworkRequestLoader {
    private final ArtworkLoaderFactory factory;
    private volatile ArtworkRequestLoader delegate;

    LazyArtworkRequestLoader(ArtworkLoaderFactory factory) {
        this.factory = factory;
    }

    private ArtworkRequestLoader getDelegate() {
        if (delegate == null) {
            synchronized (this) {
                if (delegate == null) delegate = factory.create();
            }
        }
        return delegate;
    }

    @Override
    public CustomTarget<Bitmap> load(Uri artwork, CustomTarget<Bitmap> target) {
        return getDelegate().load(artwork, target);
    }

    @Override
    public void clear(CustomTarget<Bitmap> target) {
        getDelegate().clear(target);
    }
}

@UnstableApi
interface ArtworkRenderSink {
    void renderMetadata(MediaSessionCompat session, MediaMetadataCompat metadata);

    void renderNotification(MusicService service, Notification notification);
}
