package com.github.pangolin.routing.beta;

import com.github.pangolin.routing.util.SocketUtils;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.netty.util.NetUtil;
import lombok.val;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class LeaseAllocator {

    protected class Lease {
        private final int value;
        private final long timestamp;

        protected Lease(int value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }
    }

    protected class Node {
        private final AtomicReference<Lease> item;
        private final AtomicReference<Node> next = new AtomicReference<>();

        protected Node(Lease item) {
            this.item = new AtomicReference<>(item);
        }
    }

    private final AtomicReference<Node> head = new AtomicReference<>();
    private final AtomicReference<Node> tail = new AtomicReference<>();


    private final int min;
    private final int max;
    private final AtomicInteger generator;
    private final long ttl;

    public LeaseAllocator(final int min, final int max, final long ttl) {
        final Node n = new Node(null);
        head.set(n);
        tail.set(n);
        this.min = min;
        this.max = max;
        generator = new AtomicInteger(min);
        this.ttl = ttl;
    }

    private void offer(final Lease item) {
        final Node newNode = new Node(item);
        Node t = tail.get();
        Node p = t;
        do {
            Node q = p.next.get();
            if (null == q) {
                // p is last node
                if (p.next.compareAndSet(null, newNode)) {
                    if (p != t) {
                        tail.compareAndSet(t, newNode);
                    }
                    return;
                }
                // Lost CAS race to another thread; re-read next
            } else if (p == q) {
                p = (t != (t = tail.get())) ? t : head.get();
            } else {
                p = (p != t && t != (t = tail.get())) ? t : q;
            }
        } while (true);
    }

    public Lease acquire() {
        Lease lease = poll(2 * ttl);
        if (null == lease) {
            lease = acquire0();
//        } else {
//            Lease o = lease;
//            lease = new Lease(o.value);
        }
        if (null == lease) {
            lease = poll(ttl);
//        } else {
//            lease = new Lease(lease.value);
        }

        if (null == lease) {
            throw new IllegalStateException("No more");
        }
        lease = new Lease(lease.value);
        offer(lease);
        return lease;
    }

    private Lease acquire0() {
        int value;
        do {
            value = generator.get();
            if (value > max) {
                return null;
            }
        } while(!generator.compareAndSet(value, value + 1));
        return new Lease(value);
    }

    public Lease poll(final long ttl) {
        restartFromHead:
        for (; ; ) { // 无限循环
            for (Node h = head.get(), p = h, q; ; ) { // 保存头节点
                // item项
                Lease item = p.item.get();


                // if (item != null && p.item.compareAndSet(item, null)) { // item不为null并且比较并替换item成功
                if (item != null) {
                    // TODO 如果item没有过期, 直接 return null;
                    if (System.currentTimeMillis() - item.timestamp < ttl) {
                        return null;
                    }

                    if (p.item.compareAndSet(item, null)) {
                        // Successful CAS is the linearization point
                        // for item to be removed from this queue.
                        if (p != h) // p不等于h    // hop two nodes at a time
                            // 更新头节点
                            updateHead(h, ((q = p.next.get()) != null) ? q : p);
                        // 返回item
                        return item;
                    }
                } else if ((q = p.next.get()) == null) { // q结点为null
                    // 更新头节点
                    updateHead(h, p);
                    return null;
                } else if (p == q) // p等于q
                    // 继续循环
                    continue restartFromHead;
                else
                    // p赋值为q
                    p = q;
            }
        }
    }

    final void updateHead(Node h, Node p) {
        if (h != p && head.compareAndSet(h, p))
            h.next.lazySet(h);
    }

    private static int ipAddressToInt(final byte[] ipBytes) {
        assert ipBytes.length == 4;
        return (ipBytes[0] & 0xff) << 24 | (ipBytes[1] & 0xff) << 16 | (ipBytes[2] & 0xff) << 8 | ipBytes[3] & 0xff;
    }

    final Map<String, Lease> leaseMap = new ConcurrentHashMap<>();

    protected String lookup(final String domain) {
        int value = leaseMap.compute(domain, (k, v) -> {
            //
            if (null == v || System.currentTimeMillis() - v.timestamp >= 2 * ttl) {
                return acquire();
            }
            return v;
        }).value;
        return 0 != value ? NetUtil.intToIpAddress(value) : null;
    }

    public static void main(String[] args) {
        final byte[] address = SocketUtils.toAddress("192.168.1.1", false).getAddress();
        final byte[] mask = SocketUtils.toAddress("255.255.255.000", false).getAddress();
        final int addressInt = ipAddressToInt(address);

        final int maskInt = ipAddressToInt(mask);
        final int size = 0xFFFFFFFF - maskInt;
        final int subnetAddress = addressInt & maskInt;
        System.out.println(size);

        System.out.println(NetUtil.intToIpAddress(subnetAddress));
        LeaseAllocator allocator = new LeaseAllocator(subnetAddress + 1, subnetAddress + size - 1, 100);
        for (int i = 0; i < 10; i++) {
            final int index = allocator.acquire().value;
            System.out.println((index) + ": " + NetUtil.intToIpAddress(index));
            System.out.println(allocator.lookup("www.baidu.com"));
        }
    }

}
