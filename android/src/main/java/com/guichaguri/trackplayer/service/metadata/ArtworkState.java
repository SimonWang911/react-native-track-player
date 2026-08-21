package com.guichaguri.trackplayer.service.metadata;

import android.graphics.Bitmap;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.bumptech.glide.request.target.CustomTarget;

final class ArtworkState {
    enum Status {
        IDLE,
        LOADING,
        READY,
        FAILED
    }

    static final class Begin {
        final long generation;
        final CustomTarget<Bitmap> previousTarget;

        Begin(long generation, @Nullable CustomTarget<Bitmap> previousTarget) {
            this.generation = generation;
            this.previousTarget = previousTarget;
        }
    }

    private long generation;
    private Uri uri;
    private CustomTarget<Bitmap> target;
    private Bitmap bitmap;
    private Status status = Status.IDLE;

    synchronized Begin begin(Uri nextUri, CustomTarget<Bitmap> nextTarget) {
        CustomTarget<Bitmap> previousTarget = target;
        generation++;
        uri = nextUri;
        target = nextTarget;
        bitmap = null;
        status = Status.LOADING;
        return new Begin(generation, previousTarget);
    }

    synchronized CustomTarget<Bitmap> invalidate() {
        CustomTarget<Bitmap> previousTarget = target;
        generation++;
        uri = null;
        target = null;
        bitmap = null;
        status = Status.IDLE;
        return previousTarget;
    }

    synchronized boolean canReuse(Uri artwork) {
        return sameUri(uri, artwork)
                && target != null
                && (status == Status.LOADING || status == Status.READY);
    }

    synchronized boolean owns(
            long callbackGeneration,
            Uri callbackUri,
            CustomTarget<Bitmap> callbackTarget
    ) {
        return generation == callbackGeneration
                && sameUri(uri, callbackUri)
                && target == callbackTarget;
    }

    synchronized boolean acceptReady(
            long callbackGeneration,
            Uri callbackUri,
            CustomTarget<Bitmap> callbackTarget,
            Bitmap resource
    ) {
        if (!owns(callbackGeneration, callbackUri, callbackTarget)) return false;
        bitmap = resource;
        status = Status.READY;
        return true;
    }

    synchronized boolean acceptFailure(
            long callbackGeneration,
            Uri callbackUri,
            CustomTarget<Bitmap> callbackTarget
    ) {
        if (!owns(callbackGeneration, callbackUri, callbackTarget)) return false;
        bitmap = null;
        target = null;
        status = Status.FAILED;
        return true;
    }

    synchronized boolean acceptCleared(
            long callbackGeneration,
            Uri callbackUri,
            CustomTarget<Bitmap> callbackTarget
    ) {
        if (!owns(callbackGeneration, callbackUri, callbackTarget)) return false;
        bitmap = null;
        target = null;
        status = Status.IDLE;
        return true;
    }

    synchronized Uri getUri() {
        return uri;
    }

    synchronized Bitmap getBitmap() {
        return bitmap;
    }

    private static boolean sameUri(@Nullable Uri left, @Nullable Uri right) {
        return left == null ? right == null : left.equals(right);
    }
}
