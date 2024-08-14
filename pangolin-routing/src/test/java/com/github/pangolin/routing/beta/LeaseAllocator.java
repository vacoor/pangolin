package com.github.pangolin.routing.beta;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public abstract class LeaseAllocator<E> {

    protected class Node<E> {
        private final AtomicReference<E> item;
        private final AtomicReference<Node<E>> next = new AtomicReference<>();

        protected Node(E item) {
            this.item = new AtomicReference<>(item);
        }
    }

    private final AtomicReference<Node<E>> head = new AtomicReference<>();
    private final AtomicReference<Node<E>> tail = new AtomicReference<>();


    public LeaseAllocator() {
        final Node n = new Node(null);
        head.set(n);
        tail.set(n);
    }

    protected void offer(final E item) {
        final Node<E> newNode = new Node(item);
        Node<E> t = tail.get();
        Node<E> p = t;
        do {
            Node<E> q = p.next.get();
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

    protected E poll(final long ttl) {
        restartFromHead:
        for (; ; ) { // 无限循环
            for (Node<E> h = head.get(), p = h, q; ; ) { // 保存头节点
                // item项
                E item = p.item.get();

                // if (item != null && p.item.compareAndSet(item, null)) { // item不为null并且比较并替换item成功
                if (item != null) {
                    // TODO 如果item没有过期, 直接 return null;
                    if (!check(item)) {
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

    protected abstract boolean check(E item);


}
