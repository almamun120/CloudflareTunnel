package com.cloudflare.tunnel

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class TunnelAdapter(
    private val onStart: (TunnelInstance) -> Unit,
    private val onStop: (TunnelInstance) -> Unit,
    private val onSelect: (TunnelInstance) -> Unit,
    private val onDelete: (TunnelInstance) -> Unit,
    private val onCopyUrl: (TunnelInstance) -> Unit,
    private val onQr: (TunnelInstance) -> Unit,
    private val onEdit: (TunnelInstance) -> Unit
) : RecyclerView.Adapter<TunnelAdapter.TunnelViewHolder>() {

    private val instances = mutableListOf<TunnelInstance>()

    var selectedId: String? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    fun submitList(newList: List<TunnelInstance>) {
        instances.clear()
        instances.addAll(newList)
        notifyDataSetChanged()
    }

    fun updateItem(instance: TunnelInstance) {
        val index = instances.indexOfFirst { it.id == instance.id }
        if (index >= 0) {
            instances[index] = instance
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TunnelViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tunnel, parent, false)
        return TunnelViewHolder(view)
    }

    override fun onBindViewHolder(holder: TunnelViewHolder, position: Int) = holder.bind(instances[position])

    override fun getItemCount(): Int = instances.size

    inner class TunnelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.cardTunnel)
        private val tvName: TextView = itemView.findViewById(R.id.tvTunnelName)
        private val tvType: TextView = itemView.findViewById(R.id.tvTunnelType)
        private val tvConfig: TextView = itemView.findViewById(R.id.tvTunnelConfig)
        private val tvUrl: TextView = itemView.findViewById(R.id.tvTunnelUrl)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvTunnelStatus)
        private val dot: View = itemView.findViewById(R.id.tunnelStatusIndicator)
        private val btnToggle: MaterialButton = itemView.findViewById(R.id.btnTunnelToggle)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btnCopyTunnelUrl)
        private val btnQr: ImageButton = itemView.findViewById(R.id.btnTunnelQr)
        private val btnEdit: ImageButton = itemView.findViewById(R.id.btnEditTunnel)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteTunnel)

        private val green = Color.parseColor("#34D399")
        private val red = Color.parseColor("#F87171")
        private val orange = Color.parseColor("#F6821F")
        private val amber = Color.parseColor("#FBBF24")

        fun bind(instance: TunnelInstance) {
            val cfg = instance.config
            val named = cfg.tunnelType == TunnelType.NAMED
            tvName.text = instance.name
            tvType.text = if (named) "NAMED" else "QUICK"

            tvConfig.text = buildString {
                append("${cfg.protocol}://localhost:${cfg.localPort}")
                if (named && cfg.hostname.isNotBlank()) append("  →  ${cfg.hostname}")
                if (cfg.hasFirebase() && cfg.enableFirebaseSync) append("  ·  ☁ Firebase")
            }

            val hasUrl = instance.tunnelUrl.isNotBlank()
            val live = instance.isRunning && instance.isConnected && hasUrl
            val selected = instance.id == selectedId
            card.strokeColor = if (selected) orange else Color.parseColor("#26324F")
            card.strokeWidth = if (selected) 3 else 2

            if (instance.isRunning) {
                tvStatus.text = if (live) "LIVE" else "CONNECTING…"
                tvStatus.setTextColor(if (live) green else amber)
                dot.backgroundTintList = ColorStateList.valueOf(if (live) green else amber)

                btnToggle.text = "STOP"
                btnToggle.setIconResource(R.drawable.ic_stop)
                btnToggle.backgroundTintList = ColorStateList.valueOf(red)

                tvUrl.visibility = View.VISIBLE
                tvUrl.text = if (hasUrl) instance.tunnelUrl else "Waiting for public URL…"
            } else {
                tvStatus.text = "STOPPED"
                tvStatus.setTextColor(Color.parseColor("#8C98B5"))
                dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#5A6685"))

                btnToggle.text = "START"
                btnToggle.setIconResource(R.drawable.ic_play)
                btnToggle.backgroundTintList = ColorStateList.valueOf(orange)

                tvUrl.visibility = View.GONE
            }
            btnToggle.iconTint = ColorStateList.valueOf(Color.WHITE)
            btnToggle.setTextColor(Color.WHITE)

            val urlActions = instance.isRunning && hasUrl
            btnCopy.isEnabled = urlActions
            btnQr.isEnabled = urlActions
            btnCopy.alpha = if (urlActions) 1f else 0.3f
            btnQr.alpha = if (urlActions) 1f else 0.3f
            btnEdit.alpha = if (instance.isRunning) 0.3f else 1f

            btnToggle.setOnClickListener { if (instance.isRunning) onStop(instance) else onStart(instance) }
            btnCopy.setOnClickListener { if (urlActions) onCopyUrl(instance) }
            btnQr.setOnClickListener { if (urlActions) onQr(instance) }
            btnEdit.setOnClickListener { onEdit(instance) }
            btnDelete.setOnClickListener { onDelete(instance) }
            card.setOnClickListener { onSelect(instance) }
        }
    }
}
