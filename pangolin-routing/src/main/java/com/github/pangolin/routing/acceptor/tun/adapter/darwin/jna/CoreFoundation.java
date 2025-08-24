package com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna;

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

    /**
     * Creates a new mutable dictionary with the key-value pairs from another dictionary.
     * <p>
     * This reference must be released with {@link #CFRelease} to avoid leaking
     * references.
     *
     * @param allocator The allocator to use to allocate memory for the new string. Pass
     *                  {@code null} or {@code kCFAllocatorDefault} to use the current
     *                  default allocator.
     * @param capacity  The maximum number of key-value pairs that can be contained by the
     *                  new dictionary. The dictionary starts empty and can grow to this
     *                  number of key-value pairs (and it can have less).
     *                  <p>
     *                  Pass 0 to specify that the maximum capacity is not limited. The
     *                  value must not be negative.
     * @param theDict   The dictionary to copy. The keys and values from the dictionary
     *                  are copied as pointers into the new dictionary, not that which
     *                  the values point to (if anything). The keys and values are also
     *                  retained by the new dictionary. The count of the new dictionary
     *                  is the same as the count of theDict. The new dictionary uses the
     *                  same callbacks as theDict.
     * @return A new dictionary that contains the same values as theDict.
     * @see <a href="https://developer.apple.com/documentation/corefoundation/cfdictionarycreatemutablecopy(_:_:_:)">CFDictionaryCreateMutableCopy(_:_:_:)</a>
     */
    CFMutableDictionaryRef CFDictionaryCreateMutableCopy(final CFAllocatorRef allocator,
                                                         final CFIndex capacity,
                                                         final CFDictionaryRef theDict);

}
