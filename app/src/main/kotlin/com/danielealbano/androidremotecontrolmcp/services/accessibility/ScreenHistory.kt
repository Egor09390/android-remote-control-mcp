package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/** Short, in-memory history for the current MCP server run. */
@Singleton
class ScreenHistory internal constructor(
    private val elapsedMillis: () -> Long,
    private val wallMillis: () -> Long,
) {
    @Inject
    constructor() : this(SystemClock::elapsedRealtime, System::currentTimeMillis)

    private val observations = ArrayDeque<ScreenObservation>()
    private var recording = false
    private var generation = 0L

    @Synchronized
    internal fun start() {
        clear()
        recording = true
    }

    @Synchronized
    internal fun stop() {
        recording = false
        clear()
    }

    @Synchronized
    internal fun clear() {
        generation++
        observations.clear()
    }

    @Synchronized
    internal fun sample(): ScreenSample? =
        if (recording) ScreenSample(generation, elapsedMillis(), wallMillis()) else null

    @Synchronized
    internal fun recordWindowEvent(window: ScreenWindow, sourceClass: String?) {
        if (!recording || window.id < 0 || window.packageName.isBlank()) return
        val now = elapsedMillis()
        expire(now)
        if (observations.lastOrNull()?.window == window) return
        append(ScreenObservation(window, sourceClass?.take(HISTORY_IDENTIFIER_LENGTH), now, wallMillis()))
    }

    @Synchronized
    internal fun recordFocusedWindow(
        sample: ScreenSample,
        window: ScreenWindow,
        tree: HistoricalScreenTree?,
    ) {
        if (!recording || sample.generation != generation) return
        val now = elapsedMillis()
        expire(now)
        if (now - sample.elapsedMs >= RETENTION_MS) return
        val latest = observations.lastOrNull()
        if (latest?.window == window) {
            if (sample.elapsedMs >= latest.elapsedMs) {
                observations[observations.lastIndex] = latest.copy(
                    focused = true,
                    tree = latest.tree ?: tree,
                )
            }
        } else if (latest == null || sample.elapsedMs >= latest.elapsedMs) {
            append(ScreenObservation(window, null, sample.elapsedMs, sample.wallMs, focused = true, tree = tree))
        }
    }

    @Synchronized
    internal fun recent(limit: Int): List<ScreenObservation> {
        expire(elapsedMillis())
        return observations.takeLast(limit)
    }

    private fun append(observation: ScreenObservation) {
        if (observations.size == CAPACITY) observations.removeFirst()
        observations.addLast(observation)
    }

    private fun expire(now: Long) {
        while (observations.firstOrNull()?.let { now - it.elapsedMs >= RETENTION_MS } == true) {
            observations.removeFirst()
        }
    }

    companion object {
        internal const val RETENTION_MS = 60_000L
        internal const val CAPACITY = 24
    }
}

internal data class ScreenWindow(val id: Int, val packageName: String)

internal data class ScreenSample(val generation: Long, val elapsedMs: Long, val wallMs: Long)

internal data class ScreenObservation(
    val window: ScreenWindow,
    val sourceClass: String?,
    val elapsedMs: Long,
    val wallMs: Long,
    val focused: Boolean = false,
    val tree: HistoricalScreenTree? = null,
)

internal data class HistoricalScreenTree(val root: AccessibilityNodeData, val screenInfo: ScreenInfo)

/** Do not retain typed input, password values, WebView URLs, or unbounded labels. */
internal fun AccessibilityNodeData.forScreenHistory(): AccessibilityNodeData {
    val sensitiveField = editable || isPassword
    val keepLabel = visible && !sensitiveField
    return copy(
        className = className?.take(HISTORY_IDENTIFIER_LENGTH),
        resourceId = resourceId?.take(HISTORY_IDENTIFIER_LENGTH),
        text = text?.takeIf { keepLabel }?.take(HISTORY_LABEL_LENGTH),
        contentDescription = contentDescription?.takeIf { keepLabel }?.take(HISTORY_LABEL_LENGTH),
        hintText = null,
        labeledByText = null,
        webRole = null,
        targetUrl = null,
        children = if (sensitiveField) emptyList() else children.map(AccessibilityNodeData::forScreenHistory),
    )
}

private const val HISTORY_LABEL_LENGTH = 100
private const val HISTORY_IDENTIFIER_LENGTH = 200
