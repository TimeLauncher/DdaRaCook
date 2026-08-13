package com.example.myapplication

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.camera.CaptureFailureKind
import com.example.myapplication.camera.CaptureOutcome
import com.example.myapplication.camera.CapturePurpose
import com.example.myapplication.camera.CaptureRequest
import com.example.myapplication.camera.FakeCaptureBehavior
import com.example.myapplication.camera.FakeWearableCameraGateway
import com.example.myapplication.camera.WearableCameraState
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FakeCameraGatewayInstrumentedTest {
    private fun request(id: String) = CaptureRequest(id, "session", 1, CapturePurpose.INSPECTION)

    @Test
    fun duplicateCaptureReturnsBusyAndFirstCaptureCompletes() = runBlocking {
        val gateway = FakeWearableCameraGateway(ApplicationProvider.getApplicationContext())
        gateway.setState(WearableCameraState.Ready)
        gateway.setBehavior(FakeCaptureBehavior.Success(delayMs = 250L))

        val first = async { gateway.capture(request("first")) }
        delay(30L)
        val duplicate = gateway.capture(request("duplicate"))

        assertTrue(duplicate is CaptureOutcome.Failure)
        assertEquals(CaptureFailureKind.BUSY, (duplicate as CaptureOutcome.Failure).kind)
        assertTrue(first.await() is CaptureOutcome.Success)
        assertEquals(WearableCameraState.Ready, gateway.state.value)
    }

    @Test
    fun failedCaptureCleansUpToReadyState() = runBlocking {
        val gateway = FakeWearableCameraGateway(ApplicationProvider.getApplicationContext())
        gateway.setState(WearableCameraState.Ready)
        gateway.setBehavior(FakeCaptureBehavior.Failure(CaptureFailureKind.CAPTURE_TIMEOUT, true, "실패", "test", 1L))

        assertTrue(gateway.capture(request("failure")) is CaptureOutcome.Failure)
        assertEquals(WearableCameraState.Ready, gateway.state.value)
    }
}
