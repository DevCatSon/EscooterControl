package com.devcat.escootercontrol.ble

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

enum class LogDirection { TX, RX, INFO }

data class FrameLogEntry(val timeMs: Long, val direction: LogDirection, val text: String)

sealed interface FrameLogEvent {
    data class Added(val entry: FrameLogEntry) : FrameLogEvent
    data object Cleared : FrameLogEvent
}

/**
 * Small in-memory ring buffer of frames sent/received by the app.
 *
 * The hot BLE/control path intentionally does not publish a full copied List on every entry.
 * Consumers get tiny add/clear events and can take a snapshot when they actually open Debug.
 */
class FrameLog(val capacity: Int = 400) {

    private val lock = Any()
    private val buffer = ArrayDeque<FrameLogEntry>(capacity)
    private val _events = MutableSharedFlow<FrameLogEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<FrameLogEvent> = _events.asSharedFlow()

    fun add(direction: LogDirection, text: String) {
        val entry = FrameLogEntry(System.currentTimeMillis(), direction, text)
        synchronized(lock) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(entry)
        }
        _events.tryEmit(FrameLogEvent.Added(entry))
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _events.tryEmit(FrameLogEvent.Cleared)
    }

    fun snapshot(): List<FrameLogEntry> = synchronized(lock) { buffer.toList() }

    fun asText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return snapshot().joinToString("\n") { e ->
            "${fmt.format(Date(e.timeMs))} ${e.direction.name.padEnd(4)} ${e.text}"
        }
    }
}
