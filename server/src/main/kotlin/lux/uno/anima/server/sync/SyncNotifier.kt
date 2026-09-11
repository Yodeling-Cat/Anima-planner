package lux.uno.anima.server.sync

import kotlinx.coroutines.channels.Channel

/**
 * Tells a user's other devices that their feed has moved.
 *
 * The whole of the message is a sequence number the server already assigned, which is the
 * least it can say and still be useful: a device that hears it asks for the change through
 * `/sync` like any other, so nothing here has to know what changed or open a payload to
 * find out. Keeping it to a number is also what lets the same channel work the day payloads
 * are ciphertext.
 *
 * The alternative — every device polling — costs the same wake-ups spread over every device
 * that is *not* changing anything, which on a phone is the expensive half.
 */
class SyncNotifier(private val perUser: Int = MAX_LISTENERS_PER_USER) {

    /**
     * Conflated, one per listening connection: a device that is slow to read only ever needs
     * the newest sequence, and an older one it missed tells it nothing the newer one does not.
     * Conflation is also what keeps a stalled reader from holding up [notify] for everyone else.
     */
    private val listeners = HashMap<String, ArrayDeque<Channel<Long>>>()

    fun subscribe(userId: String): Channel<Long> {
        val channel = Channel<Long>(Channel.CONFLATED)
        synchronized(listeners) {
            val queue = listeners.getOrPut(userId) { ArrayDeque() }
            queue.addLast(channel)
            // A connection that died without unsubscribing is indistinguishable from one
            // thinking, so the cap drops the oldest rather than refusing the newest: the
            // device asking now is the one in front of somebody.
            while (queue.size > perUser) queue.removeFirst().close()
        }
        return channel
    }

    fun unsubscribe(userId: String, channel: Channel<Long>) {
        synchronized(listeners) {
            val queue = listeners[userId] ?: return@synchronized
            queue.remove(channel)
            if (queue.isEmpty()) listeners.remove(userId)
        }
        channel.close()
    }

    /** Announces [cursor] to every device of [userId], including the one that caused it. */
    fun notify(userId: String, cursor: Long) {
        val queue = synchronized(listeners) { listeners[userId]?.toList() } ?: return
        // Never suspends and never fails: a full or closed channel means that listener is
        // already going to hear something at least as new, or is gone.
        queue.forEach { it.trySend(cursor) }
    }

    /** Only for tests and logging — how many connections are currently listening. */
    fun listenerCount(userId: String): Int =
        synchronized(listeners) { listeners[userId]?.size ?: 0 }

    private companion object {
        /**
         * A person's devices, generously. Past this it is leaked connections rather than
         * hardware, and the oldest is the likeliest to be one.
         */
        const val MAX_LISTENERS_PER_USER = 8
    }
}
