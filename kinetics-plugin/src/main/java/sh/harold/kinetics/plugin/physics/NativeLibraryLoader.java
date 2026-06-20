package sh.harold.kinetics.plugin.physics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

final class NativeLibraryLoader {
    private static final String VERSION = "5.1.0";

    private NativeLibraryLoader() {
    }

    static void load(Path dataDirectory) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) {
            throw new IOException("Kinetics supports x86-64 Jolt natives only, not " + arch);
        }

        String resource;
        String filename;
        if (os.contains("win")) {
            resource = "/windows/x86-64/com/github/stephengold/joltjni.dll";
            filename = "joltjni.dll";
        } else if (os.contains("linux")) {
            resource = "/linux/x86-64/com/github/stephengold/libjoltjni.so";
            filename = "libjoltjni.so";
        } else {
            throw new IOException("Kinetics supports Linux and Windows only, not " + os);
        }

        Path targetDirectory = dataDirectory.resolve("native").resolve(VERSION);
        Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve(filename);
        Path temporary = targetDirectory.resolve(filename + ".tmp");
        try (InputStream stream = NativeLibraryLoader.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Bundled Jolt native is missing: " + resource);
            }
            Files.copy(stream, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            System.load(target.toAbsolutePath().toString());
        } catch (UnsatisfiedLinkError error) {
            throw new IOException("Could not load Jolt JNI; native plugin reloads require a server restart", error);
        }
    }
}
