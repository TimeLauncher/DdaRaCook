package com.example.myapplication.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class VoiceInputRouteMode {
    GLASSES_ALWAYS,
    PHONE_WAKE_GLASSES_COMMAND,
    PHONE_ONLY
}

data class VoiceRouteStatus(
    val mode: VoiceInputRouteMode = VoiceInputRouteMode.GLASSES_ALWAYS,
    val message: String = "안경 마이크 준비 전",
    val glassesRouteActive: Boolean = false,
    val error: Boolean = false
)

internal sealed interface RouteAttempt {
    data class Connected(val deviceName: String) : RouteAttempt
    data class Unavailable(val reason: String) : RouteAttempt
}

internal interface CommunicationRoutePort {
    fun requestGlassesRoute(): RouteAttempt
    fun isGlassesRouteActive(): Boolean
    fun releaseRoute()
}

/**
 * Owns the Android communication-audio request used by both the wake-word listener and command STT.
 *
 * The preferred mode keeps the Ray-Ban HFP route for the whole foreground cooking session. If that
 * request is rejected, wake-word detection stays on the phone microphone and each command gets one
 * fresh attempt to use the glasses microphone. Every exit path ultimately calls [releaseRoute].
 */
class VoiceAudioRouter internal constructor(
    private val communicationRoute: CommunicationRoutePort,
    private val onStatus: (VoiceRouteStatus) -> Unit,
    private val preferredMode: VoiceInputRouteMode = VoiceInputRouteMode.GLASSES_ALWAYS
) {
    constructor(
        context: Context,
        onStatus: (VoiceRouteStatus) -> Unit
    ) : this(
        communicationRoute = AndroidGlassesCommunicationRoute(context.applicationContext),
        onStatus = onStatus
    )

    var activeMode: VoiceInputRouteMode = preferredMode
        private set

    private var commandRouteActive = false

    fun startVoiceSession(): VoiceInputRouteMode {
        commandRouteActive = false
        communicationRoute.releaseRoute()

        if (preferredMode != VoiceInputRouteMode.GLASSES_ALWAYS) {
            activeMode = preferredMode
            publishPhoneWakeStatus("폰 마이크로 호출어를 기다립니다")
            return activeMode
        }

        return when (val attempt = communicationRoute.requestGlassesRoute()) {
            is RouteAttempt.Connected -> {
                activeMode = VoiceInputRouteMode.GLASSES_ALWAYS
                onStatus(
                    VoiceRouteStatus(
                        mode = activeMode,
                        message = "안경 마이크 상시 연결 · ${attempt.deviceName}",
                        glassesRouteActive = true
                    )
                )
                activeMode
            }
            is RouteAttempt.Unavailable -> {
                communicationRoute.releaseRoute()
                activeMode = VoiceInputRouteMode.PHONE_WAKE_GLASSES_COMMAND
                publishPhoneWakeStatus("안경 상시 연결 실패 · ${attempt.reason}", error = true)
                activeMode
            }
        }
    }

    /** Verifies that Android applied the accepted full-session request before Vosk opens input. */
    fun confirmWakeWordRoute(): VoiceInputRouteMode {
        if (activeMode != VoiceInputRouteMode.GLASSES_ALWAYS) return activeMode
        if (communicationRoute.isGlassesRouteActive()) return activeMode

        communicationRoute.releaseRoute()
        activeMode = VoiceInputRouteMode.PHONE_WAKE_GLASSES_COMMAND
        publishPhoneWakeStatus(
            message = "안경 상시 경로가 적용되지 않음 · 폰 호출어로 전환",
            error = true
        )
        return activeMode
    }

    /** Called immediately before command recognition starts. */
    fun prepareCommandRoute(): Boolean {
        if (
            activeMode == VoiceInputRouteMode.GLASSES_ALWAYS &&
            communicationRoute.isGlassesRouteActive()
        ) {
            commandRouteActive = true
            return true
        }

        if (activeMode == VoiceInputRouteMode.PHONE_ONLY) return false

        return when (val attempt = communicationRoute.requestGlassesRoute()) {
            is RouteAttempt.Connected -> {
                commandRouteActive = true
                onStatus(
                    VoiceRouteStatus(
                        mode = activeMode,
                        message = if (activeMode == VoiceInputRouteMode.GLASSES_ALWAYS) {
                            "안경 마이크 경로 복구 · ${attempt.deviceName}"
                        } else {
                            "명령을 안경 마이크로 듣는 중 · ${attempt.deviceName}"
                        },
                        glassesRouteActive = true
                    )
                )
                true
            }
            is RouteAttempt.Unavailable -> {
                commandRouteActive = false
                communicationRoute.releaseRoute()
                if (activeMode == VoiceInputRouteMode.GLASSES_ALWAYS) {
                    activeMode = VoiceInputRouteMode.PHONE_WAKE_GLASSES_COMMAND
                }
                publishPhoneWakeStatus(
                    message = "안경 명령 경로 실패 · 이번 명령은 폰 마이크 · ${attempt.reason}",
                    error = true
                )
                false
            }
        }
    }

    /** Releases only the short-lived command route. Full-glasses mode remains connected. */
    fun finishCommand() {
        if (!commandRouteActive) return
        commandRouteActive = false
        if (activeMode == VoiceInputRouteMode.PHONE_WAKE_GLASSES_COMMAND) {
            communicationRoute.releaseRoute()
            publishPhoneWakeStatus("폰 호출어 대기 · 다음 명령에서 안경 마이크 재연결")
        }
    }

    fun stopVoiceSession() {
        commandRouteActive = false
        communicationRoute.releaseRoute()
        activeMode = preferredMode
        onStatus(
            VoiceRouteStatus(
                mode = activeMode,
                message = "음성 마이크 경로 해제됨"
            )
        )
    }

    private fun publishPhoneWakeStatus(message: String, error: Boolean = false) {
        onStatus(
            VoiceRouteStatus(
                mode = activeMode,
                message = message,
                glassesRouteActive = false,
                error = error
            )
        )
    }
}

