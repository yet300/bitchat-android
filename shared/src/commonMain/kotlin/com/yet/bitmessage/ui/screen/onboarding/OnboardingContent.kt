package com.yet.bitmessage.ui.screen.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.onboarding.OnboardingComponent
import com.yet.bitmessage.feature.onboarding.OnboardingStep
import com.yet.bitmessage.ui.component.icon.BluetoothSearching
import com.yet.bitmessage.ui.component.icon.LockPerson
import com.yet.bitmessage.ui.component.icon.NoAccounts
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
import com.yet.bitmessage.shared.resources.onboarding_value_e2e_body
import com.yet.bitmessage.shared.resources.onboarding_value_e2e_title
import com.yet.bitmessage.shared.resources.onboarding_value_mesh_body
import com.yet.bitmessage.shared.resources.onboarding_value_mesh_title
import com.yet.bitmessage.shared.resources.onboarding_value_noaccount_body
import com.yet.bitmessage.shared.resources.onboarding_value_noaccount_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * First-run onboarding UI. The Welcome step is a swipeable value carousel (one value per page,
 * dot pager); the remaining steps are a single centered page each. A3 wires the real nearby /
 * notification permission requests behind the primer buttons.
 */
@Composable
fun OnboardingContent(component: OnboardingComponent, modifier: Modifier = Modifier) {
    val model by component.model.subscribeAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (model.step == OnboardingStep.WELCOME) {
            WelcomeCarousel(modifier = Modifier.weight(1f))
        } else {
            StepBody(model = model, onNicknameChanged = component::onNicknameChanged, modifier = Modifier.weight(1f))
        }

        StepActions(step = model.step, component = component)
    }
}

private data class ValuePage(val glyph: ImageVector, val title: StringResource, val body: StringResource)

private val valuePages = listOf(
    ValuePage(BluetoothSearching, Res.string.onboarding_value_mesh_title, Res.string.onboarding_value_mesh_body),
    ValuePage(NoAccounts, Res.string.onboarding_value_noaccount_title, Res.string.onboarding_value_noaccount_body),
    ValuePage(LockPerson, Res.string.onboarding_value_e2e_title, Res.string.onboarding_value_e2e_body),
)

@Composable
private fun WelcomeCarousel(modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState(pageCount = { valuePages.size })

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            val value = valuePages[page]
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = value.glyph,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(96.dp),
                )
                Spacer(Modifier.padding(16.dp))
                Text(
                    text = stringResource(value.title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.padding(8.dp))
                Text(
                    text = stringResource(value.body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.padding(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(valuePages.size) { index ->
                val selected = pagerState.currentPage == index
                Box(
                    Modifier
                        .size(if (selected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        ),
                )
            }
        }
    }
}

@Composable
private fun StepBody(
    model: OnboardingComponent.Model,
    onNicknameChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(titleFor(model.step)),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.padding(8.dp))
        Text(
            text = stringResource(bodyFor(model.step)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (model.step == OnboardingStep.NICKNAME) {
            Spacer(Modifier.padding(12.dp))
            OutlinedTextField(
                value = model.nickname,
                onValueChange = onNicknameChanged,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StepActions(step: OnboardingStep, component: OnboardingComponent) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(onClick = component::onPrimaryClicked, modifier = Modifier.fillMaxWidth()) {
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

private fun titleFor(step: OnboardingStep): StringResource = when (step) {
    OnboardingStep.NICKNAME -> Res.string.onboarding_nickname_title
    OnboardingStep.NEARBY -> Res.string.onboarding_nearby_title
    OnboardingStep.NOTIFICATIONS -> Res.string.onboarding_notifications_title
    OnboardingStep.DONE -> Res.string.onboarding_done_title
    OnboardingStep.WELCOME -> Res.string.onboarding_value_mesh_title // unused: Welcome renders the carousel
}

private fun bodyFor(step: OnboardingStep): StringResource = when (step) {
    OnboardingStep.NICKNAME -> Res.string.onboarding_nickname_body
    OnboardingStep.NEARBY -> Res.string.onboarding_nearby_body
    OnboardingStep.NOTIFICATIONS -> Res.string.onboarding_notifications_body
    OnboardingStep.DONE -> Res.string.onboarding_done_body
    OnboardingStep.WELCOME -> Res.string.onboarding_value_mesh_body // unused: Welcome renders the carousel
}

private fun primaryLabelFor(step: OnboardingStep): StringResource = when (step) {
    OnboardingStep.WELCOME -> Res.string.onboarding_action_get_started
    OnboardingStep.NICKNAME -> Res.string.onboarding_action_continue
    OnboardingStep.NEARBY -> Res.string.onboarding_action_enable_nearby
    OnboardingStep.NOTIFICATIONS -> Res.string.onboarding_action_enable_notifications
    OnboardingStep.DONE -> Res.string.onboarding_action_start
}
