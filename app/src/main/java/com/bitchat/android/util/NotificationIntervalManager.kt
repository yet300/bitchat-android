package com.bitchat.android.util

import jakarta.inject.Singleton

@Singleton
class NotificationIntervalManager {
  private var _lastNetworkNotificationTime = 0L
  val lastNetworkNotificationTime: Long
    get() = _lastNetworkNotificationTime

  val recentlySeenPeers: MutableSet<String> = mutableSetOf()

  fun setLastNetworkNotificationTime(notificationTime: Long) {
    _lastNetworkNotificationTime = notificationTime
  }
}