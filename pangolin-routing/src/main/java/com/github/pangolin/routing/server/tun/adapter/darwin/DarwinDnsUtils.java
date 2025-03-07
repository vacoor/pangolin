package com.github.pangolin.routing.server.tun.adapter.darwin;

import com.github.pangolin.routing.server.tun.adapter.darwin.jna.CoreFoundation2;
import com.github.pangolin.routing.server.tun.adapter.darwin.jna.SystemConfiguration;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.CoreFoundation2.CFRunLoopRef;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.CoreFoundation2.CFRunLoopSourceRef;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.SystemConfiguration.SCDynamicStoreCallBack;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.SystemConfiguration.SCDynamicStoreRef;
import static com.sun.jna.platform.mac.CoreFoundation.*;

@Slf4j
public class DarwinDnsUtils {
    private static final CoreFoundation2 CF = CoreFoundation2.INSTANCE;
    private static final SystemConfiguration SC = SystemConfiguration.INSTANCE;

    private static String getPrimaryServiceID(final SCDynamicStoreRef store) {
        final CFDictionaryRef globalIPv4 = SC.SCDynamicStoreCopyValue(store, CFSTR("State:/Network/Global/IPv4"));
        if (null != globalIPv4) {
            try {
                // interfaceName = CF.CFDictionaryGetValue(globalIPv4, CFSTR("PrimaryInterface"));
                CFStringRef serviceId = new CFStringRef(CF.CFDictionaryGetValue(globalIPv4, CFSTR("PrimaryService")));
                return serviceId.stringValue();
            } finally {
                CF.CFRelease(globalIPv4);
            }
        }
        return null;
    }

