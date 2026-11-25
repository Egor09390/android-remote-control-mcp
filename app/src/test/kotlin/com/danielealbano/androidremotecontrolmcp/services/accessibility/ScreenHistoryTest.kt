package com.danielealbano.androidremotecontrolmcp.services.accessibility

import com.danielealbano.androidremotecontrolmcp.mcp.tools.GetScreenHistoryHandler
import com.danielealbano.androidremotecontrolmcp.testutil.PrivacyToolTestDoubles
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenHistoryTest {
    private var elapsed = 0L
    private val history = ScreenHistory({ elapsed }, { elapsed + 1_000 })
    private val camera = ScreenWindow(1, "com.sec.android.app.camera")
    private val chat = ScreenWindow(2, "com.openai.chatgpt")
    private val shutter = AccessibilityNodeData(
        id = "shutter",
        text = "Shutter",
        bounds = BoundsData(10, 20, 100, 200),
        visible = true,
        enabled = true,
        clickable = true,
    )
    private val snapshot = HistoricalScreenTree(shutter, ScreenInfo(1080, 2340, 510, "portrait"))

    @Test
    fun `window event is retained after switching away before any tree reads`() {
        history.start()
        history.recordWindowEvent(camera, "CameraActivity")
        history.recordWindowEvent(camera, "CameraActivity")
        elapsed += 1_000
        history.recordWindowEvent(chat, "MainActivity")

        val observations = history.recent(8)
        assertEquals(listOf(camera, chat), observations.map { it.window })
        assertEquals("CameraActivity", observations.first().sourceClass)
        assertFalse(observations.first().focused)
        assertNull(observations.first().tree)
    }

    @Test
    fun `tool returns the previous focused camera tree and rejects typed values from history`() = runTest {
        history.start()
        history.recordWindowEvent(camera, "CameraActivity")
        val field = shutter.copy(id = "input", text = "private input", editable = true, children = listOf(shutter))
        val screen = snapshot.copy(root = shutter.copy(children = listOf(field)).forScreenHistory())
        history.recordFocusedWindow(checkNotNull(history.sample()), camera, screen)
        elapsed += 1_000
        history.recordWindowEvent(chat, "MainActivity")
        val tool = GetScreenHistoryHandler(history, CompactTreeFormatter(), PrivacyToolTestDoubles.passthroughGate())

        val timeline = (tool.execute(null).content.single() as TextContent).text
        assertTrue(timeline.indexOf(camera.packageName) in 0 until timeline.indexOf(chat.packageName), timeline)
        assertTrue("focused_application_window" in timeline)
        assertFalse("historical_tree" in timeline)

        val args = buildJsonObject { put("include_nodes", true) }
        val output = (tool.execute(args).content.single() as TextContent).text
        assertTrue("Shutter" in output)
        assertTrue("10,20,100,200" in output)
        assertFalse("private input" in output)
    }

    @Test
    fun `capacity expiration and server stop bound the recording`() {
        assertNull(history.sample())
        history.start()
        for (id in 1..30) {
            history.recordWindowEvent(ScreenWindow(id, camera.packageName), null)
            elapsed++
        }
        assertEquals((7..30).toList(), history.recent(30).map { it.window.id })

        elapsed += ScreenHistory.RETENTION_MS
        assertTrue(history.recent(30).isEmpty())
        val pendingSample = checkNotNull(history.sample())
        history.stop()
        history.recordWindowEvent(camera, "CameraActivity")
        history.start()
        history.recordFocusedWindow(pendingSample, camera, snapshot)
        assertTrue(history.recent(8).isEmpty())
    }

    @Test
    fun `an earlier screen capture cannot replace a newer window event`() {
        history.start()
        history.recordWindowEvent(camera, "CameraActivity")
        val pendingSample = checkNotNull(history.sample())
        elapsed += 1_000
        history.recordWindowEvent(chat, "ChatActivity")
        history.recordFocusedWindow(pendingSample, camera, snapshot)

        assertEquals(listOf(camera, chat), history.recent(8).map { it.window })
        assertNull(history.recent(8).last().tree)
    }
}
