package com.app.transport.debug

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock

/**
 * Minimal thread-safe FIFO queue used by [DebugSettingsManager] for its rolling debug-stat windows.
 * commonMain replacement for java.util.concurrent.ConcurrentLinkedQueue (an ArrayDeque guarded by a
 * stately Lock); exposes only the operations the debug manager needs. Not a general-purpose queue.
 */
internal class ConcurrentFifoQueue<T> {
    private val lock = Lock()
    private val deque = ArrayDeque<T>()

    val size: Int get() = lock.withLock { deque.size }

    fun isEmpty(): Boolean = lock.withLock { deque.isEmpty() }
    fun isNotEmpty(): Boolean = lock.withLock { deque.isNotEmpty() }

    fun offer(element: T) {
        lock.withLock { deque.addLast(element) }
    }

    fun peek(): T? = lock.withLock { deque.firstOrNull() }

    fun poll(): T? = lock.withLock { if (deque.isEmpty()) null else deque.removeFirst() }

    fun clear() {
        lock.withLock { deque.clear() }
    }

    fun toList(): List<T> = lock.withLock { deque.toList() }

    fun count(predicate: (T) -> Boolean): Int = lock.withLock { deque.count(predicate) }

    fun filter(predicate: (T) -> Boolean): List<T> = lock.withLock { deque.filter(predicate) }

    fun remove(element: T): Boolean = lock.withLock { deque.remove(element) }
}
