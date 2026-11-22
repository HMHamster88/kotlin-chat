package org.kotlinchat.server.core

import java.util.concurrent.ConcurrentHashMap

/** Simple token bucket; one instance per session and per message class. */
class TokenBucket(
    private val ratePerSecond: Double,
    private val burst: Int,
) {
    private var tokens = burst.toDouble()
    private var lastRefillNanos = System.nanoTime()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0
        lastRefillNanos = now
        tokens = minOf(burst.toDouble(), tokens + elapsedSeconds * ratePerSecond)
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}

/**
 * Per-IP guard against password guessing.
 *
 * Three failures inside a minute lock the address out for 30 s; every further failure
 * in the window extends the lockout by another 30 s.
 */
class AuthThrottle(
    private val maxFailures: Int = 3,
    private val windowMillis: Long = 60_000,
    private val lockoutMillis: Long = 30_000,
) {
    private class Attempts {
        var failures: Int = 0
        var lastFailureAt: Long = 0
        var lockedUntil: Long = 0
    }

    private val attempts = ConcurrentHashMap<String, Attempts>()

    fun isLocked(remoteHost: String): Boolean {
        val entry = attempts[remoteHost] ?: return false
        synchronized(entry) {
            return System.currentTimeMillis() < entry.lockedUntil
        }
    }

    fun recordFailure(remoteHost: String) {
        val now = System.currentTimeMillis()
        val entry = attempts.computeIfAbsent(remoteHost) { Attempts() }
        synchronized(entry) {
            if (now - entry.lastFailureAt > windowMillis) entry.failures = 0
            entry.failures++
            entry.lastFailureAt = now
            if (entry.failures >= maxFailures) {
                entry.lockedUntil = now + lockoutMillis * (entry.failures - maxFailures + 1)
            }
        }
    }

    fun recordSuccess(remoteHost: String) {
        attempts.remove(remoteHost)
    }
}
