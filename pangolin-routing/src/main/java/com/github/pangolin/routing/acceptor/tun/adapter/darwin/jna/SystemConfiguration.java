package com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna;

import com.sun.jna.*;

import static com.sun.jna.platform.mac.CoreFoundation.*;

/**
 * System Configuration framework.
 *
 * @see <a href="https://developer.apple.com/documentation/systemconfiguration/">System Configuration</a>
 */
public interface SystemConfiguration extends Library {

    SystemConfiguration INSTANCE = Native.load("SystemConfiguration", SystemConfiguration.class);

    /**
     * This is the handle to an open a dynamic store session
     * with the system configuration daemon.
     */
    class SCDynamicStoreRef extends CFTypeRef {
    }

    /**
     * Type of callback function used when notification of
     * changes to the dynamic store is delivered.
     *
     * @see <a href="https://developer.apple.com/documentation/systemconfiguration/scdynamicstorecallback">SCDynamicStoreCallBack</a>
     */
    interface SCDynamicStoreCallBack extends Callback {

        /**
         * Callback function used when notification of
         * changes to the dynamic store is delivered.
         *
         * @param store       The dynamic store session
         * @param changedKeys The list of changed keys
         * @param info        A C pointer to a user-specified block of data
         */
        void invoke(
                final SCDynamicStoreRef store,
                final CFArrayRef changedKeys,
                final Pointer info
        );

    }

    /**
     * Creates a new session used to interact with the dynamic
     * store maintained by the System Configuration server.
     *
     * @param allocator The CFAllocator that should be used to allocate
     *                  memory for the local dynamic store object.
     *                  This parameter may be NULL in which case the current
     *                  default CFAllocator is used. If this reference is not
     *                  a valid CFAllocator, the behavior is undefined.
     * @param name      A string that describes the name of the calling
     *                  process or plug-in of the caller.
     * @param callout   The function to be called when a watched value
     *                  in the dynamic store is changed.
     *                  A NULL value can be specified if no callouts are
     *                  desired.
     * @param context   The context associated with the callout.
     * @return Returns a reference to the new SCDynamicStore session.
     * You must release the returned value.
     * @see <a href="https://developer.apple.com/documentation/systemconfiguration/scdynamicstorecreate(_:_:_:_:)">SCDynamicStoreCreate(_:_:_:_:)</a>
     */
    SCDynamicStoreRef SCDynamicStoreCreate(
            final CFAllocatorRef allocator,
            final CFStringRef name,
            final SCDynamicStoreCallBack callout,
            final Pointer context
    );

    /**
     * Creates a dynamic store key that can be used to access the per-service network configuration information.
     *
     * @param allocator The allocator that should be used to allocate memory
     *                  for this key. This parameter may be NULL in which case
     *                  the current default allocator is used.
     *                  If this value is not a valid CFAllocator, the behavior is undefined.
     * @param domain    The desired domain, such as the requested configuration or the current state.
     * @param serviceID The service ID or a regular expression pattern.
     * @param entity    The specific global entity, such as IPv4 or DNS.
     * @return A reference to the new key. You must release the returned value.
     * @see <a href="https://developer.apple.com/documentation/systemconfiguration/scdynamicstorekeycreatenetworkserviceentity(_:_:_:_:)/">SCDynamicStoreKeyCreateNetworkServiceEntity(_:_:_:_:)</a>
     */
    CFStringRef SCDynamicStoreKeyCreateNetworkServiceEntity(
            final CFAllocatorRef allocator,
            final CFStringRef domain,
            final CFStringRef serviceID,
            final CFStringRef entity
    );

