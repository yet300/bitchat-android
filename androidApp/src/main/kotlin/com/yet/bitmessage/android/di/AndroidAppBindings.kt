package com.yet.bitmessage.android.di

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.app.domain.repository.NotificationMutePolicy
import com.app.transport.notification.ServiceNotifier
import com.yet.bitmessage.android.ui.NotificationManager
import com.yet.bitmessage.android.notification.NotificationIntervalManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * App-resident bindings: the providers that touch :app-only artifacts (NotificationManager + its
 * resources) and therefore cannot move to a core module. The app-agnostic
 * transport SPIs live in [com.app.data.di.AndroidTransportBindings]; the data-layer platform
 * providers live in :core:data `DataAndroidBindings`.
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
}
