package com.example.myapplication.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioRouterTest {
    @Test
    fun fullGlassesModeKeepsRouteUntilVoiceSessionStops() {
        val port = FakeCommunicationRoute(RouteAttempt.Connected("Ray-Ban Meta"))
        val statuses = mutableListOf<VoiceRouteStatus>()
        val router = VoiceAudioRouter(port, statuses::add)

        assertEquals(VoiceInputRouteMode.GLASSES_ALWAYS, router.startVoiceSession())
        assertTrue(port.active)

        assertTrue(router.prepareCommandRoute())
        router.finishCommand()

        assertTrue(port.active)
        assertEquals(1, port.requestCount)

        router.stopVoiceSession()

        assertFalse(port.active)
        assertTrue(port.releaseCount >= 2)
        assertEquals("음성 마이크 경로 해제됨", statuses.last().message)
    }

    @Test
    fun rejectedFullRouteFallsBackToPhoneWakeAndRetriesForCommand() {
        val port =
            FakeCommunicationRoute(
                RouteAttempt.Unavailable("상시 연결 거부"),
                RouteAttempt.Connected("Ray-Ban Meta")
            )
        val statuses = mutableListOf<VoiceRouteStatus>()
        val router = VoiceAudioRouter(port, statuses::add)

        assertEquals(
            VoiceInputRouteMode.PHONE_WAKE_GLASSES_COMMAND,
            router.startVoiceSession()
        )
        assertFalse(port.active)
        assertTrue(statuses.last().error)

        assertTrue(router.prepareCommandRoute())
        assertTrue(port.active)

        router.finishCommand()

        assertFalse(port.active)
        assertEquals(
            VoiceInputRouteMode.PHONE_WAKE_GLASSES_COMMAND,
            router.activeMode
        )
        assertTrue(statuses.last().message.contains("폰 호출어 대기"))
    }

    @Test
    fun commandRouteFailureUsesPhoneForCurrentCommandAndLeavesNoRoute() {
        val port =
            FakeCommunicationRoute(
                RouteAttempt.Unavailable("상시 연결 거부"),
                RouteAttempt.Unavailable("명령 연결 거부")
            )
        val statuses = mutableListOf<VoiceRouteStatus>()
        val router = VoiceAudioRouter(port, statuses::add)

        router.startVoiceSession()

        assertFalse(router.prepareCommandRoute())
        assertFalse(port.active)
        assertTrue(statuses.last().message.contains("이번 명령은 폰 마이크"))
        assertTrue(statuses.last().error)
    }

    @Test
    fun lostFullRouteDowngradesToPhoneWakeCommandMode() {
        val port =
            FakeCommunicationRoute(
                RouteAttempt.Connected("Ray-Ban Meta"),
                RouteAttempt.Unavailable("연결 끊김")
            )
        val router = VoiceAudioRouter(communicationRoute = port, onStatus = {})

        router.startVoiceSession()
        port.active = false

        assertFalse(router.prepareCommandRoute())
        assertEquals(
            VoiceInputRouteMode.PHONE_WAKE_GLASSES_COMMAND,
            router.activeMode
        )
        assertFalse(port.active)
    }

    @Test
    fun acceptedButUnappliedWakeRouteDowngradesBeforeListeningStarts() {
        val port = FakeCommunicationRoute(RouteAttempt.Connected("Ray-Ban Meta"))
        val statuses = mutableListOf<VoiceRouteStatus>()
        val router = VoiceAudioRouter(port, statuses::add)

        router.startVoiceSession()
        port.active = false

        assertEquals(
            VoiceInputRouteMode.PHONE_WAKE_GLASSES_COMMAND,
            router.confirmWakeWordRoute()
        )
        assertFalse(port.active)
        assertTrue(statuses.last().message.contains("폰 호출어로 전환"))
    }
}

private class FakeCommunicationRoute(vararg attempts: RouteAttempt) : CommunicationRoutePort {
    private val pendingAttempts = ArrayDeque(attempts.toList())

    var active: Boolean = false
    var requestCount: Int = 0
    var releaseCount: Int = 0

    override fun requestGlassesRoute(): RouteAttempt {
        requestCount += 1
        val attempt = pendingAttempts.removeFirstOrNull()
            ?: RouteAttempt.Unavailable("준비된 결과 없음")
        active = attempt is RouteAttempt.Connected
        return attempt
    }

    override fun isGlassesRouteActive(): Boolean = active

    override fun releaseRoute() {
        releaseCount += 1
        active = false
    }
}
