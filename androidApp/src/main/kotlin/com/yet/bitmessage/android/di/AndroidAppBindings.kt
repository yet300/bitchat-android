package com.yet.bitmessage.android.di

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.app.transport.NicknameSource
import com.app.domain.repository.NotificationMutePolicy
import com.app.transport.notification.ServiceNotifier
import com.app.data.DataManager
import com.yet.bitmessage.android.ui.NotificationManager
import com.yet.bitmessage.android.notification.NotificationIntervalManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import com.app.common.settings.SettingsStore
import dev.zacsweers.metro.SingleIn

/**
 * App-resident bindings: the providers that touch :app-only artifacts (NotificationManager + its
 * resources, the legacy DataManager) and therefore cannot move to :shared. The app-agnostic
 * transport SPIs live in [com.yet.bitmessage.android.di.AndroidTransportBindings] in :shared.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object AndroidAppBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideServiceNotifier(
        context: Context,
        notificationMutePolicy: NotificationMutePolicy,
    ): ServiceNotifier = NotificationManager(
        context,
        NotificationManagerCompat.from(context),
        NotificationIntervalManager(),
        notificationMutePolicy,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideNicknameSource(settings: SettingsStore): NicknameSource {
        val dataManager = DataManager(settings)
        return NicknameSource { fallback ->
            try {
                dataManager.loadNickname().ifBlank { fallback }
            } catch (_: Exception) {
                fallback
            }
        }
    }
}
