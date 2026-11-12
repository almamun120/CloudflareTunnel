package com.cloudflare.tunnel

import android.graphics.Color
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.parcelize.Parcelize
import java.text.SimpleDateFormat
import java.util.*

enum class LogType { INFO, ERROR, WARN, SUCCESS, DEBUG }

@Parcelize
data class LogEntry(
    val message: String,
    val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
    val type: LogType = LogType.INFO
) : Parcelable

class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    companion object {
        private const val MAX_LOGS = 1000   // আগে সীমাহীন বাড়ত → মেমোরি লিক
        fun classify(message: String): LogType = when {
            message.contains("[ERROR]", true) || message.contains(" ERR ", false) -> LogType.ERROR
            message.contains("[WARN]", true) || message.contains(" WRN ", false) -> LogType.WARN
            message.contains("[SUCCESS]", true) -> LogType.SUCCESS
            message.contains("[DEBUG]", true) || message.contains(" DBG ", false) -> LogType.DEBUG
            else -> LogType.INFO
        }
    }

    private val logs = ArrayList<LogEntry>()

    fun addLog(message: String, type: LogType = classify(message)) {
        logs.add(LogEntry(message, type = type))
        notifyItemInserted(logs.size - 1)
        if (logs.size > MAX_LOGS) {
            val remove = logs.size - MAX_LOGS
            repeat(remove) { logs.removeAt(0) }
            notifyItemRangeRemoved(0, remove)
        }
    }

    fun setLogs(entries: List<LogEntry>) {
        logs.clear()
        logs.addAll(entries.takeLast(MAX_LOGS))
        notifyDataSetChanged()
    }

    fun clearLogs() {
        logs.clear()
        notifyDataSetChanged()
    }

    fun getAllLogs(): String = logs.joinToString("\n") { "[${it.timestamp}] ${it.message}" }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) = holder.bind(logs[position])

    override fun getItemCount(): Int = logs.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)

        fun bind(entry: LogEntry) {
            tvTimestamp.text = entry.timestamp
            tvMessage.text = entry.message
            tvMessage.setTextColor(
                when (entry.type) {
                    LogType.ERROR -> Color.parseColor("#F87171")
                    LogType.WARN -> Color.parseColor("#FBBF24")
                    LogType.SUCCESS -> Color.parseColor("#34D399")
                    LogType.DEBUG -> Color.parseColor("#7DD3FC")
                    LogType.INFO -> Color.parseColor("#D5DBEB")
                }
            )
        }
    }
}
