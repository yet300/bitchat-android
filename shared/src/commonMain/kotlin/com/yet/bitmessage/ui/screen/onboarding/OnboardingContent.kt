package com.yet.bitmessage.ui.screen.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.onboarding.OnboardingComponent
import com.yet.bitmessage.feature.onboarding.OnboardingStep
import com.yet.bitmessage.shared.resources.Res
import com.yet.bitmessage.shared.resources.onboarding_action_back
import com.yet.bitmessage.shared.resources.onboarding_action_continue
import com.yet.bitmessage.shared.resources.onboarding_action_enable_nearby
import com.yet.bitmessage.shared.resources.onboarding_action_enable_notifications
import com.yet.bitmessage.shared.resources.onboarding_action_get_started
import com.yet.bitmessage.shared.resources.onboarding_action_not_now
import com.yet.bitmessage.shared.resources.onboarding_action_start
import com.yet.bitmessage.shared.resources.onboarding_done_body
import com.yet.bitmessage.shared.resources.onboarding_done_title
import com.yet.bitmessage.shared.resources.onboarding_nearby_body
import com.yet.bitmessage.shared.resources.onboarding_nearby_title
import com.yet.bitmessage.shared.resources.onboarding_nickname_body
import com.yet.bitmessage.shared.resources.onboarding_nickname_title
import com.yet.bitmessage.shared.resources.onboarding_notifications_body
import com.yet.bitmessage.shared.resources.onboarding_notifications_title
import com.yet.bitmessage.shared.resources.onboarding_welcome_body
import com.yet.bitmessage.shared.resources.onboarding_welcome_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * First-run onboarding UI. A2a placeholder: a single text-first screen per step that proves the
 * flow (Welcome → Nickname → Nearby → Notifications → Done) end to end. A2b replaces the Welcome
 * step with the swipeable value carousel + Valkyrie glyphs.
 */
@Composable
fun OnboardingContent(component: OnboardingComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()
    val step = model.step

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            text = stringResource(titleFor(step)),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.padding(8.dp))
        Text(
            text = stringResource(bodyFor(step)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (step == OnboardingStep.NICKNAME) {
            Spacer(Modifier.padding(12.dp))
            OutlinedTextField(
                value = model.nickname,
                onValueChange = component::onNicknameChanged,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = component::onPrimaryClicked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(primaryLabelFor(step)))
            }
            if (step == OnboardingStep.NEARBY || step == OnboardingStep.NOTIFICATIONS) {
                TextButton(onClick = component::onSkipClicked) {
                    Text(stringResource(Res.string.onboarding_action_not_now))
                }
            }
            if (step != OnboardingStep.WELCOME) {
                TextButton(onClick = component::onBackClicked) {
                    Text(stringResource(Res.string.onboarding_action_back))
                }
            }
        }
    }
}

private fun titleFor(step: OnboardingStep): StringResource = when (step) {
    OnboardingStep.WELCOME -> Res.string.onboarding_welcome_title
    OnboardingStep.NICKNAME -> Res.string.onboarding_nickname_title
    OnboardingStep.NEARBY -> Res.string.onboarding_nearby_title
    OnboardingStep.NOTIFICATIONS -> Res.string.onboarding_notifications_title
    OnboardingStep.DONE -> Res.string.onboarding_done_title
}

private fun bodyFor(step: OnboardingStep): StringResource = when (step) {
    OnboardingStep.WELCOME -> Res.string.onboarding_welcome_body
    OnboardingStep.NICKNAME -> Res.string.onboarding_nickname_body
    OnboardingStep.NEARBY -> Res.string.onboarding_nearby_body
    OnboardingStep.NOTIFICATIONS -> Res.string.onboarding_notifications_body
    OnboardingStep.DONE -> Res.string.onboarding_done_body
}

private fun primaryLabelFor(step: OnboardingStep): StringResource = when (step) {
    OnboardingStep.WELCOME -> Res.string.onboarding_action_get_started
    OnboardingStep.NICKNAME -> Res.string.onboarding_action_continue
    OnboardingStep.NEARBY -> Res.string.onboarding_action_enable_nearby
    OnboardingStep.NOTIFICATIONS -> Res.string.onboarding_action_enable_notifications
    OnboardingStep.DONE -> Res.string.onboarding_action_start
}
