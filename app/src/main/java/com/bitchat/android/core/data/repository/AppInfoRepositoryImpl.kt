package com.bitchat.android.core.data.repository

import android.content.Context
import com.bitchat.android.core.domain.repository.AppInfoRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class AppInfoRepositoryImpl @Inject constructor(
    private val context: Context
) : AppInfoRepository {

    override fun getVersionName(): String {
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    }

}