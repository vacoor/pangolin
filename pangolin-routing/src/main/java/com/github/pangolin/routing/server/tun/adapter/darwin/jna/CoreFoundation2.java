package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;

/**
 *
 */
public interface CoreFoundation2 extends CoreFoundation {
    CoreFoundation2 INSTANCE = Native.load("CoreFoundation", CoreFoundation2.class);

    class CFRunLoopRef extends CFTypeRef {
    }
    class CFRunLoopSourceRef extends CFTypeRef {
    }

    // 获取主线程 RunLoop
    CFRunLoopRef CFRunLoopGetMain();

    CFRunLoopRef CFRunLoopGetCurrent();

    // 添加事件源到 RunLoop
    void CFRunLoopAddSource(CFRunLoopRef runLoop, CFRunLoopSourceRef source, CFStringRef modeName);

    // 启动 RunLoop
    void CFRunLoopRun();

    // 其他必要函数（如 CFString 转换）
    // Pointer CFStringCreateWithCString(Pointer allocator, String str, int encoding);

}
