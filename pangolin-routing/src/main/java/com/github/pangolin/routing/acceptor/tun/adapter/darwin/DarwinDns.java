package com.github.pangolin.routing.acceptor.tun.adapter.darwin;

import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.CoreFoundation;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.SystemConfiguration;
import com.google.common.collect.Lists;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.CoreFoundation.CFRunLoopRef;
import static com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.CoreFoundation.CFRunLoopSourceRef;
import static com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.SystemConfiguration.SCDynamicStoreCallBack;
import static com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.SystemConfiguration.SCDynamicStoreRef;
import static com.sun.jna.platform.mac.CoreFoundation.*;

/**
 * Darwin system dns utilities.
 */
@Slf4j
public final class DarwinDns {
    /**
     * CoreFoundation instance;
     */
    private static final CoreFoundation CF = CoreFoundation.INSTANCE;
    /**
     * SystemConfiguration instance;
     */
    private static final SystemConfiguration SC = SystemConfiguration.INSTANCE;

    private static final String GLOBAL_DNS_KEY = "State:/Network/Global/DNS";
    private static final String GLOBAL_IPV4_KEY = "State:/Network/Global/IPv4";

    private static final String SERVICE_ID_DNS_KEY_FMT = "State:/Network/Service/%s/DNS";

    private static final String SETUP_SERVICE_ID_DNS_KEY_FMT = "Setup:/Network/Service/%s/DNS";

    private static final String SERVICE_QUERY_DNS_PATTERN_STR = "State:/Network/Service/.*/DNS";
    private static final Pattern SERVICE_ID_DNS_PATTERN = Pattern.compile("^State:/Network/Service/([-a-zA-Z0-9]+)/.*");
    private static final String SERVER_ADDRESSES = "ServerAddresses";

    /**
     * Private constructor.
     */
    private DarwinDns() {
    }

    /**
     * Adds the network global/service dns addresses.
     *
     * @param dns the dns addresses to add
     * @return true if add dns addresses is successful or unnecessary, otherwise false
     */
    public static boolean addDns(final String[] dns) {
        return addDns(dns, true);
    }

    /**
     * Adds the network global/service dns addresses.
     *
     * @param dns the dns addresses to add
     * @return true if add dns addresses is successful or unnecessary, otherwise false
     */
    public static boolean addDns(final String[] dns, final boolean cleanupOnShutdown) {
        final String name = DarwinDns.class.getSimpleName();
        final AtomicBoolean shutdownHolder = new AtomicBoolean();
        final AtomicReference<String> serviceIdHolder = new AtomicReference<>();
        final SCDynamicStoreRef store = SC.SCDynamicStoreCreate(null, CFSTR(name), null, null);
        try {
            final String primaryServiceId = getPrimaryServiceId(store);
            final List<String> serviceDns;
            if (null != primaryServiceId) {
                List<String> serviceDns1 = getServiceDns(store, primaryServiceId);
                serviceDns = null != serviceDns1 ? serviceDns1 : getServiceManualDns(store, primaryServiceId);
            } else {
                serviceDns = null;
            }

            /*-
             * service dns may not exist when manually.
             */
            if (null != serviceDns && (addServiceDns(store, primaryServiceId, dns) || addServiceManualDns(store, primaryServiceId, dns))) {
                serviceIdHolder.set(primaryServiceId);
//            } else if (!addGlobalDns(store, dns)) {
            } else {
                return false;
            }

            watchInBackground(name, new String[]{GLOBAL_IPV4_KEY}, new SCDynamicStoreCallBack() {
                @Override
                public void invoke(final SCDynamicStoreRef store, final CFArrayRef changedKeys, final Pointer info) {
                    if (Thread.currentThread().isInterrupted() || shutdownHolder.get()) {
                        return;
                    }
                    final String prevServiceId = serviceIdHolder.get();
                    final String nextServiceId = getPrimaryServiceId(store);
                    if (null == nextServiceId) {
                        serviceIdHolder.set(null);
                        if (null != prevServiceId && (removeServiceDns(store, prevServiceId, dns) || removeServiceManualDns(store, prevServiceId, dns))) {
                            log.info("• Cleanup Network Service DNS: {}", prevServiceId);
                        }

//                        if (addGlobalDns(store, dns)) {
//                            log.info("• Add Network Global DNS: {}", Arrays.toString(dns));
//                        }
                    } else if (!nextServiceId.equals(prevServiceId)) {
                        if (addServiceDns(store, nextServiceId, dns) || addServiceManualDns(store, nextServiceId, dns)) {
                            serviceIdHolder.set(nextServiceId);
                            log.info("• Add Network Service DNS: {}", nextServiceId);
//                        } else if (addGlobalDns(store, dns)) {
//                            log.info("• Add Network Global DNS: {}", Arrays.toString(dns));
                        }
                    }
                }
            }).start();

            if (cleanupOnShutdown) {
                Runtime.getRuntime().addShutdownHook(dnsCleaner(name, serviceIdHolder, shutdownHolder, dns));
            }
            return true;
        } finally {
            CF.CFRelease(store);
        }
    }


