package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.BitchatApplication

/**
 * Graph entry point for Android framework classes (Activity, Service, Receiver,
 * Composables via LocalContext) that cannot be constructor-injected before Phase C.
 */
val Context.appGraph: AppGraph
    get() = (applicationContext as BitchatApplication).appGraph
