package com.bitchat.android.feature.about

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.bitchat.android.core.common.asValue
import com.bitchat.android.feature.about.integration.stateToModel
import com.bitchat.android.feature.about.store.AboutStore
import com.bitchat.android.feature.about.store.AboutStoreFactory
import com.bitchat.android.ui.theme.ThemePreference
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DefaultAboutComponent(
    componentContext: ComponentContext,
    private val onDismissCallback: () -> Unit
) : AboutComponent, ComponentContext by componentContext, KoinComponent {

    private val storeFactory: StoreFactory by inject()

    private val store = instanceKeeper.getStore {
        AboutStoreFactory(storeFactory).create()
    }

    override val model: Value<AboutComponent.Model> = store.asValue().map(stateToModel)

    override fun onThemeChanged(theme: ThemePreference) {
        store.accept(AboutStore.Intent.SetTheme(theme))
    }

    override fun onPowEnabledChanged(enabled: Boolean) {
        store.accept(AboutStore.Intent.SetPowEnabled(enabled))
    }

    override fun onPowDifficultyChanged(difficulty: Int) {
        store.accept(AboutStore.Intent.SetPowDifficulty(difficulty))
    }

    override fun onTorModeChanged(enabled: Boolean) {
        store.accept(AboutStore.Intent.SetTorMode(enabled))
    }

    override fun onDismiss() {
        onDismissCallback()
    }
}
