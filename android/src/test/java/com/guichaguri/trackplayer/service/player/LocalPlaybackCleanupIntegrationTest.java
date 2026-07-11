package com.guichaguri.trackplayer.service.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.exoplayer.ExoPlayer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class LocalPlaybackCleanupIntegrationTest {

    @Test
    public void retriesUnfinishedCleanupStagesWithoutRepeatingSuccessfulStages() {
        RecordingExoPlayer player = new RecordingExoPlayer();
        player.listenerRemovalFailures = 1;
        player.releaseFailures = 1;
        RecordingCache cacheResource = new RecordingCache();
        cacheResource.releaseFailures = 1;
        PlaybackCache cache = new PlaybackCache(
                RuntimeEnvironment.getApplication(),
                1_024,
                (context, maxSize) -> cacheResource.value
        );
        LocalPlayback playback = new LocalPlayback(
                RuntimeEnvironment.getApplication(),
                new PlaybackEventHandler() {},
                player.value,
                cache,
                false,
                true
        );
        playback.initialize();

        RuntimeException firstFailure = assertThrows(RuntimeException.class, playback::destroy);

        assertEquals("player listener cleanup failed", firstFailure.getMessage());
        assertEquals(2, firstFailure.getSuppressed().length);
        assertEquals(1, player.removeAnalyticsListenerCalls);
        assertEquals(1, player.removeListenerCalls);
        assertEquals(1, player.releaseCalls);
        assertEquals(1, cacheResource.releaseCalls);

        playback.destroy();
        playback.destroy();

        assertEquals(1, player.removeAnalyticsListenerCalls);
        assertEquals(2, player.removeListenerCalls);
        assertEquals(2, player.releaseCalls);
        assertEquals(2, cacheResource.releaseCalls);
    }

    static final class RecordingCache implements InvocationHandler {
        final Cache value = (Cache)Proxy.newProxyInstance(
                Cache.class.getClassLoader(),
                new Class<?>[] { Cache.class },
                this
        );
        int releaseFailures;
        int releaseCalls;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("release")) {
                releaseCalls++;
                if (releaseFailures > 0) {
                    releaseFailures--;
                    throw new IllegalStateException("cache cleanup failed");
                }
                return null;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            return null;
        }
    }

    static final class RecordingExoPlayer implements InvocationHandler {
        final ExoPlayer value = (ExoPlayer)Proxy.newProxyInstance(
                ExoPlayer.class.getClassLoader(),
                new Class<?>[] { ExoPlayer.class },
                this
        );
        int listenerRemovalFailures;
        int analyticsAttachmentFailures;
        int releaseFailures;
        int addAnalyticsListenerCalls;
        int removeAnalyticsListenerCalls;
        int removeListenerCalls;
        int releaseCalls;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("addListener")) return null;
            if (name.equals("addAnalyticsListener")) {
                addAnalyticsListenerCalls++;
                if (analyticsAttachmentFailures > 0) {
                    analyticsAttachmentFailures--;
                    throw new IllegalStateException("analytics listener activation failed");
                }
                return null;
            }
            if (name.equals("removeAnalyticsListener")) {
                removeAnalyticsListenerCalls++;
                return null;
            }
            if (name.equals("removeListener")) {
                removeListenerCalls++;
                if (listenerRemovalFailures > 0) {
                    listenerRemovalFailures--;
                    throw new IllegalStateException("player listener cleanup failed");
                }
                return null;
            }
            if (name.equals("release")) {
                releaseCalls++;
                if (releaseFailures > 0) {
                    releaseFailures--;
                    throw new IllegalStateException("player cleanup failed");
                }
                return null;
            }
            if (name.equals("getMediaItemCount")) return 0;
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return C.INDEX_UNSET;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0F;
            if (returnType == double.class) return 0D;
            return null;
        }
    }
}
