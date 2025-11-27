package com.bitchat.android.core.domain.repository

interface AppInfoRepository {
    fun getVersionName() : String
}