    private static List<String> getDnsServiceIds(final SCDynamicStoreRef store) {
        final List<String> serviceKeys = newArrayList();
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

    private static List<String> getGlobalDnsServers(final SCDynamicStoreRef store) {
        final List<String> servers = newArrayList();
        final CFStringRef dnsKey = CFStringRef.createCFString("State:/Network/Global/DNS");
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


    private static final String GLOBAL_DNS_KEY = "State:/Network/Global/DNS";
    private static final String GLOBAL_IPV4_KEY = "State:/Network/Global/IPv4";

    private static final String SERVICE_ID_DNS_KEY = "State:/Network/Service/%s/DNS";
    private static final Pattern SERVICE_ID_PATTERN = Pattern.compile("^State:/Network/Service/([-a-zA-Z0-9]+)/.*");

    private static String extractServiceId(final String key) {
        final Matcher matcher = SERVICE_ID_PATTERN.matcher(key);
        return matcher.find() ? matcher.group(1) : null;
    }


    private static boolean addDns0(final SCDynamicStoreRef store, final String serviceId, final String[] dnsServers) {
        final List<String> dns = getDns0(store, serviceId);
        final List<String> dnsToUse = newArrayList();

        for (String dnsServer : dnsServers) {
            if (!dns.contains(dnsServer)) {
                dnsToUse.add(dnsServer);
            }
        }

        dnsToUse.addAll(dns);
        return setDns0(store, serviceId, dnsToUse);
    }

    private static boolean removeDns0(final SCDynamicStoreRef store, final String serviceId, final String[] dnsServers) {
        final List<String> dns = getDns0(store, serviceId);
        final List<String> dnsToUse = newArrayList(dns);

        boolean found = false;
        for (String dnsServer : dnsServers) {
            while (dnsToUse.remove(dnsServer)) {
                found = true;
            }
        }
        return found && setDns0(store, serviceId, dnsToUse);
    }

    private static List<String> getDns0(final SCDynamicStoreRef store, final String serviceID) {
        final List<String> dnsToUse = newArrayList();
        final CFStringRef dnsKey = CFStringRef.createCFString(String.format(SERVICE_ID_DNS_KEY, serviceID));
        final CFDictionaryRef dnsDictionary = SC.SCDynamicStoreCopyValue(store, dnsKey);
        if (null != dnsDictionary) {
            if (CF.CFDictionaryGetTypeID().equals(CF.CFGetTypeID(dnsDictionary))) {
                final Pointer ptr = CF.CFDictionaryGetValue(dnsDictionary, CFSTR("ServerAddresses"));
                if (null != ptr && CF.CFArrayGetTypeID().equals(CF.CFGetTypeID(ptr))) {
                    final CFArrayRef serverAddresses = new CFArrayRef(ptr);
//                    System.out.printf("DNS 服务器地址（服务键：%s）:\n", dnsKey.stringValue());

                    int count = CF.CFArrayGetCount(serverAddresses).intValue();
                    for (int j = 0; j < count; j++) {
                        final Pointer p = CF.CFArrayGetValueAtIndex(serverAddresses, new CFIndex(j));
                        CFStringRef dnsEntry = new CFStringRef(p);
//                        System.out.printf("• %s\n", dnsEntry.stringValue());
                        dnsToUse.add(dnsEntry.stringValue());
                    }
                }
            }
            CF.CFRelease(dnsDictionary);
        }
        CF.CFRelease(dnsKey);
        return dnsToUse;
    }

    private static boolean setDns0(final SCDynamicStoreRef store, final String serviceID, final List<String> dnsServers) {
        // 构建 DNS 配置键路径
        final CFStringRef dnsKey = CFSTR(String.format(SERVICE_ID_DNS_KEY, serviceID));

        // 定义 DNS 服务器地址
        final Memory memory = new Memory(Native.POINTER_SIZE * dnsServers.size());
        for (int i = 0; i < dnsServers.size(); i++) {
            final String server = dnsServers.get(i);
            memory.setPointer(i * Native.POINTER_SIZE, CFStringRef.createCFString(server).getPointer());
        }
        final CFArrayRef dnsAddresses = CF.CFArrayCreate(null, memory, new CFIndex(dnsServers.size()), null);

        // 构建 DNS 配置字典
        final CFMutableDictionaryRef dnsDict = CF.CFDictionaryCreateMutable(null, new CFIndex(0), null, null);
        CF.CFDictionarySetValue(dnsDict, CFSTR("ServerAddresses"), dnsAddresses);

        // 应用配置(仅内存有效).
        return SC.SCDynamicStoreSetValue(store, dnsKey, dnsDict);
    }

    private boolean flushDnsCache0(final SCDynamicStoreRef store) {
        // 通知系统网络配置更新，间接触发缓存刷新.(默认修改了DNS就会触发, 不需要调用)
        return SC.SCDynamicStoreNotifyValue(store, CFSTR("State:/Network/Global/DNS"));
    }


    private static Thread watchInBackground(final String[] patterns, final SCDynamicStoreCallBack callback) {
        final Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                watch0(patterns, callback);
            }
        });
        worker.setDaemon(true);
        return worker;
    }

    private static void watch0(final String[] patterns, final SCDynamicStoreCallBack callback) {
        final SCDynamicStoreRef store = SC.SCDynamicStoreCreate(null, CFSTR("WATCHER"), callback, null);

        // 构建监听KEY.
        final Memory memory = new Memory(Native.POINTER_SIZE * patterns.length);
        for (int i = 0; i < patterns.length; i++) {
            memory.setPointer(Native.POINTER_SIZE * i, CFSTR(patterns[i]).getPointer());
        }

        final CFArrayRef patternsToWatch = CF.CFArrayCreate(null, memory, new CFIndex(patterns.length), null);

        // 设置监听的键
        SC.SCDynamicStoreSetNotificationKeys(store, null, patternsToWatch);
        CF.CFRelease(patternsToWatch);

        final CFRunLoopRef rl = CF.CFRunLoopGetCurrent();
        final CFRunLoopSourceRef rlSource = SC.SCDynamicStoreCreateRunLoopSource(null, store, new CFIndex(0));

        // 绑定到 RunLoop
        CF.CFRunLoopAddSource(rl, rlSource, CFStringRef.createCFString("kCFRunLoopDefaultMode").getPointer());

        // 启动事件循环.
        CF.CFRunLoopRun();

        CF.CFRelease(rlSource);
        CF.CFRelease(store);
    }

    private static Thread cleaner(final AtomicReference<String> serviceId, final String[] dnsServers) {
        final String dnsServersStr = Arrays.toString(dnsServers);
        return new Thread() {
            @Override
            public void run() {
                final String serviceIdToUse = serviceId.get();
                if (null == serviceIdToUse || serviceIdToUse.isEmpty()) {
                    log.info("• Cleanup DNS {}: SKIP, no ServiceID hold", dnsServersStr);
                    return;
                }

                final SCDynamicStoreRef store = SC.SCDynamicStoreCreate(null, CFSTR("DNS-CLEANER@" + dnsServersStr), null, null);
                try {
                    final boolean cleanup = removeDns0(store, serviceIdToUse, dnsServers);
                    log.info("• Cleanup DNS {}: {}", dnsServersStr, cleanup);
                } finally {
                    CF.CFRelease(store);
                }
            }
        };
    }

    public static void addDnsServerAndCleanupOnShutdown(final String[] dns) {
        final String dnsStr = Arrays.toString(dns);
        final AtomicReference<String> holder = new AtomicReference<>();
        final SCDynamicStoreRef store = SC.SCDynamicStoreCreate(null, CFSTR("DNS"), null, null);
        try {
            final String sid = getPrimaryServiceID(store);
            // 获取当前活动网络接口服务 ID
            if (addDns0(store, sid, dns)) {
                holder.set(sid);
            } else {
                log.warn("• ADD DNS {} to SERVICE({}): FAILED", sid, dnsStr);
            }

            watchInBackground(new String[]{GLOBAL_IPV4_KEY}, new SCDynamicStoreCallBack() {
                @Override
                public void invoke(final SCDynamicStoreRef store, final CFArrayRef changedKeys, final Pointer info) {
                    final String prev = holder.get();
                    final String primary = getPrimaryServiceID(store);
                    if (null == primary) {
                        holder.set(null);
                        log.warn("• Network Interface {} DOWN", prev);
                    } else {
                        if (addDns0(store, primary, dns)) {
                            holder.set(primary);
                            log.info("• Add DNS {} to SERVICE({}): OK", dnsStr, primary);
                        } else {
                            holder.set(null);
                            log.info("• Add DNS {} to SERVICE({}): FAILED", dnsStr, primary);
                        }
                    }
                }
            }).start();

            Runtime.getRuntime().addShutdownHook(cleaner(holder, dns));
        } finally {
            CF.CFRelease(store);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // addDnsServerAndCleanupOnShutdown("127.0.0.1");
        /*
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                watchInNewThread();
            }
        });
        t.setDaemon(false);
        t.start();
        t.join();
        System.out.println("Over");
        LockSupport.park();
        */
//        watchInNewThread();
//        printUTUNServiceIDs();
    }


    private static CFStringRef CFSTR(final String str) {
        return CFStringRef.createCFString(str);
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

    private static <T> ArrayList<T> newArrayList() {
        return new ArrayList<>();
    }

    private static <T> ArrayList<T> newArrayList(Iterable<T> elements) {
        final ArrayList<T> ret = newArrayList();
        for (final T element : elements) {
            ret.add(element);
        }
        return ret;
    }
}