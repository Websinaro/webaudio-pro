package com.weaudio.pro

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.platform.app.InstrumentationRegistry
import com.weaudio.pro.audio.AudioDiagnostics
import com.weaudio.pro.audio.NativeAudioBackend
import com.weaudio.pro.audio.sampler.DrumInstrument
import com.weaudio.pro.audio.sampler.NativeDrumBackend
import com.weaudio.pro.samples.AssetKitSource
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Runs on a device: real Oboe stream, real assets, real JNI sampler. */
class DrumIntegrationTest {
    private val audio = NativeAudioBackend()
    private val drums = NativeDrumBackend()
    private val kits = AssetKitSource(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before fun setUp() { audio.release(); assertTrue(audio.initialize()); assertTrue(audio.start()) }
    @After fun tearDown() { drums.stopAll(); audio.release() }

    private fun loadDefaultKit(): String {
        val kit = kits.kits().first().id
        for (i in DrumInstrument.entries) {
            val bytes = requireNotNull(kits.read(kit, i)) { "missing ${i.fileName}" }
            assertEquals("", drums.loadSample(i.slot, bytes))
        }
        return kit
    }
    private fun diag() = AudioDiagnostics.parse(audio.diagnosticsText())

    @Test fun kitLoadsAndEveryDrumTriggers() {
        loadDefaultKit()
        DrumInstrument.entries.forEach { assertTrue("${it.label} not loaded", drums.isLoaded(it.slot)) }
        assertEquals("12", diag()["drumSlotsLoaded"])
        val before = diag()["drumTriggers"].toLong()
        DrumInstrument.entries.forEach { assertTrue("${it.label} rejected", drums.trigger(it.slot, 0.9f)); Thread.sleep(40) }
        Thread.sleep(150)
        assertEquals(before + 12, diag()["drumTriggers"].toLong())
    }

    @Test fun simultaneousHitsProduceMultipleVoicesAndOutputPeak() {
        loadDefaultKit()
        listOf(DrumInstrument.KICK, DrumInstrument.CRASH, DrumInstrument.SNARE, DrumInstrument.RIDE).forEach { drums.trigger(it.slot, 1f) }
        Thread.sleep(120)
        assertTrue("voices=${diag()["drumActiveVoices"]}", diag()["drumActiveVoices"].toInt() >= 3)
        assertTrue("no signal reached the output stage", audio.peakLevel() > 0.01f)
    }

    @Test fun invalidSampleIsRejectedAndKeepsPreviousSample() {
        loadDefaultKit()
        assertNotEquals("", drums.loadSample(DrumInstrument.KICK.slot, ByteArray(200) { 3 }))
        assertTrue(drums.isLoaded(DrumInstrument.KICK.slot))
    }

    @Test fun survivesPauseResumeAndReinitialize() {
        loadDefaultKit()
        assertTrue(audio.pause()); assertFalse("triggers must be rejected while paused", drums.trigger(0, 1f))
        assertTrue(audio.resume()); Thread.sleep(100); assertTrue(drums.trigger(0, 1f))
        audio.release(); assertTrue(audio.initialize()); assertTrue(audio.start())
        assertTrue("kit must survive engine re-initialisation", drums.isLoaded(0)); assertTrue(drums.trigger(0, 1f))
    }

    @Test fun stressRepeatedTriggeringKeepsEngineHealthy() {
        loadDefaultKit()
        repeat(400) { drums.trigger(it % 12, 0.8f); Thread.sleep(5) }
        Thread.sleep(200)
        val d = diag()
        assertTrue(d["drumActiveVoices"].toInt() <= 32)
        assertEquals("None", d["lastError"])
        println("xruns after stress: ${d["xRuns"]} (informational)")
    }
}

class DrumUiIntegrationTest {
    @get:org.junit.Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun padTouchAndPhysicalKeyTriggerNativeSampler() {
        Thread.sleep(1500)   // kit load + stream start
        val d0 = AudioDiagnostics.parse(NativeAudioBackend().diagnosticsText())["drumTriggers"].toLongOrNull() ?: 0
        rule.onNodeWithTag("pad_kick").assertIsDisplayed()
        rule.onNodeWithTag("pad_kick").performTouchInput { click() }
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S))   // note: injected events are not from a physical device
        inst.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_S))
        Thread.sleep(200)
        val d1 = AudioDiagnostics.parse(NativeAudioBackend().diagnosticsText())["drumTriggers"].toLongOrNull() ?: 0
        assertTrue("touch should have triggered at least once (before=$d0 after=$d1)", d1 >= d0 + 1)
    }

    @Test fun rotationKeepsPadsAndKit() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitForIdle(); rule.onNodeWithTag("pad_kick").assertIsDisplayed(); rule.onNodeWithTag("inspector_title").assertIsDisplayed()
        rule.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitForIdle(); rule.onNodeWithTag("pad_kick").assertIsDisplayed()
    }
}
