package com.guichaguri.trackplayer.service.source;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.os.Bundle;
import androidx.media3.datasource.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TrackDataSourceFactoryRegistryTest {
    @After
    public void clearProvider() {
        TrackDataSourceFactoryRegistry.setProvider(null);
    }

    @Test
    public void plainTrackProviderReceivesCachedFactoryAndReturnsItUnchanged() {
        Context context = RuntimeEnvironment.getApplication();
        Bundle track = new Bundle();
        track.putString("url", "https://example.invalid/plain.mp3");
        DataSource.Factory network = new MarkerFactory(null);
        DataSource.Factory cached = new MarkerFactory(network);
        RecordingProvider provider = new RecordingProvider(false);
        TrackDataSourceFactoryRegistry.setProvider(provider);

        DataSource.Factory result = TrackDataSourceFactoryRegistry.wrap(context, track, cached);

        assertSame(context, provider.context);
        assertSame(track, provider.track);
        assertSame(cached, provider.upstream);
        assertSame(cached, result);
    }

    @Test
    public void transformedTrackWrapsCachedFactorySoCacheRemainsInsideTransform() {
        Context context = RuntimeEnvironment.getApplication();
        Bundle track = new Bundle();
        track.putString("sourceTransformType", "cipher-v2");
        track.putString("sourceTransformToken", "opaque-token");
        MarkerFactory network = new MarkerFactory(null);
        MarkerFactory cached = new MarkerFactory(network);
        RecordingProvider provider = new RecordingProvider(true);
        TrackDataSourceFactoryRegistry.setProvider(provider);

        MarkerFactory transformed =
                (MarkerFactory) TrackDataSourceFactoryRegistry.wrap(context, track, cached);

        assertSame(cached, transformed.upstream);
        assertSame(network, ((MarkerFactory) transformed.upstream).upstream);
    }

    @Test
    public void forkProductionSourcesRemainDomainAgnostic() throws IOException {
        Path packageRoot = findPackageRoot();
        List<Path> roots = Arrays.asList(
                packageRoot.resolve("android/src/main/java"),
                packageRoot.resolve("src"));
        String[] forbidden = {
                "qm" + "c",
                "e" + "key",
                "com." + "simon",
                "simon" + "wang",
                "raw" + "key"
        };

        for (Path root : roots) {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                    String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                            .toLowerCase(Locale.ROOT);
                    for (String value : forbidden) {
                        assertFalse(path + " contains forbidden product logic", source.contains(value));
                    }
                }
            }
        }
    }

    private static Path findPackageRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path directPackage = current.resolve("package.json");
            if (Files.isRegularFile(directPackage) && Files.isDirectory(current.resolve("android/src/main/java"))) {
                return current;
            }
            Path installed = current.resolve("node_modules/react-native-track-player");
            if (Files.isDirectory(installed.resolve("android/src/main/java"))) {
                return installed;
            }
            current = current.getParent();
        }
        throw new AssertionError("Unable to locate react-native-track-player package root");
    }

    private static final class RecordingProvider implements TrackDataSourceFactoryProvider {
        final boolean wrap;
        Context context;
        Bundle track;
        DataSource.Factory upstream;

        RecordingProvider(boolean wrap) {
            this.wrap = wrap;
        }

        @Override
        public DataSource.Factory wrap(
                Context context,
                Bundle originalTrack,
                DataSource.Factory upstream) {
            this.context = context;
            this.track = originalTrack;
            this.upstream = upstream;
            return wrap ? new MarkerFactory(upstream) : upstream;
        }
    }

    private static final class MarkerFactory implements DataSource.Factory {
        final DataSource.Factory upstream;

        MarkerFactory(DataSource.Factory upstream) {
            this.upstream = upstream;
        }

        @Override
        public DataSource createDataSource() {
            return null;
        }
    }
}
