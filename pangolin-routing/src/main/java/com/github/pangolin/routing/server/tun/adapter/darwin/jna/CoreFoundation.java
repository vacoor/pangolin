package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Core Foundation framework.
 *
 * @see <a href="https://developer.apple.com/documentation/corefoundation/">Core Foundation</a>
 */
public interface CoreFoundation extends com.sun.jna.platform.mac.CoreFoundation {

    /**
     * CoreFoundation instance.
     */
    CoreFoundation INSTANCE = Native.load("CoreFoundation", CoreFoundation.class);

    /**
     * A reference to a run loop object.
     *
     * @see <a href="https://developer.apple.com/documentation/corefoundation/cfrunloopref">CFRunLoopRef</a>
     */
    class CFRunLoopRef extends CFTypeRef {
    }

    /**
     * A reference to a run loop source object.
     *
     * @see <a href="https://developer.apple.com/documentation/corefoundation/cfrunloopsourceref">CFRunLoopSourceRef</a>
     */
    class CFRunLoopSourceRef extends CFTypeRef {
    }

    /**
     * Returns the type identifier for the CFRunLoop opaque type.
     *
     * @return The type identifier for the CFRunLoop opaque type.
     * @see <a href="https://developer.apple.com/documentation/corefoundation/1543626-cfrunloopgettypeid">CFRunLoopGetTypeID()</a>
     */
    CFTypeID CFRunLoopGetTypeID();

    /**
     * Returns the type identifier of the CFRunLoopSource opaque type.
     *
     * @return The type identifier for the CFRunLoopSource opaque type.
     * @see <a href="https://developer.apple.com/documentation/corefoundation/1541979-cfrunloopsourcegettypeid">CFRunLoopSourceGetTypeID()</a>
     */
    CFTypeID CFRunLoopSourceGetTypeID();

    /**
     * Returns the CFRunLoop object for the current thread.
     *
     * @return current thread’s run loop
     * @see <a href="https://developer.apple.com/documentation/corefoundation/1542428-cfrunloopgetcurrent">CFRunLoopGetCurrent()</a>
     */
    CFRunLoopRef CFRunLoopGetCurrent();

    /**
     * Adds a CFRunLoopSource object to a run loop mode.
     *
     * @param rl     The run loop to modify
     * @param source The run loop source to add. The source is retained by the run loop
     * @param mode   The run loop mode to which to add source
     * @see <a href="https://developer.apple.com/documentation/corefoundation/1543356-cfrunloopaddsource">CFRunLoopAddSource(_:_:_:)</a>
     */
    void CFRunLoopAddSource(
            final CFRunLoopRef rl,
            final CFRunLoopSourceRef source,
            final Pointer mode
    );

    /**
     * Runs the current thread’s CFRunLoop object in its default mode indefinitely.
     *
     * @see <a href="https://developer.apple.com/documentation/corefoundation/1542011-cfrunlooprun">CFRunLoopRun()</a>
     */
    void CFRunLoopRun();

}
