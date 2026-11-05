package com.queuekiosk.printer

import android.content.Context
import android.util.Log
import com.queuekiosk.printer.escpos.BluetoothEscPosDriver

/**
 * All printer drivers available in this build. ESC/POS is always present. The Epson driver
 * is only present when the optional :printer-epson module (proprietary SDK) is built in.
 */
class PrinterRegistry(context: Context) {

    val drivers: List<PrinterDriver> = buildList {
        add(BluetoothEscPosDriver(context))
        loadOptional(context.applicationContext, EPSON_DRIVER_CLASS)?.let(::add)
    }

    fun get(id: String): PrinterDriver? = drivers.firstOrNull { it.id == id }

    companion object {
        private const val TAG = "PrinterRegistry"
        const val EPSON_DRIVER_CLASS = "com.queuekiosk.printer.epson.EpsonEpos2Driver"

        private fun loadOptional(context: Context, className: String): PrinterDriver? = try {
            Class.forName(className)
                .getConstructor(Context::class.java)
                .newInstance(context) as PrinterDriver
        } catch (e: ClassNotFoundException) {
            null // module not built in, which is expected
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Could not create $className", e)
            null
        } catch (e: LinkageError) {
            // e.g. UnsatisfiedLinkError: libepos2.so missing for this ABI
            Log.w(TAG, "Native library missing for $className", e)
            null
        }
    }
}
