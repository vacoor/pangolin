package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

import com.sun.jna.*;

import static com.sun.jna.platform.mac.CoreFoundation.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.CoreFoundation2.*;

public interface SystemConfiguration extends Library {

    SystemConfiguration INSTANCE = Native.load("SystemConfiguration", SystemConfiguration.class);

    interface SCDynamicStoreCallBack extends Callback {

        void invoke(SCDynamicStoreRef store, CFArrayRef changedKeys, Pointer info);

    }

    class SCDynamicStoreRef extends CFTypeRef {
    }

    // 获取动态存储引用
    SCDynamicStoreRef SCDynamicStoreCreate(CFAllocatorRef allocator, CFStringRef name, SCDynamicStoreCallBack callback, Pointer context);

    // 获取所有 DNS 服务键
    CFArrayRef SCDynamicStoreCopyKeyList(SCDynamicStoreRef store, CFStringRef pattern);

    // 获取具体 DNS 配置字典
    CFDictionaryRef SCDynamicStoreCopyValue(SCDynamicStoreRef store, CFStringRef key);

    boolean SCDynamicStoreSetValue(SCDynamicStoreRef store, CFStringRef key, PointerType value);

    boolean SCDynamicStoreNotifyValue(SCDynamicStoreRef store, CFStringRef key);

    boolean SCDynamicStoreSetNotificationKeys(SCDynamicStoreRef store, final CFArrayRef keys, final CFArrayRef patterns);

    CFRunLoopSourceRef SCDynamicStoreCreateRunLoopSource(CFAllocatorRef allocator, SCDynamicStoreRef store, CFIndex order);

}