    /**
     * Watch the patterns in new daemon thread.
     *
     * @param name     the name of dynamic store session
     * @param patterns the watch patterns
     * @param callback the callback
     */
    private static Thread watchInBackground(final String name, final String[] patterns, final SCDynamicStoreCallBack callback) {
        final Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                watch(name, patterns, callback);
            }
        });
        worker.setDaemon(true);
        return worker;
    }

    private static Thread dnsCleaner(final String name,
                                     final AtomicReference<String> serviceIdHolder,
                                     final AtomicBoolean shutdownHolder, final String[] dns) {
        final String dnsAddresses = Arrays.toString(dns);
        return new Thread() {
            @Override
            public void run() {
                shutdownHolder.set(true);
                final SCDynamicStoreRef store = SC.SCDynamicStoreCreate(
                        null, CFSTR(name + "-CLEANER@" + dnsAddresses), null, null
                );
                try {
                    final String serviceId = serviceIdHolder.get();
                    if (null != serviceId) {
                        if (removeServiceDns(store, serviceId, dns) || removeServiceManualDns(store, serviceId, dns)) {
                            log.info("• Cleanup Network Service DNS ({}) -> {}: OK", serviceId, dnsAddresses);
                        } else {
                            log.info("• Cleanup Network Service DNS ({}) -> {}: FAIL", serviceId, dnsAddresses);
                        }
                    }

//                    if (removeGlobalDns(store, dns)) {
//                        log.info("• Cleanup Network Global DNS -> {}: OK", dnsAddresses);
//                    }
                } finally {
                    CF.CFRelease(store);
                }
            }
        };
    }

    /**
     * Notify OS flush dns cache.
     *
     * @return true if notify OS successful, otherwise false
     */
    public static boolean flushDnsCache() {
        final SCDynamicStoreRef store = SC.SCDynamicStoreCreate(null, CFSTR("DNS-FLUSHER"), null, null);
        try {
            return flushDnsCache(store);
        } finally {
            CF.CFRelease(store);
        }
    }

    /* ******* ********* */

    /**
     * Adds the network global dns addresses.
     *
     * @param store the dynamic store session
     * @param dns   the dns addresses to add
     * @return true if add dns addresses is successful or unnecessary, otherwise false
     */
    private static boolean addGlobalDns(final SCDynamicStoreRef store, final String[] dns) {
        return addDns0(store, CFSTR(GLOBAL_DNS_KEY), dns);
    }

    /**
     * Adds the network service dns addresses.
     *
     * @param store     the dynamic store session
     * @param serviceId the network service id
     * @param dns       the dns addresses to add
     * @return true if add dns addresses is successful or unnecessary, otherwise false
     */
    private static boolean addServiceDns(final SCDynamicStoreRef store,
                                         final String serviceId, final String[] dns) {
        return addDns0(store, CFSTR(String.format(SERVICE_ID_DNS_KEY_FMT, serviceId)), dns);
    }

    private static boolean addServiceManualDns(final SCDynamicStoreRef store,
                                               final String serviceId, final String[] dns) {
        return addDns0(store, CFSTR(String.format(SETUP_SERVICE_ID_DNS_KEY_FMT, serviceId)), dns);
    }

    /**
     * Adds the network global/service dns addresses.
     *
     * @param store           the dynamic store session
     * @param dnsDirectoryKey the network dns directory key
     * @param dns             the dns addresses to add
     * @return true if add dns addresses is successful or unnecessary, otherwise false
     */
    private static boolean addDns0(final SCDynamicStoreRef store,
                                   final CFStringRef dnsDirectoryKey, final String[] dns) {
        final List<String> snapshot = getDns0(store, dnsDirectoryKey);
        if (null == snapshot) {
            return false;
        }

        log.info("• Current DNS: {}", snapshot);

        final List<String> dnsToUse = Lists.newArrayList();
        for (final String dnsAddress : dns) {
            if (!snapshot.contains(dnsAddress)) {
                dnsToUse.add(dnsAddress);
            }
        }
        dnsToUse.addAll(snapshot);
        return setDns0(store, dnsDirectoryKey, dnsToUse);
    }

    /**
     * Removes the network global dns addresses.
     *
     * @param store the dynamic store session
     * @param dns   the dns addresses to remove
     * @return true if remove dns addresses is successful or unnecessary, otherwise false
     */
    private static boolean removeGlobalDns(final SCDynamicStoreRef store, final String[] dns) {
        return removeDns0(store, CFSTR(GLOBAL_DNS_KEY), dns);
    }

    /**
     * Removes the network service dns addresses.
     *
     * @param store     the dynamic store session
     * @param serviceId the network service id
     * @param dns       the dns addresses to remove
     * @return true if remove dns addresses is successful or unnecessary, otherwise false
     */
    private static boolean removeServiceDns(final SCDynamicStoreRef store,
                                            final String serviceId, final String[] dns) {
        return removeDns0(store, CFSTR(String.format(SERVICE_ID_DNS_KEY_FMT, serviceId)), dns);
    }

    private static boolean removeServiceManualDns(final SCDynamicStoreRef store,
                                                  final String serviceId, final String[] dns) {
        return removeDns0(store, CFSTR(String.format(SETUP_SERVICE_ID_DNS_KEY_FMT, serviceId)), dns);
    }

    /**
     * Removes the network global/service dns addresses.
     *
     * @param store           the dynamic store session
     * @param dnsDirectoryKey the network dns directory key
     * @param dns             the dns addresses to remove
     * @return true if remove dns addresses is successful or unnecessary, otherwise false
     */
    private static boolean removeDns0(final SCDynamicStoreRef store,
                                      final CFStringRef dnsDirectoryKey, final String[] dns) {
        final List<String> snapshot = getDns0(store, dnsDirectoryKey);
        if (null == snapshot) {
            return false;
        }

        final List<String> dnsToUse = Lists.newArrayList(snapshot);
        boolean found = false;
        for (final String dnsAddress : dns) {
            if (dnsToUse.remove(dnsAddress)) {
                found = true;
            }
        }
        return found && setDns0(store, dnsDirectoryKey, dnsToUse);
    }

    /**
     * Gets the global dns addresses.
     *
     * @param store the dynamic store session
     * @return dns addresses if the network global dns exists, otherwise null
     */
    private static List<String> getGlobalDns(final SCDynamicStoreRef store) {
        return getDns0(store, CFSTR(GLOBAL_DNS_KEY));
    }

    /**
     * Gets the network service dns addresses.
     *
     * @param store     the dynamic store session
     * @param serviceId the network service id
     * @return dns addresses if the network service dns exists, otherwise null
     */
    private static List<String> getServiceDns(final SCDynamicStoreRef store, final String serviceId) {
        return getDns0(store, CFSTR(String.format(SERVICE_ID_DNS_KEY_FMT, serviceId)));
    }

    private static List<String> getServiceManualDns(final SCDynamicStoreRef store, final String serviceId) {
        return getDns0(store, CFSTR(String.format(SETUP_SERVICE_ID_DNS_KEY_FMT, serviceId)));
    }

    /**
     * Gets dns addresses in the dns dictionary.
     *
     * @param store            the dynamic store session
     * @param dnsDictionaryKey the dns dictionary key
     * @return dns addresses if the dns dictionary exists, otherwise null
     */
    private static List<String> getDns0(final SCDynamicStoreRef store, final CFStringRef dnsDictionaryKey) {
        final CFDictionaryRef dnsDictionary = SC.SCDynamicStoreCopyValue(store, dnsDictionaryKey);
        if (null == dnsDictionary) {
            return null;
        }

        try {
            final List<String> dnsToUse = Lists.newArrayList();
            if (CF.CFDictionaryGetTypeID().equals(CF.CFGetTypeID(dnsDictionary))) {
                // read dns server addresses.
                final Pointer ptr = CF.CFDictionaryGetValue(dnsDictionary, CFSTR(SERVER_ADDRESSES));
                if (null != ptr && CF.CFArrayGetTypeID().equals(CF.CFGetTypeID(ptr))) {
                    final CFArrayRef dnsAddresses = new CFArrayRef(ptr);
                    final int count = CF.CFArrayGetCount(dnsAddresses).intValue();
                    for (int i = 0; i < count; i++) {
                        final CFStringRef dnsEntry = new CFStringRef(CF.CFArrayGetValueAtIndex(dnsAddresses, new CFIndex(i)));
                        final String dnsAddress = dnsEntry.stringValue();
                        if (!dnsToUse.contains(dnsAddress)) {
                            dnsToUse.add(dnsAddress);
                        }
                    }
                }
            }
            return dnsToUse;
        } finally {
            CF.CFRelease(dnsDictionary);
        }
    }

    /**
     * Sets the global dns addresses.
     *
     * @param store the dynamic store session
     * @param dns   the global dns addresses
     */
    private static boolean setGlobalDns(final SCDynamicStoreRef store, final List<String> dns) {
        return setDns0(store, CFSTR(GLOBAL_DNS_KEY), dns);
    }

    /**
     * Sets the network service dns addresses.
     *
     * @param store     the dynamic store session
     * @param serviceId the network service id
     * @param dns       the dns addresses to apply
     * @return true if apply dns addresses successful, otherwise false
     */
    private static boolean setServiceDns(final SCDynamicStoreRef store, final String serviceId, final List<String> dns) {
        return setDns0(store, CFSTR(String.format(SERVICE_ID_DNS_KEY_FMT, serviceId)), dns);
    }

    private static boolean setServiceManualDns(final SCDynamicStoreRef store, final String serviceId, final List<String> dns) {
        return setDns0(store, CFSTR(String.format(SETUP_SERVICE_ID_DNS_KEY_FMT, serviceId)), dns);
    }

    /**
     * Sets dns addresses into the dns dictionary.
     *
     * @param store            the dynamic store session
     * @param dnsDictionaryKey the dns dictionary key
     * @param dns              the dns addresses to apply
     * @return true if apply dns addresses successful, otherwise false
     */
    private static boolean setDns0(final SCDynamicStoreRef store,
                                   final CFStringRef dnsDictionaryKey, final List<String> dns) {
        try (final Memory memory = !dns.isEmpty() ? new Memory((long) Native.POINTER_SIZE * dns.size()) : null) {
            // create dns addresses array.
            for (int i = 0; i < dns.size(); i++) {
                memory.setPointer((long) i * Native.POINTER_SIZE, CFSTR(dns.get(i)).getPointer());
            }

            // create dns configure dictionary
            final CFDictionaryRef originalDnsDictionary = SC.SCDynamicStoreCopyValue(store, dnsDictionaryKey);
            if (null == originalDnsDictionary) {
                // dns directory not exists, skip create directory.
                return false;
            }

            final CFMutableDictionaryRef dnsDictionary = CF.CFDictionaryCreateMutableCopy(
                    null, new CFIndex(0), originalDnsDictionary
            );

            final CFArrayRef dnsAddresses = CF.CFArrayCreate(null, memory, new CFIndex(dns.size()), null);
            CF.CFDictionarySetValue(dnsDictionary, CFSTR(SERVER_ADDRESSES), dnsAddresses);

            try {
                if (SC.SCDynamicStoreSetValue(store, dnsDictionaryKey, dnsDictionary)) {
                    log.info("• Set DNS (KEY:{}) -> {}: OK", dnsDictionaryKey.stringValue(), dns);
                    // flush dns cache ?
                    SC.SCDynamicStoreNotifyValue(store, dnsDictionaryKey);
                    return true;
                }
                log.info("• Set DNS (KEY:{}) -> {}: FAIL", dnsDictionaryKey.stringValue(), dns);
                return false;
            } finally {
                CF.CFRelease(dnsDictionary);
            }
        }
    }

    /**
     * Notify OS flush dns cache.
     *
     * @param store the dynamic store session
     * @return true if notify OS successful, otherwise false
     */
    private static boolean flushDnsCache(final SCDynamicStoreRef store) {
        return SC.SCDynamicStoreNotifyValue(store, CFSTR(GLOBAL_DNS_KEY));
    }

    /**
     * Watch the patterns.
     *
     * @param name     the name of dynamic store session
     * @param patterns the watch patterns
     * @param callback the callback
     */
    private static void watch(final String name, final String[] patterns, final SCDynamicStoreCallBack callback) {
        if (null == patterns || 0 == patterns.length) {
            throw new IllegalArgumentException("patterns is empty");
        }

        final SCDynamicStoreRef store = SC.SCDynamicStoreCreate(null, CFSTR(name), callback, null);
        try (final Memory memory = new Memory((long) Native.POINTER_SIZE * patterns.length)) {
            // create watch patterns array.
            for (int i = 0; i < patterns.length; i++) {
                memory.setPointer((long) Native.POINTER_SIZE * i, CFSTR(patterns[i]).getPointer());
            }
            final CFArrayRef patternsToWatch = CF.CFArrayCreate(null, memory, new CFIndex(patterns.length), null);

            // set watch patterns
            if (!SC.SCDynamicStoreSetNotificationKeys(store, null, patternsToWatch)) {
                throw new IllegalStateException("SCDynamicStoreSetNotificationKeys: FAIL");
            }
            CF.CFRelease(patternsToWatch);
        }

        // create loop source, bind to loop, and run loop.
        final CFRunLoopRef rl = CF.CFRunLoopGetCurrent();
        final CFRunLoopSourceRef rlSource = SC.SCDynamicStoreCreateRunLoopSource(null, store, new CFIndex(0));
        try {
            CF.CFRunLoopAddSource(rl, rlSource, CFSTR("kCFRunLoopDefaultMode").getPointer());
            CF.CFRunLoopRun();
        } finally {
            CF.CFRelease(rlSource);
            CF.CFRelease(store);
        }
    }

    /**
     * Gets primary network service ID.
     *
     * @param store the dynamic store session
     * @return the service ID or null
     */
    private static String getPrimaryServiceId(final SCDynamicStoreRef store) {
        final CFDictionaryRef dictionary = SC.SCDynamicStoreCopyValue(store, CFSTR(GLOBAL_IPV4_KEY));
        try {
            if (null != dictionary && CF.CFDictionaryGetTypeID().equals(CF.CFGetTypeID(dictionary))) {
                return new CFStringRef(CF.CFDictionaryGetValue(dictionary, CFSTR("PrimaryService"))).stringValue();
            }
            return null;
        } finally {
            if (null != dictionary) {
                CF.CFRelease(dictionary);
            }
        }
    }

    /**
     * @param store the dynamic store session
     * @return DNS service ID
     */
    private static List<String> getDnsServiceIds(final SCDynamicStoreRef store) {
        final List<String> serviceKeys = Lists.newArrayList();
        final CFArrayRef dnsDictionaryKeys = SC.SCDynamicStoreCopyKeyList(store, CFSTR(SERVICE_QUERY_DNS_PATTERN_STR));
        if (null != dnsDictionaryKeys) {
            final int count = CF.CFArrayGetCount(dnsDictionaryKeys).intValue();
            for (int i = 0; i < count; i++) {
                final Pointer ptr = CF.CFArrayGetValueAtIndex(dnsDictionaryKeys, new CFIndex(i));
                final CFStringRef dnsKey = new CFStringRef(ptr);
                final String serviceId = extractServiceId(dnsKey.stringValue());
                if (null != serviceId) {
                    serviceKeys.add(serviceId);
                }
            }
            CF.CFRelease(dnsDictionaryKeys);
        }
        return serviceKeys;
    }

    private static String extractServiceId(final String key) {
        final Matcher matcher = SERVICE_ID_DNS_PATTERN.matcher(key);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Creates CFStringRef.
     *
     * @param str the string
     * @return the CFStringRef
     */
    private static CFStringRef CFSTR(final String str) {
        return CFStringRef.createCFString(str);
    }

}