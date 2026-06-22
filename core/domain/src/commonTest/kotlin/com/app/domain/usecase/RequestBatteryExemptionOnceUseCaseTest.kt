package com.app.domain.usecase

import com.app.domain.repository.BatteryOptimizationRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestBatteryExemptionOnceUseCaseTest {

    private class FakeBatteryOptimizationRepository(
        var exempt: Boolean = false,
        var asked: Boolean = false,
    ) : BatteryOptimizationRepository {
        var requests = 0
        override fun isExempt(): Boolean = exempt
        override fun hasAsked(): Boolean = asked
        override fun markAsked() { asked = true }
        override suspend fun requestExemption() { requests++ }
    }

    @Test
    fun first_run_marks_asked_and_requests() = runTest {
        val repo = FakeBatteryOptimizationRepository(exempt = false, asked = false)
        RequestBatteryExemptionOnceUseCase(repo)()

        assertTrue(repo.asked, "asked flag must flip on first run")
        assertEquals(1, repo.requests)
    }

    @Test
    fun does_not_request_when_already_asked() = runTest {
        val repo = FakeBatteryOptimizationRepository(exempt = false, asked = true)
        RequestBatteryExemptionOnceUseCase(repo)()

        assertEquals(0, repo.requests, "must not re-request once already asked")
    }

    @Test
    fun does_not_request_or_mark_when_already_exempt() = runTest {
        val repo = FakeBatteryOptimizationRepository(exempt = true, asked = false)
        RequestBatteryExemptionOnceUseCase(repo)()

        assertEquals(0, repo.requests, "must not request when already exempt")
        assertEquals(false, repo.asked, "no need to mark asked when already exempt")
    }

    @Test
    fun second_invocation_does_not_re_request() = runTest {
        val repo = FakeBatteryOptimizationRepository(exempt = false, asked = false)
        val useCase = RequestBatteryExemptionOnceUseCase(repo)

        useCase()
        useCase()

        assertEquals(1, repo.requests, "the prompt is shown at most once")
    }
}
