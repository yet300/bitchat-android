package com.yet.bitmessage.feature.onboarding.di

import com.yet.bitmessage.feature.onboarding.DefaultOnboardingComponentFactory
import com.yet.bitmessage.feature.onboarding.OnboardingComponent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo

@ContributesTo(AppScope::class)
@BindingContainer
abstract class OnboardingBindings {
    @Binds
    internal abstract val DefaultOnboardingComponentFactory.bindOnboardingComponentFactory: OnboardingComponent.Factory
}