    /**
     * Creates a CFRunLoopSource object that can be added to the
     * application's run loop.  All dynamic store notifications are
     * delivered using this run loop source.
     *
     * @param allocator The CFAllocator that should be used to allocate
     *                  memory for this run loop source.
     *                  This parameter may be NULL in which case the current
     *                  default CFAllocator is used. If this reference is not
     *                  a valid CFAllocator, the behavior is undefined.
     * @param store     A reference to the dynamic store session.
     * @param order     On platforms which support it, for source versions
     *                  which support it, this parameter determines the order in
     *                  which the sources which are ready to be processed are
     *                  handled. A lower order number causes processing before
     *                  higher order number sources. It is inadvisable to depend
     *                  on the order number for any architectural or design aspect
     *                  of code. In the absence of any reason to do otherwise,
     *                  zero should be used.
     * @return A reference to the new CFRunLoopSource.
     * You must release the returned value.
     * @see <a href="https://developer.apple.com/documentation/systemconfiguration/scdynamicstorecreaterunloopsource(_:_:_:)">SCDynamicStoreCreateRunLoopSource(_:_:_:)</a>
     */
    CoreFoundation.CFRunLoopSourceRef SCDynamicStoreCreateRunLoopSource(
            final CFAllocatorRef allocator,
            final SCDynamicStoreRef store,
            final CFIndex order
    );

    /**
     * Returns an array of CFString keys representing the
     * current dynamic store entries that match a specified pattern.
     *
     * @param store   The dynamic store session.
     * @param pattern A regex(3) regular expression pattern
     *                used to match the dynamic store keys.
     * @return Returns the list of matching keys; NULL if an error was encountered.
     * You must release the returned value.
     * @see <a href="https://developer.apple.com/documentation/systemconfiguration/scdynamicstorecopykeylist(_:_:)">SCDynamicStoreCopyKeyList(_:_:)</a>
     */
    CFArrayRef SCDynamicStoreCopyKeyList(
            final SCDynamicStoreRef store,
            final CFStringRef pattern
    );

    /**
     * Gets the value of the specified key from the dynamic store.
     *
     * @param store The dynamic store session.
     * @param key   The key associated with the value you want to get.
     * @return Returns the value from the dynamic store that is associated with the given
     * key; NULL if no value was located or an error was encountered.
     * You must release the returned value.
     * @see <a href="https://developer.apple.com/documentation/systemconfiguration/scdynamicstorecopyvalue(_:_:)">SCDynamicStoreCopyValue(_:_:)</a>
     */
    CFDictionaryRef SCDynamicStoreCopyValue(
            final SCDynamicStoreRef store,
            final CFStringRef key
    );

    /**
     * Adds or replaces a value in the dynamic store for
     * the specified key.
     *
     * @param store The dynamic store session.
     * @param key   The key you want to set.
     * @param value The value to add to or replace in the dynamic store.
     * @return Returns TRUE if the key was updated; FALSE if an error was encountered.
     * @see <a href="https://developer.apple.com/documentation/systemconfiguration/scdynamicstoresetvalue(_:_:_:)">SCDynamicStoreSetValue(_:_:_:)</a>
     */
    boolean SCDynamicStoreSetValue(
            final SCDynamicStoreRef store,
            final CFStringRef key,
            final PointerType value
    );

    /**
     * Triggers a notification to be delivered for the
     * specified key in the dynamic store.
     *
     * @param store The dynamic store session.
     * @param key   The key that should be flagged as changed.  Any dynamic store sessions
     *              that are monitoring this key will received a notification.  Note that the
     *              key's value is not updated.
     * @return Returns TRUE if the notification was processed; FALSE if an error was encountered.
     * @see <a href="https://developer.apple.com/documentation/systemconfiguration/scdynamicstorenotifyvalue(_:_:)">SCDynamicStoreNotifyValue(_:_:)</a>
     */
    boolean SCDynamicStoreNotifyValue(
            final SCDynamicStoreRef store,
            final CFStringRef key
    );

    /**
     * Specifies a set of specific keys and key patterns
     * that should be monitored for changes.
     *
     * @param store    The dynamic store session being watched.
     * @param keys     An array of keys to be monitored; NULL if no specific keys
     *                 are to be monitored.
     * @param patterns An array of regex(3) pattern strings used to match keys to be monitored;
     *                 NULL if no key patterns are to be monitored.
     * @return Returns TRUE if the set of notification keys and patterns was successfully
     * updated; FALSE if an error was encountered.
     * @see <a href="https://developer.apple.com/documentation/systemconfiguration/scdynamicstoresetnotificationkeys(_:_:_:)">SCDynamicStoreSetNotificationKeys(_:_:_:)</a>
     */
    boolean SCDynamicStoreSetNotificationKeys(
            final SCDynamicStoreRef store,
            final CFArrayRef keys,
            final CFArrayRef patterns
    );

}