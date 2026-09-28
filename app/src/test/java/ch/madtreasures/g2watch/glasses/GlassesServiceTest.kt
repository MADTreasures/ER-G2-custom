package ch.madtreasures.g2watch.glasses

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android ends the app if a service started with startForegroundService() is stopped before it
 * called startForeground(). These tests play the start and stop orders that can happen when a
 * check is cancelled right after it began.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GlassesServiceTest {
    private val app = RuntimeEnvironment.getApplication()

    @Before
    fun grantBluetooth() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun runService(intent: android.content.Intent) =
        Robolectric.buildService(GlassesService::class.java, intent).create().startCommand(0, 1)

    @Test
    fun `a stop before the service is up waits for it`() {
        GlassesService.start(app, "Firmware wird geprüft")
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        GlassesService.stop(app)
        assertNull("stopped while still starting", shadowOf(app).nextStoppedService)

        val service = runService(started)
        assertNotNull(shadowOf(service.get()).lastForegroundNotification)
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        service.destroy()
    }

    @Test
    fun `a running service is stopped directly`() {
        GlassesService.start(app, "Firmware wird geprüft")
        val service = runService(shadowOf(app).nextStartedService)
        assertFalse(shadowOf(service.get()).isStoppedBySelf)

        GlassesService.stop(app)
        assertNotNull(shadowOf(app).nextStoppedService)
        service.destroy()
    }

    @Test
    fun `a second start while running does not start it again`() {
        GlassesService.start(app, "Firmware wird geprüft")
        val service = runService(shadowOf(app).nextStartedService)
        GlassesService.start(app, "Verbinde mit der Brille")
        assertNull(shadowOf(app).nextStartedService)

        GlassesService.stop(app)
        service.destroy()
    }

    @Test
    fun `without Bluetooth permission nothing starts`() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        GlassesService.start(app, "Firmware wird geprüft")
        assertNull(shadowOf(app).nextStartedService)
        GlassesService.stop(app)
        assertNull(shadowOf(app).nextStoppedService)
    }
}
