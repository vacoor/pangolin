package com.github.pangolin.routing.acceptor.tun.adapter.darwin;

import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.CoreFoundation;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.SystemConfiguration;
import com.google.common.collect.Lists;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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

    private static final String STATE_GLOBAL_DNS_KEY = "State:/Network/Global/DNS";
    private static final String STATE_GLOBAL_IPV4_KEY = "State:/Network/Global/IPv4";

    private static final String STATE_SERVICE_ID_DNS_KEY_FMT = "State:/Network/Service/%s/DNS";
    private static final String SETUP_SERVICE_ID_DNS_KEY_FMT = "Setup:/Network/Service/%s/DNS";

    private static final String STATE_DNS_SERVICE_ID_QUERY_PATTERN_STR = "State:/Network/Service/.*/DNS";
    private static final Pattern STATE_SERVICE_ID_MATCHES_PATTERN = Pattern.compile("^State:/Network/Service/([-a-zA-Z0-9]+)/.*");

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
        return setDns(dns, true, true);
    }

    /**
     * Sets the network global/service dns addresses.
     *
     * @param dns the dns addresses to set
     * @return true if add dns addresses is successful or unnecessary, otherwise false
     */
    public static boolean setDns(final String[] dns) {
        return setDns(dns, false, true);
    }

    /**
     * Sets the network global/service dns addresses.
     *
     * @param dns the dns addresses to add or set
     * @return true if add dns addresses is successful or unnecessary, otherwise false
     */
    public static boolean setDns(final String[] dns, final boolean append, final boolean cleanupOnShutdown) {
        final String name = DarwinDns.class.getSimpleName();
        final AtomicBoolean shutdownHolder = new AtomicBoolean();
        final AtomicReference<String> serviceIdHolder = new AtomicReference<>();
        final SCDynamicStoreRef store = SC.SCDynamicStoreCreate(null, CFSTR(name), null, null);
        try {
            final String primaryServiceId = getPrimaryServiceId(store);
            if (null == primaryServiceId) {
                return false;
            }
            /*-
             * Using "Setup:/Network/Service/{ServiceId}/DNS" when manually (high priority).
             * Using "State:/Network/Service/{ServiceId}/DNS" when automatic.
             */
            String[] currentDns;
            final String[] manuallyServiceDns = getServiceDns(store, primaryServiceId, true);
            if (null != manuallyServiceDns) {
                if (append && !addServiceDns(store, primaryServiceId, true, dns)) {
                    return false;
                } else if (!append && !setServiceDns(store, primaryServiceId, true, dns)) {
                    return false;
                }
                currentDns = manuallyServiceDns;
            } else {
                final String[] automaticServiceDns = getServiceDns(store, primaryServiceId, false);
                if (null == automaticServiceDns) {
                    return false;
                }
                if (append && !addServiceDns(store, primaryServiceId, false, dns)) {
                    return false;
                } else if (!append && !setServiceDns(store, primaryServiceId, false, dns)) {
                    return false;
                }
                currentDns = automaticServiceDns;
            }

            final String[] defaultDns = currentDns;
            serviceIdHolder.set(primaryServiceId);
            watchInBackground(name, new String[]{STATE_GLOBAL_IPV4_KEY}, new SCDynamicStoreCallBack() {
                @Override
                public void invoke(final SCDynamicStoreRef store, final CFArrayRef changedKeys, final Pointer info) {
                    if (Thread.currentThread().isInterrupted() || shutdownHolder.get()) {
                        return;
                    }

                    final String prevServiceId = serviceIdHolder.get();
                    final String nextServiceId = getPrimaryServiceId(store);
                    serviceIdHolder.set(nextServiceId);

                    /*-
                     * Clean up the DNS of the previous service when the service changed.
                     */
                    final boolean changed = !Objects.equals(prevServiceId, nextServiceId);
                    if (null != prevServiceId && changed && removeServiceDns(store, prevServiceId, true, dns)) {
                        log.info("• Cleanup Network Service DNS: {} -> {}", String.format(SETUP_SERVICE_ID_DNS_KEY_FMT, prevServiceId), Arrays.asList(dns));
                    }
                    if (null != prevServiceId && changed && removeServiceDns(store, prevServiceId, false, dns)) {
                        log.info("• Cleanup Network Service DNS: {} -> {}", String.format(STATE_SERVICE_ID_DNS_KEY_FMT, prevServiceId), Arrays.asList(dns));
                    }

                    /*-
                     * add dns to the current service.
                     */
                    if (null != nextServiceId && addServiceDns(store, nextServiceId, true, dns)) {
                        log.info("• Add Network Service DNS: {} -> {}", String.format(SETUP_SERVICE_ID_DNS_KEY_FMT, nextServiceId), Arrays.asList(dns));
                    } else if (null != nextServiceId && addServiceDns(store, nextServiceId, false, dns)) {
                        log.info("• Add Network Service DNS: {} -> {}", String.format(STATE_SERVICE_ID_DNS_KEY_FMT, nextServiceId), Arrays.asList(dns));
                    }
                }
            }).start();

            if (cleanupOnShutdown) {
                Runtime.getRuntime().addShutdownHook(dnsCleaner(name, serviceIdHolder, shutdownHolder, dns, defaultDns));
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
        worker.setName("DNS-WATCHER");
        worker.setDaemon(true);
        return worker;
    }

    private static Thread dnsCleaner(final String name,
                                     final AtomicReference<String> serviceIdHolder,
                                     final AtomicBoolean shutdownHolder, final String[] dns, final String... defaultDns) {
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
                    /*-
                     * Clean up the DNS of the previous service when the service changed.
                     */
                    if (null != serviceId && removeServiceDns(store, serviceId, true, dns, defaultDns)) {
                        log.info("• Cleanup Network Service DNS: {} -> {}", String.format(SETUP_SERVICE_ID_DNS_KEY_FMT, serviceId), Arrays.asList(dns));
                    }
                    if (null != serviceId && removeServiceDns(store, serviceId, false, dns, defaultDns)) {
                        log.info("• Cleanup Network Service DNS: {} -> {}", String.format(STATE_SERVICE_ID_DNS_KEY_FMT, serviceId), Arrays.asList(dns));
                    }
                } finally {
                    CF.CFRelease(store);
                }
            }
        };
    }

    public static String[] getDns() {
        final SCDynamicStoreRef store = SC.SCDynamicStoreCreate(null, CFSTR("GET-DNS"), null, null);
        try {
            final String primaryServiceId = getPrimaryServiceId(store);
            if (null == primaryServiceId) {
                return null;
            }
            final String[] manuallyServiceDns = getServiceDns(store, primaryServiceId, true);
            return null != manuallyServiceDns ? manuallyServiceDns : getServiceDns(store, primaryServiceId, false);
        } finally {
            CF.CFRelease(store);
        }
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
        return addDns0(store, CFSTR(STATE_GLOBAL_DNS_KEY), dns);
    }

    /**
     * Adds the network service dns addresses.
     *
     * @param store     the dynamic store session
     * @param serviceId the network service id
     * @param setup     Setup:/Network/Service/{ServiceId}/DNS or State:/Network/Service/{ServiceId}/DNS
     * @param dns       the dns addresses to add
     * @return true if add dns addresses is successful or unnecessary, otherwise false
     */
    private static boolean addServiceDns(final SCDynamicStoreRef store,
                                         final String serviceId, final boolean setup, final String[] dns) {
        final String dnsDirectoryKeyFmt = setup ? SETUP_SERVICE_ID_DNS_KEY_FMT : STATE_SERVICE_ID_DNS_KEY_FMT;
        return addDns0(store, CFSTR(String.format(dnsDirectoryKeyFmt, serviceId)), dns);
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
        final String[] snapshot = getDns0(store, dnsDirectoryKey);
        if (null == snapshot) {
            return false;
        }

        final List<String> dnsToUse = Lists.newArrayList(snapshot);
        for (int i = dns.length - 1; i >= 0; i--) {
            if (!dnsToUse.contains(dns[i])) {
                dnsToUse.add(0, dns[i]);
            }
        }
        if (dnsToUse.size() == snapshot.length) {
            return true;
        }
        return setDns0(store, dnsDirectoryKey, dnsToUse.toArray(new String[0]));
    }

    /**
     * Removes the network global dns addresses.
     *
     * @param store the dynamic store session
     * @param dns   the dns addresses to remove
     * @return true if remove dns addresses is successful or unnecessary, otherwise false
     */
    private static boolean removeGlobalDns(final SCDynamicStoreRef store, final String[] dns, final String... defaultDns) {
        return removeDns0(store, CFSTR(STATE_GLOBAL_DNS_KEY), dns, defaultDns);
    }

    /**
     * Removes the network service dns addresses.
     *
     * @param store     the dynamic store session
     * @param serviceId the network service id
     * @param setup     Setup:/Network/Service/{ServiceId}/DNS or State:/Network/Service/{ServiceId}/DNS
     * @param dns       the dns addresses to remove
     * @return true if remove dns addresses is successful or unnecessary, otherwise false
     */
    private static boolean removeServiceDns(final SCDynamicStoreRef store,
                                            final String serviceId, final boolean setup,
                                            final String[] dns, final String... defaultDns) {
        final String dnsDirectoryKeyFmt = setup ? SETUP_SERVICE_ID_DNS_KEY_FMT : STATE_SERVICE_ID_DNS_KEY_FMT;
        return removeDns0(store, CFSTR(String.format(dnsDirectoryKeyFmt, serviceId)), dns, defaultDns);
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
                                      final CFStringRef dnsDirectoryKey,
                                      final String[] dns, final String... defaultDns) {
        final String[] snapshot = getDns0(store, dnsDirectoryKey);
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
        if (dnsToUse.isEmpty() && null != defaultDns && defaultDns.length > 0) {
            Collections.addAll(dnsToUse, defaultDns);
        }
        return found && setDns0(store, dnsDirectoryKey, dnsToUse.toArray(new String[0]));
    }

    /**
     * Gets the global dns addresses.
     *
     * @param store the dynamic store session
     * @return dns addresses if the network global dns exists, otherwise null
     */
    private static String[] getGlobalDns(final SCDynamicStoreRef store) {
        return getDns0(store, CFSTR(STATE_GLOBAL_DNS_KEY));
    }

    /**
     * Gets the network service dns addresses.
     *
     * @param store     the dynamic store session
     * @param serviceId the network service id
     * @param setup     Setup:/Network/Service/{ServiceId}/DNS or State:/Network/Service/{ServiceId}/DNS
     * @return dns addresses if the network service dns exists, otherwise null
     */
    private static String[] getServiceDns(final SCDynamicStoreRef store, final String serviceId, final boolean setup) {
        final String dnsDirectoryKeyFmt = setup ? SETUP_SERVICE_ID_DNS_KEY_FMT : STATE_SERVICE_ID_DNS_KEY_FMT;
        return getDns0(store, CFSTR(String.format(dnsDirectoryKeyFmt, serviceId)));
    }

    /**
     * Gets dns addresses in the dns dictionary.
     *
     * @param store            the dynamic store session
     * @param dnsDictionaryKey the dns dictionary key
     * @return dns addresses if the dns dictionary exists, otherwise null
     */
    private static String[] getDns0(final SCDynamicStoreRef store, final CFStringRef dnsDictionaryKey) {
        final CFDictionaryRef dnsDictionary = SC.SCDynamicStoreCopyValue(store, dnsDictionaryKey);
        if (null == dnsDictionary) {
            return null;
        }

        try {
            if (!CF.CFDictionaryGetTypeID().equals(CF.CFGetTypeID(dnsDictionary))) {
                return null;
            }

            // read dns server addresses.
            final Pointer ptr = CF.CFDictionaryGetValue(dnsDictionary, CFSTR(SERVER_ADDRESSES));
            if (null == ptr || !CF.CFArrayGetTypeID().equals(CF.CFGetTypeID(ptr))) {
                return null;
            }

            final CFArrayRef dnsAddresses = new CFArrayRef(ptr);
            final int count = CF.CFArrayGetCount(dnsAddresses).intValue();
            final String[] dnsToUse = new String[count];
            for (int i = 0; i < count; i++) {
                final CFStringRef dnsEntry = new CFStringRef(CF.CFArrayGetValueAtIndex(dnsAddresses, new CFIndex(i)));
                final String dnsAddress = dnsEntry.stringValue();
                dnsToUse[i] = dnsAddress;
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
    private static boolean setGlobalDns(final SCDynamicStoreRef store, final String[] dns) {
        return setDns0(store, CFSTR(STATE_GLOBAL_DNS_KEY), dns);
    }

    /**
     * Sets the network service dns addresses.
     *
     * @param store     the dynamic store session
     * @param serviceId the network service id
     * @param setup     Setup:/Network/Service/{ServiceId}/DNS or State:/Network/Service/{ServiceId}/DNS
     * @param dns       the dns addresses to apply
     * @return true if apply dns addresses successful, otherwise false
     */
    private static boolean setServiceDns(final SCDynamicStoreRef store, final String serviceId, final boolean setup, final String[] dns) {
        final String dnsDirectoryKeyFmt = setup ? SETUP_SERVICE_ID_DNS_KEY_FMT : STATE_SERVICE_ID_DNS_KEY_FMT;
        return setDns0(store, CFSTR(String.format(dnsDirectoryKeyFmt, serviceId)), dns);
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
                                   final CFStringRef dnsDictionaryKey, final String[] dns) {
        try (final Memory memory = null != dns && dns.length > 0 ? new Memory((long) Native.POINTER_SIZE * dns.length) : null) {
            // create dns addresses array.
            for (int i = 0; i < dns.length; i++) {
                memory.setPointer((long) i * Native.POINTER_SIZE, CFSTR(dns[i]).getPointer());
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

            final CFArrayRef dnsAddresses = CF.CFArrayCreate(null, memory, new CFIndex(dns.length), null);
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
        return SC.SCDynamicStoreNotifyValue(store, CFSTR(STATE_GLOBAL_DNS_KEY));
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
        final CFDictionaryRef dictionary = SC.SCDynamicStoreCopyValue(store, CFSTR(STATE_GLOBAL_IPV4_KEY));
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
        final CFArrayRef dnsDictionaryKeys = SC.SCDynamicStoreCopyKeyList(store, CFSTR(STATE_DNS_SERVICE_ID_QUERY_PATTERN_STR));
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
        final Matcher matcher = STATE_SERVICE_ID_MATCHES_PATTERN.matcher(key);
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