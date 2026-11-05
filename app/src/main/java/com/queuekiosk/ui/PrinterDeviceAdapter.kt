package com.queuekiosk.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.queuekiosk.databinding.ItemPrinterDeviceBinding
import com.queuekiosk.printer.PrinterDevice

/** fleQ DeviceAdapter without the auto-navigation side effect in onBindViewHolder. */
class PrinterDeviceAdapter(
    private val onClick: (PrinterDevice) -> Unit,
) : RecyclerView.Adapter<PrinterDeviceAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemPrinterDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    private var items: List<PrinterDevice> = emptyList()
    private var selected: PrinterDevice? = null

    @SuppressLint("NotifyDataSetChanged") // lists are tiny
    fun submit(items: List<PrinterDevice>, selected: PrinterDevice?) {
        if (items == this.items && selected == this.selected) return
        this.items = items
        this.selected = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemPrinterDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = items[position]
        val isSelected = selected?.let { it.address == device.address && it.driverId == device.driverId } == true
        with(holder.binding) {
            itemPrinterName.text = device.name
            itemPrinterAddress.text = device.address
            itemPrinterCheck.isVisible = isSelected
            root.isActivated = isSelected
            root.setOnClickListener { onClick(device) }
        }
    }

    override fun getItemCount() = items.size
}
