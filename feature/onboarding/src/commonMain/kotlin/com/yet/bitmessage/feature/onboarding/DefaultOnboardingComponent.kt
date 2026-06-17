package com.yet.bitmessage.feature.onboarding

import com.app.common.decompose.asValue
import com.app.common.decompose.coroutineScope
import com.app.domain.repository.ConnectivityRepository
import com.app.domain.repository.NotificationPermissionRepository
import com.app.domain.repository.OnboardingRepository
import com.app.domain.repository.SettingsRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yet.bitmessage.feature.onboarding.store.OnboardingStore
import com.yet.bitmessage.feature.onboarding.store.OnboardingStoreFactory
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.launch

internal class DefaultOnboardingComponent(
    componentContext: ComponentContext,
    storeFactory: OnboardingStoreFactory,
    private val onFinished: () -> Unit,
) : OnboardingComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<OnboardingComponent.Model> = store.asValue().map { state ->
        OnboardingComponent.Model(step = state.step, nickname = state.nickname)
    }

    init {
        // Completion is persisted by the store; navigate away only once the flag is written.
        coroutineScope().launch {
            store.labels.collect { label ->
                when (label) {
                    OnboardingStore.Label.Finished -> onFinished()
                }
            }
        }
    }

    override fun onNicknameChanged(text: String) = store.accept(OnboardingStore.Intent.NicknameChanged(text))

    override fun onPrimaryClicked() =
        if (store.state.step == OnboardingStep.DONE) {
            store.accept(OnboardingStore.Intent.Finish)
        } else {
            store.accept(OnboardingStore.Intent.Primary)
        }

    override fun onSkipClicked() = store.accept(OnboardingStore.Intent.Skip)

    override fun onBackClicked() = store.accept(OnboardingStore.Intent.Back)
}

@Inject
internal class DefaultOnboardingComponentFactory(
    private val storeFactory: StoreFactory,
    private val settingsRepository: SettingsRepository,
    private val onboardingRepository: OnboardingRepository,
    private val connectivityRepository: ConnectivityRepository,
    private val notificationPermissionRepository: NotificationPermissionRepository,
) : OnboardingComponent.Factory {
    override fun create(componentContext: ComponentContext, onFinished: () -> Unit): OnboardingComponent =
        DefaultOnboardingComponent(
            componentContext = componentContext,
            storeFactory = OnboardingStoreFactory(
                storeFactory = storeFactory,
                settingsRepository = settingsRepository,
                onboardingRepository = onboardingRepository,
                connectivityRepository = connectivityRepository,
                notificationPermissionRepository = notificationPermissionRepository,
            ),
            onFinished = onFinished,
        )
}
