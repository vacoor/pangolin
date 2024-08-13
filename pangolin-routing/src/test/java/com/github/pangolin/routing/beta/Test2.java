package com.github.pangolin.routing.beta;

import com.github.pangolin.routing.util.SocketUtils;
import io.netty.util.NetUtil;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class Test2 {

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

  public Test2() {
    final Node n = new Node(null);
    head.set(n);
    tail.set(n);
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
    Lease lease = poll(100);
    if (null == lease) {
      lease = acquire0();
    }
    offer(lease);
    return lease;
  }

  private final AtomicInteger generator = new AtomicInteger();
  private Lease acquire0() {
    return new Lease(generator.incrementAndGet());
  }

  public Lease poll(final long ttl) {
    restartFromHead:
    for (;;) { // 无限循环
      for (Node h = head.get(), p = h, q;;) { // 保存头节点
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
        }
        else if ((q = p.next.get()) == null) { // q结点为null
          // 更新头节点
          updateHead(h, p);
          return null;
        }
        else if (p == q) // p等于q
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

  public static void main(String[] args) {
//    NetUtil.intToIpAddress()
    final byte[] address = SocketUtils.toAddress("192.168.1.1", false).getAddress();
    final byte[] mask = SocketUtils.toAddress("255.255.255.0", false).getAddress();
    final int addressInt = ipAddressToInt(address);

    final int maskInt = ipAddressToInt(mask);
    final int size = ((int) Math.pow(2, 32) - 1) - maskInt;
    final int subnetAddress = addressInt & maskInt;
    System.out.println(size);

    Test2 allocator = new Test2();
    for (int i = 0; i < 10; i++) {
      final int index = allocator.acquire().value;
      System.out.println(index + ": " + NetUtil.intToIpAddress(subnetAddress + index));
    }
  }

}
