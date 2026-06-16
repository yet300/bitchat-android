package com.app.domain.model

/**
 * Per-channel message-retention policy. Messages older than [maxAgeMillis] are pruned when the
 * policy is applied; [KEEP_ALL] (null) never prunes.
 */
enum class RetentionPolicy(val maxAgeMillis: Long?) {
    KEEP_ALL(null),
    DAY(24L * 60 * 60 * 1000),
    WEEK(7L * 24 * 60 * 60 * 1000),
    MONTH(30L * 24 * 60 * 60 * 1000),
}
