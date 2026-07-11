package com.app.database.di

import com.app.common.AppDispatchers
import com.app.database.dao.ChannelDao
import com.app.database.dao.ContactDao
import com.app.database.dao.ConversationDao
import com.app.database.dao.BoardDao
import com.app.database.dao.CourierDao
import com.app.database.dao.GroupDao
import com.app.database.dao.VouchDao
import com.app.database.dao.ConversationPrefDao
import com.app.database.dao.GeohashDao
import com.app.database.dao.MessageDao
import com.app.database.dao.OutboxDao
import com.app.database.dao.SecureSettingDao
import com.app.database.db.DatabaseDriverFactory
import com.app.database.db.DatabaseManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * App-scoped DI for the database layer: the single [DatabaseManager] and one DAO per area. The
 * platform [DatabaseDriverFactory] binding is contributed by the platform driver factory
 * (AndroidDatabaseDriverFactory / NativeDatabaseDriverFactory).
 */
@ContributesTo(AppScope::class)
@BindingContainer
object DatabaseBindings {

    @SingleIn(AppScope::class)
    @Provides
    fun provideDatabaseManager(
        driverFactory: DatabaseDriverFactory,
        dispatchers: AppDispatchers,
    ): DatabaseManager = DatabaseManager(driverFactory, dispatchers)

    @SingleIn(AppScope::class)
    @Provides
    fun provideMessageDao(manager: DatabaseManager, dispatchers: AppDispatchers): MessageDao =
        MessageDao(manager, dispatchers)

    @SingleIn(AppScope::class)
    @Provides
    fun provideConversationDao(manager: DatabaseManager, dispatchers: AppDispatchers): ConversationDao =
        ConversationDao(manager, dispatchers)

    @SingleIn(AppScope::class)
    @Provides
    fun provideContactDao(manager: DatabaseManager, dispatchers: AppDispatchers): ContactDao =
        ContactDao(manager, dispatchers)

    @SingleIn(AppScope::class)
    @Provides
    fun provideChannelDao(manager: DatabaseManager, dispatchers: AppDispatchers): ChannelDao =
        ChannelDao(manager, dispatchers)

    @SingleIn(AppScope::class)
    @Provides
    fun provideGeohashDao(manager: DatabaseManager, dispatchers: AppDispatchers): GeohashDao =
        GeohashDao(manager, dispatchers)

    @SingleIn(AppScope::class)
    @Provides
    fun provideConversationPrefDao(manager: DatabaseManager, dispatchers: AppDispatchers): ConversationPrefDao =
        ConversationPrefDao(manager, dispatchers)

    @SingleIn(AppScope::class)
    @Provides
    fun provideSecureSettingDao(manager: DatabaseManager, dispatchers: AppDispatchers): SecureSettingDao =
        SecureSettingDao(manager, dispatchers)

    @SingleIn(AppScope::class)
    @Provides
    fun provideOutboxDao(manager: DatabaseManager, dispatchers: AppDispatchers): OutboxDao =
        OutboxDao(manager, dispatchers)

    @SingleIn(AppScope::class)
    @Provides
    fun provideVouchDao(manager: DatabaseManager, dispatchers: AppDispatchers): VouchDao =
        VouchDao(manager, dispatchers)

    @SingleIn(AppScope::class)
    @Provides
    fun provideCourierDao(manager: DatabaseManager, dispatchers: AppDispatchers): CourierDao =
        CourierDao(manager, dispatchers)

    @SingleIn(AppScope::class)
    @Provides
    fun provideGroupDao(manager: DatabaseManager, dispatchers: AppDispatchers): GroupDao =
        GroupDao(manager, dispatchers)

    @SingleIn(AppScope::class)
    @Provides
    fun provideBoardDao(manager: DatabaseManager, dispatchers: AppDispatchers): BoardDao =
        BoardDao(manager, dispatchers)
}
