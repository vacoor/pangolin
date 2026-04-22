package com.github.pangolin.routing.acceptor.tun.adapter.windows.jna;

import com.google.common.base.Joiner;
import com.sun.jna.Native;
import com.sun.jna.Platform;

import java.io.File;
import java.io.IOException;

public abstract class WintunLoader {
    public static final String PREFER_SYSTEM = "pref_system";
    public static final String PREFER_BUNDLED = "pref_bundled";

    private static final String RESOURCE_PREFIX = "/META-INF/native/";

    private WintunLoader() {
    }

    public static void loadLibrary(final Class<?> clazz, final String libName, final String mode) {
        if (PREFER_SYSTEM.equals(mode)) {
            try {
                loadSystemLibrary(clazz, libName);
            } catch (final UnsatisfiedLinkError ex) {
                loadClassPathLibrary(clazz, libName);
            }
        } else if (PREFER_BUNDLED.equals(mode)) {
            loadClassPathLibrary(clazz, libName);
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }

    /**
     * When called from a class static initializer, maps all native methods
     * found within that class to native libraries via the JNA raw calling
     * interface.  Uses the class loader of the given class to search for the
     * native library in the resource path if it is not found in the system
     * library load path or <code>jna.library.path</code>.
     *
     * @param cls     Class with native methods to register
     * @param libName name of or path to native library to which functions should be bound
     */
    public static void loadSystemLibrary(final Class<?> cls, final String libName) {
        Native.register(cls, libName);
    }

    public static void loadClassPathLibrary(final Class<?> clazz, final String name) {
        final String arch = getNativeLibraryArch();
        final String pathInClasspath = Joiner.on("/").join(RESOURCE_PREFIX, name, arch, name + ".dll");

        try {
            final File libraryFile = Native.extractFromResourcePath(pathInClasspath, clazz.getClassLoader());
            Native.register(clazz, libraryFile.getAbsolutePath());
        } catch (final IOException ex) {
            throw new UnsatisfiedLinkError("Could not load Wintun library from classpath: " + pathInClasspath);
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