private class AndroidGlassesCommunicationRoute(context: Context) : CommunicationRoutePort {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)

    private var previousAudioMode: Int? = null
    private var didChangeAudioMode = false
    private var requestedDeviceId: Int? = null

    override fun requestGlassesRoute(): RouteAttempt {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return RouteAttempt.Unavailable("Android 12 이상 필요")
        }
        if (
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return RouteAttempt.Unavailable("Bluetooth 연결 권한 필요")
        }
        if (isGlassesRouteActive()) {
            val name = audioManager.communicationDevice?.productName?.toString().orEmpty()
            return RouteAttempt.Connected(name.ifBlank { "Ray-Ban Meta" })
        }

        releaseRoute()
        val target = findRayBanCommunicationDevice()
            ?: return RouteAttempt.Unavailable("Ray-Ban 통신 장치를 찾지 못함")

        previousAudioMode = audioManager.mode
        return try {
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                didChangeAudioMode = true
            }
            if (!audioManager.setCommunicationDevice(target)) {
                rollbackFailedRequest()
                RouteAttempt.Unavailable("Android가 HFP 경로 요청을 거부함")
            } else {
                requestedDeviceId = target.id
                RouteAttempt.Connected(
                    target.productName?.toString()?.takeIf(String::isNotBlank) ?: "Ray-Ban Meta"
                )
            }
        } catch (error: Exception) {
            rollbackFailedRequest()
            RouteAttempt.Unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    override fun isGlassesRouteActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val requestedId = requestedDeviceId ?: return false
        return try {
            val selected = audioManager.communicationDevice ?: return false
            selected.id == requestedId ||
                (selected.isBluetoothCommunicationDevice() && selected.rayBanName())
        } catch (_: SecurityException) {
            false
        }
    }

    override fun releaseRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && requestedDeviceId != null) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        requestedDeviceId = null
        restoreAudioMode()
    }

    private fun rollbackFailedRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        requestedDeviceId = null
        restoreAudioMode()
    }

    private fun restoreAudioMode() {
        val previous = previousAudioMode
        previousAudioMode = null
        if (didChangeAudioMode && previous != null) {
            runCatching {
                if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = previous
                }
            }
        }
        didChangeAudioMode = false
    }

    private fun findRayBanCommunicationDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val namedInputs =
                audioManager
                    .getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .filter { it.isBluetoothCommunicationDevice() }
                    .filter { it.rayBanName() }
            val outputs =
                audioManager.availableCommunicationDevices
                    .filter { it.isBluetoothCommunicationDevice() }

            outputs.firstOrNull { it.rayBanName() }
                ?: outputs.singleOrNull()?.takeIf { namedInputs.isNotEmpty() }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun AudioDeviceInfo.isBluetoothCommunicationDevice(): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun AudioDeviceInfo.rayBanName(): Boolean {
        val normalized = productName?.toString().orEmpty().lowercase()
        return normalized.contains("ray-ban") ||
            normalized.contains("rayban") ||
            normalized.contains("meta") ||
            normalized.contains("glasses")
    }
}
