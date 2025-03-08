package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;

import java.lang.reflect.Proxy;

/**
 *
 */
public interface CoreFoundation2 extends com.sun.jna.platform.mac.CoreFoundation {

    CoreFoundation2 INSTANCE = Native.load("CoreFoundation", CoreFoundation2.class);


    class CFRunLoopRef extends CFTypeRef {
    }

    class CFRunLoopSourceRef extends CFTypeRef {
    }


    // 获取主线程 RunLoop
    CFRunLoopRef CFRunLoopGetMain();

    CFRunLoopRef CFRunLoopGetCurrent();

    // 添加事件源到 RunLoop
    void CFRunLoopAddSource(CFRunLoopRef rl, CFRunLoopSourceRef source, Pointer mode);

    // 启动 RunLoop
    void CFRunLoopRun();

    boolean CFStringHasPrefix(CFStringRef bsdName, CFStringRef utun);

    CFTypeID CFRunLoopGetTypeID();

    CFTypeID CFRunLoopSourceGetTypeID();
}
