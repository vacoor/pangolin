package com.github.pangolin.routing.acceptor.tun.adapter.windows;

import com.google.common.base.Joiner;
import com.sun.jna.Native;
import com.sun.jna.Platform;

import java.io.File;
import java.io.IOException;

public class WintunLoader {
    public static final String PREFER_SYSTEM = "pref_system";
    public static final String PREFER_BUNDLED = "pref_bundled";

    private static final String RESOURCE_PREFIX = "/META-INF/native/";

    public static void loadLibrary(final Class<?> clazz, final String name, final String mode) {
        if (PREFER_SYSTEM.equals(mode)) {
            try {
                loadSystemLibrary(clazz, name);
            } catch (final UnsatisfiedLinkError ex) {
                loadClassPathLibrary(clazz, name);
            }
        } else if (PREFER_BUNDLED.equals(mode)) {
            loadClassPathLibrary(clazz, name);
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }

    public static void loadSystemLibrary(final Class<?> clazz, final String name) {
        Native.register(clazz, name);
    }

    public static void loadClassPathLibrary(final Class<?> clazz, final String name) {
        final String arch = getNativeLibraryArch();
        final String pathInClassPath = Joiner.on("/").join(RESOURCE_PREFIX, name, arch, name + ".dll");

        try {
            final File tempFile = Native.extractFromResourcePath(pathInClassPath, clazz.getClassLoader());
            Native.register(clazz, tempFile.getAbsolutePath());
        } catch (final IOException ex) {
            throw new UnsatisfiedLinkError("Could not load Wintun library from classpath: " + pathInClassPath);
        }
    }

    private static String getNativeLibraryArch() {
        if (!Platform.isWindows()) {
            throw new UnsupportedOperationException(WintunLoader.class.getSimpleName() + " only support Windows");
        }

        final boolean is64Bit = Native.POINTER_SIZE == 8;
        if (Platform.isARM()) {
            return is64Bit ? "arm64" : "arm";
        } else {
            return is64Bit ? "amd64" : "x86";
        }
    }

}
