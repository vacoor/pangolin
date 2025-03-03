package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.CoreFoundation2.CFRunLoopRef;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.CoreFoundation2.CFRunLoopSourceRef;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.SystemConfiguration.SCDynamicStoreCallBack;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.SystemConfiguration.SCDynamicStoreRef;
import static com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import static com.sun.jna.platform.mac.CoreFoundation.CFDictionaryRef;
import static com.sun.jna.platform.mac.CoreFoundation.CFIndex;
import static com.sun.jna.platform.mac.CoreFoundation.CFMutableDictionaryRef;
import static com.sun.jna.platform.mac.CoreFoundation.CFStringRef;

import com.google.common.collect.Lists;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SystemConfigurationTest {
    private static final CoreFoundation2 CF = CoreFoundation2.INSTANCE;
    private static final SystemConfiguration SC = SystemConfiguration.INSTANCE;


    private static String getPrimaryServiceID(final SCDynamicStoreRef store) {
        final CFDictionaryRef globalIPv4 = SC.SCDynamicStoreCopyValue(store, CFSTR("State:/Network/Global/IPv4"));
        if (null != globalIPv4) {
            try {
                // interfaceName = CF.CFDictionaryGetValue(globalIPv4, CFSTR("PrimaryInterface"));
                CFStringRef serviceId = new CFStringRef(CF.CFDictionaryGetValue(globalIPv4, CFSTR("PrimaryService")));
                String s = serviceId.stringValue();
//                CF.CFRelease(serviceId);
                return s;
            } finally {
                CF.CFRelease(globalIPv4);
            }
        }
        return null;
    }

    private static List<String> getDnsServiceIds(final SCDynamicStoreRef store) {
        final List<String> serviceKeys = Lists.newArrayList();
        final CFArrayRef dnsKeys = SC.SCDynamicStoreCopyKeyList(store, CFSTR("State:/Network/Service/.*/DNS"));
        if (null != dnsKeys) {
            for (int i = 0; i < CF.CFArrayGetCount(dnsKeys).intValue(); i++) {
                final CFStringRef dnsKey = new CFStringRef(CF.CFArrayGetValueAtIndex(dnsKeys, new CFIndex(i)));
                String serviceID = extractServiceId(dnsKey.stringValue());
                if (null != serviceID) {
                    serviceKeys.add(serviceID);
                }
            }
            CF.CFRelease(dnsKeys);
        } else {
            System.out.println("未找到任何 DNS 配置");
        }
        return serviceKeys;
    }

    static String GetServiceIDByInterface(SCDynamicStoreRef store, final String targetName) {
        final CFArrayRef serviceKeys = SC.SCDynamicStoreCopyKeyList(store, CFSTR("State:/Network/Service/.*/IPv4"));
        if (null == serviceKeys || CF.CFArrayGetCount(serviceKeys).intValue() == 0) {
            return null;
        }
        String serviceID = null;
        for (int i = 0; i < CF.CFArrayGetCount(serviceKeys).intValue(); i++) {
            final CFStringRef serviceKey = s(CF.CFArrayGetValueAtIndex(serviceKeys, new CFIndex(i)));
            final CFDictionaryRef serviceInfo = SC.SCDynamicStoreCopyValue(store, serviceKey);
            if (null == serviceInfo) continue;

            // 提取接口信息
            CFDictionaryRef interfaceInfo = d(CF.CFDictionaryGetValue(serviceInfo, CFSTR("Interface")));
            CFStringRef interfaceName;
            if (null != interfaceInfo) {
                interfaceName = s(CF.CFDictionaryGetValue(interfaceInfo, CFSTR("InterfaceName")));
                if (null == interfaceName) {
                    interfaceName = s(CF.CFDictionaryGetValue(interfaceInfo, CFSTR("BSD Name")));
                }
            } else {
                interfaceName = s(CF.CFDictionaryGetValue(serviceInfo, CFSTR("ConfirmedInterfaceName")));
                if (null == interfaceName) {
                    interfaceName = s(CF.CFDictionaryGetValue(serviceInfo, CFSTR("InterfaceName")));
                }
            }

            // 匹配目标接口并验证活动状态
            if (null != interfaceName && interfaceName.stringValue().equals(targetName)) {
                // 从键名 State:/Network/Service/[UUID]/IPv4 提取 UUID
                final String key = serviceKey.stringValue();
                final int location = key.indexOf("/IPv4");
                if (location > 0) {
                    serviceID = key.substring(location - 36, location);
                }
                CF.CFRelease(serviceInfo);
                break;
            }
            CF.CFRelease(serviceInfo);
        }

        CF.CFRelease(serviceKeys);
        return serviceID;
    }

    private static List<String> getDnsServers(final SCDynamicStoreRef store, final String serviceID) {
        final List<String> servers = Lists.newArrayList();
        final CFStringRef dnsKey = CFStringRef.createCFString(String.format(SERVICE_ID_DNS_KEY, serviceID));
        final CFDictionaryRef dnsDictionary = SC.SCDynamicStoreCopyValue(store, dnsKey);
        if (null != dnsDictionary) {
            if (CF.CFDictionaryGetTypeID().equals(CF.CFGetTypeID(dnsDictionary))) {
                final Pointer ptr = CF.CFDictionaryGetValue(dnsDictionary, CFSTR("ServerAddresses"));
                if (null != ptr && CF.CFArrayGetTypeID().equals(CF.CFGetTypeID(ptr))) {
                    final CFArrayRef serverAddresses = new CFArrayRef(ptr);
                    System.out.printf("DNS 服务器地址（服务键：%s）:\n", dnsKey.stringValue());

                    int count = CF.CFArrayGetCount(serverAddresses).intValue();
                    for (int j = 0; j < count; j++) {
                        final Pointer p = CF.CFArrayGetValueAtIndex(serverAddresses, new CFIndex(j));
                        CFStringRef dnsEntry = new CFStringRef(p);
                        System.out.printf("• %s\n", dnsEntry.stringValue());
                        servers.add(dnsEntry.stringValue());
                    }
                }
            }
            CF.CFRelease(dnsDictionary);
        }
        CF.CFRelease(dnsKey);
        return servers;
    }


    private static CFStringRef s(final Pointer ptr) {
        if (null == ptr) {
            return null;
        }
        if (CF.CFStringGetTypeID().equals(CF.CFGetTypeID(ptr))) {
            return new CFStringRef(ptr);
        }
        throw new UnsupportedOperationException();
    }

    private static CFDictionaryRef d(final Pointer ptr) {
        if (null == ptr) {
            return null;
        }
        if (CF.CFDictionaryGetTypeID().equals(CF.CFGetTypeID(ptr))) {
            return new CFDictionaryRef(ptr);
        }
        throw new UnsupportedOperationException();
    }

    private static final String SERVICE_ID_DNS_KEY = "State:/Network/Service/%s/DNS";
    private static final Pattern SERVICE_ID_PATTERN = Pattern.compile("^State:/Network/Service/([-a-zA-Z0-9]+)/.*");

    private static String extractServiceId(final String key) {
        final Matcher matcher = SERVICE_ID_PATTERN.matcher(key);
        return matcher.find() ? matcher.group(1) : null;
    }


    public static boolean setDns(final SCDynamicStoreRef store, final String serviceID, final List<String> dnsServer) {
        // 构建 DNS 配置键路径
        final CFStringRef dnsKey = CFSTR(String.format(SERVICE_ID_DNS_KEY, serviceID));

        // 定义 DNS 服务器地址
        final Memory memory = new Memory(Native.POINTER_SIZE * dnsServer.size());
        for (int i = 0; i < dnsServer.size(); i++) {
            final String server = dnsServer.get(i);
            memory.setPointer(i * Native.POINTER_SIZE, CFStringRef.createCFString(server).getPointer());
        }
        final CFArrayRef serverArray = CF.CFArrayCreate(null, memory, new CFIndex(dnsServer.size()), null);

        // 构建 DNS 配置字典
        final CFMutableDictionaryRef dnsDict = CF.CFDictionaryCreateMutable(null, new CFIndex(0), null, null);
        CF.CFDictionarySetValue(dnsDict, CFSTR("ServerAddresses"), serverArray);

        // 应用配置(仅内存有效).
        return SC.SCDynamicStoreSetValue(store, dnsKey, dnsDict);
    }


    private boolean flushDnsCache(final SCDynamicStoreRef store) {
        // 通知系统网络配置更新，间接触发缓存刷新.(默认修改了DNS就会触发, 不需要调用)
        return SC.SCDynamicStoreNotifyValue(store, CFSTR("State:/Network/Global/DNS"));
    }

    public static boolean addDns0(final String dnsServer) {
        // 创建动态存储会话
        final SCDynamicStoreRef store = SC.SCDynamicStoreCreate(null, CFSTR("DNS_READER"), null, null);

        try {
            // 获取当前活动网络接口服务 ID
            final String serviceId = getPrimaryServiceID(store);


            final List<String> dnsServers = getDnsServers(store, serviceId);

            final List<String> newDnsServers = Lists.newArrayListWithExpectedSize(1 + dnsServers.size());
            newDnsServers.add(dnsServer);
            newDnsServers.addAll(dnsServers);

            return setDns(store, serviceId, newDnsServers);
        } finally {
            CF.CFRelease(store);
        }
    }

    private static boolean removeDns0(final String dnsServer) {
        // 创建动态存储会话
        final SCDynamicStoreRef store = SC.SCDynamicStoreCreate(null, CFSTR("DNS_READER"), null, null);

        try {
            // 获取当前活动网络接口服务 ID
            final String serviceId = getPrimaryServiceID(store);

            final List<String> dnsServers = getDnsServers(store, serviceId);

            final List<String> newDnsServers = Lists.newArrayList(dnsServers);
            return newDnsServers.remove(dnsServer) && setDns(store, serviceId, newDnsServers);
        } finally {
            CF.CFRelease(store);
        }
    }

    public static void addDnsServerAndCleanupOnShutdown(final String dnsServer) {
        if (addDns0(dnsServer)) {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    boolean b = removeDns0(dnsServer);
                    System.out.println("Cleanup DNS " + dnsServer + ": " + b);
                }
            });
        } else {
            System.err.println("Add DNS fail, Please run root");
        }
    }

    private static void watchInNewThread() {
        final SCDynamicStoreCallBack callback = new SCDynamicStoreCallBack() {

            @Override
            public void callback(final SCDynamicStoreRef store, final CFArrayRef changedKeys, final Pointer info) {
                final CFIndex count = CF.CFArrayGetCount(changedKeys);
                for (int i = 0; i < count.intValue(); i++) {
                    CFStringRef key = new CFStringRef(CF.CFArrayGetValueAtIndex(changedKeys, new CFIndex(i)));
                    final String cKey = key.stringValue();
                    System.out.printf("Detected network change: %s\n", cKey);
                }
            }

        };

        final Memory memory = new Memory(Native.POINTER_SIZE);
        memory.setPointer(0, CFSTR("State:/Network/Global/DNS").getPointer());
        final CFArrayRef watchedKeys = CF.CFArrayCreate(null, memory, new CFIndex(1), null);

        // 设置监听的键
        final SCDynamicStoreRef store = SC.SCDynamicStoreCreate(null, CFSTR("NetworkMonitor"), callback, null);
        SC.SCDynamicStoreSetNotificationKeys(store, watchedKeys, null);
        CF.CFRelease(watchedKeys);

        // 绑定到 RunLoop
        // final CFRunLoopSourceRef runLoopSource = SC.SCDynamicStoreCreateRunLoopSource(null, store, 0);
        // CF.CFRunLoopAddSource(CF.CFRunLoopGetMain(), runLoopSource, kCFRunLoopCommonModes);
        // CF.CFRelease(runLoopSource);

        // final CFRunLoopRef currentRunLoop = CF.CFRunLoopGetMain();
        final CFRunLoopRef currentRunLoop = CF.CFRunLoopGetCurrent();
        final CFRunLoopSourceRef runLoopSource = SC.SCDynamicStoreCreateRunLoopSource(null, store, new CFIndex(0));
        CF.CFRunLoopAddSource(currentRunLoop, runLoopSource, CFSTR("kCFRunLoopCommonModes"));

        // 启动事件循环.
        CF.CFRunLoopRun();

        CF.CFRelease(runLoopSource);
        CF.CFRelease(store);
    }

    public static void main(String[] args) throws InterruptedException {
        // addDnsServerAndCleanupOnShutdown("127.0.0.1");
        Thread t = new Thread(SystemConfigurationTest::watchInNewThread);
        t.setDaemon(false);
        t.start();
        t.join();
    }

    private static CFStringRef CFSTR(final String str) {
        return CFStringRef.createCFString(str);
    }

}