package com.t91s.webview

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.*

class BluetoothPrinterHelper(private val context: Context) {
    companion object {
        private const val TAG = "BluetoothPrinterHelper"
        // UUID estándar para impresoras serie Bluetooth SPP
        private val PRINTER_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    /**
     * Envía el archivo print_bt.txt a la impresora Bluetooth emparejada cuyo nombre contenga printerName (o la primera si es null)
     * Ahora retorna Pair<éxito, nombre de impresora utilizada>
     */
    fun printFile(printerName: String? = null): Pair<Boolean, String?> {
        Log.i(TAG, "Entrando a printFile para impresión Bluetooth")
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth no disponible o no activado")
            return Pair(false, null)
        }
        val devices = btAdapter.bondedDevices
        Log.i(TAG, "Impresoras Bluetooth emparejadas: " + devices.joinToString { it.name + " (" + it.address + ")" })
        Log.i(TAG, "UUID usado para conexión: $PRINTER_UUID")
        val device: BluetoothDevice? = devices.firstOrNull {
            printerName == null || it.name.contains(printerName, ignoreCase = true)
        }
        if (device == null) {
            Log.e(TAG, "No se encontró impresora Bluetooth emparejada${if (printerName != null) ": $printerName" else ""}")
            return Pair(false, null)
        } else {
            Log.i(TAG, "Impresora seleccionada: ${device.name} (${device.address})")
        }
        Log.i(TAG, "Verificando existencia de archivo antes de imprimir")
        val dir = File(context.filesDir, "nodejs-project")
        Log.i(TAG, "Archivos en nodejs-project: " + (dir.list()?.joinToString() ?: "(directorio vacío o no existe)"))
        val file = File(dir, "print_bt.txt")
        Log.i(TAG, "Buscando archivo: ${file.absolutePath}")
        if (!file.exists()) {
            Log.e(TAG, "Archivo print_bt.txt no encontrado: ${file.absolutePath}")
            android.widget.Toast.makeText(context, "No se encontró el archivo de impresión", android.widget.Toast.LENGTH_SHORT).show()
            return Pair(false, device.name)
        }
        if (file.length() == 0L) {
            Log.e(TAG, "Archivo print_bt.txt está vacío: ${file.absolutePath}")
            android.widget.Toast.makeText(context, "El archivo de impresión está vacío", android.widget.Toast.LENGTH_SHORT).show()
            return Pair(false, device.name)
        }
        var socket: BluetoothSocket? = null
        try {
            Log.i(TAG, "Creando socket RFCOMM con UUID: $PRINTER_UUID")
            socket = device.createRfcommSocketToServiceRecord(PRINTER_UUID)
            Log.i(TAG, "Conectando socket Bluetooth...")
            socket.connect()
            Log.i(TAG, "Socket conectado, enviando datos...")
            val out = socket.outputStream
            val data = file.readBytes()
            // Agrega salto de línea y comando de corte
            val cutCommand = byteArrayOf(0x1D, 0x56, 0x00) // ESC/POS cut
            out.write(data)
            out.write("\n".toByteArray()) // Solo un salto de línea
            out.write(cutCommand) // Comando de corte
            out.flush()
            Thread.sleep(300) // Espera 300 ms antes de cerrar el socket
            Log.i(TAG, "Impresión Bluetooth enviada correctamente")
            file.delete() // Borra el archivo después de imprimir
            return Pair(true, device.name)
        } catch (e: IOException) {
            Log.e(TAG, "Error al imprimir por Bluetooth: ${e.message}", e)
            return Pair(false, device.name)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
} 