package com.guichaguri.trackplayer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.os.Bundle;

import com.facebook.react.bridge.Promise;
import com.guichaguri.trackplayer.service.player.ExoPlayback;
import com.guichaguri.trackplayer.service.player.PlaybackLifecycleController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class MusicBinderSetupIntegrationTest {

    @Test
    public void setupPromiseStaysPendingUntilLifecycleSuccess() {
        RecordingPlaybackAccess access = new RecordingPlaybackAccess();
        RecordingPromise promise = new RecordingPromise();
        MusicBinder binder = new MusicBinder(null, null, access);

        binder.setupPlayer(new Bundle(), promise.value);

        assertEquals(1, access.setupCalls);
        assertEquals(0, promise.resolveCalls);
        assertEquals(0, promise.rejectCalls);

        access.completeSuccess();

        assertEquals(1, promise.resolveCalls);
        assertEquals(0, promise.rejectCalls);
    }

    @Test
    public void lazyAccessDuringQueuedSetupThrowsDeterministicNotInitializedError() {
        RecordingPlaybackAccess access = new RecordingPlaybackAccess();
        RecordingPromise promise = new RecordingPromise();
        MusicBinder binder = new MusicBinder(null, null, access);
        binder.setupPlayer(new Bundle(), promise.value);

        assertNotInitialized(binder);
        assertNotInitialized(binder);

        assertEquals(1, access.setupCalls);
        assertEquals(0, promise.resolveCalls);
        assertEquals(0, promise.rejectCalls);
    }

    @Test
    public void activationFailureRejectsSetupWithoutNullDereference() {
        RecordingPlaybackAccess access = new RecordingPlaybackAccess();
        RecordingPromise promise = new RecordingPromise();
        MusicBinder binder = new MusicBinder(null, null, access);
        binder.setupPlayer(new Bundle(), promise.value);

        access.completeFailure(new IllegalStateException("activation failed"));

        assertEquals(0, promise.resolveCalls);
        assertEquals(1, promise.rejectCalls);
        assertEquals("player_setup_failed", promise.rejectionCode);
        assertNotNull(promise.rejectionError);
        assertEquals("activation failed", promise.rejectionError.getMessage());
        assertNotInitialized(binder);
        assertEquals(1, access.setupCalls);
    }

    private static void assertNotInitialized(MusicBinder binder) {
        try {
            binder.getPlayback();
            fail("Expected deterministic not-initialized error");
        } catch (MusicBinder.PlaybackNotInitializedException error) {
            assertEquals("The player is not initialized", error.getMessage());
        }
    }

    private static final class RecordingPlaybackAccess implements MusicBinder.PlaybackAccess {
        int setupCalls;
        PlaybackLifecycleController.SetupCallback callback;

        @Override
        public ExoPlayback getPlayback() {
            return null;
        }

        @Override
        public void setupPlayback(
                Bundle options,
                PlaybackLifecycleController.SetupCallback callback
        ) {
            setupCalls++;
            this.callback = callback;
        }

        void completeSuccess() {
            callback.onSuccess(null);
        }

        void completeFailure(RuntimeException error) {
            callback.onFailure(error);
        }
    }

    private static final class RecordingPromise implements InvocationHandler {
        final Promise value = (Promise)Proxy.newProxyInstance(
                Promise.class.getClassLoader(),
                new Class<?>[] { Promise.class },
                this
        );
        int resolveCalls;
        int rejectCalls;
        String rejectionCode;
        Throwable rejectionError;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("resolve")) {
                resolveCalls++;
                return null;
            }
            if (method.getName().equals("reject")) {
                rejectCalls++;
                if (args != null) {
                    for (Object argument : args) {
                        if (rejectionCode == null && argument instanceof String) {
                            rejectionCode = (String)argument;
                        }
                        if (argument instanceof Throwable) {
                            rejectionError = (Throwable)argument;
                        }
                    }
                }
                return null;
            }
            if (method.getName().equals("toString")) return "RecordingPromise";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            return null;
        }
    }
}
