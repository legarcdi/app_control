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
     * Envía el archivo print_bt.txt a la impresora Bluetooth emparejada cuya MAC sea printerMac (o la primera si es null)
     * Ahora retorna Triple<éxito, nombre de impresora utilizada, mensaje de error>
     */
    fun printFile(printerMac: String? = null): Triple<Boolean, String?, String?> {
        Log.i(TAG, "Entrando a printFile para impresión Bluetooth")
        Log.i(TAG, "MAC recibida: ${printerMac ?: "(null)"}")
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth no disponible o no activado")
            return Triple(false, null, "Bluetooth no disponible o no activado")
        }
        val devices = btAdapter.bondedDevices
        Log.i(TAG, "Impresoras Bluetooth emparejadas: " + devices.joinToString { it.name + " (" + it.address + ")" })
        Log.i(TAG, "UUID usado para conexión: $PRINTER_UUID")
        val device: BluetoothDevice? = devices.firstOrNull {
            printerMac == null || it.address.equals(printerMac, ignoreCase = true)
        }
        if (device == null) {
            val msg = "No se encontró impresora Bluetooth emparejada${if (printerMac != null) ": $printerMac" else ""}"
            Log.e(TAG, msg)
            return Triple(false, null, msg)
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
            return Triple(false, device.name, "Archivo print_bt.txt no encontrado: ${file.absolutePath}")
        }
        if (file.length() == 0L) {
            Log.e(TAG, "Archivo print_bt.txt está vacío: ${file.absolutePath}")
            return Triple(false, device.name, "Archivo print_bt.txt está vacío: ${file.absolutePath}")
        }
        Log.i(TAG, "Tamaño del archivo print_bt.txt: ${file.length()} bytes")
        var socket: BluetoothSocket? = null
        try {
            Log.i(TAG, "Creando socket RFCOMM con UUID: $PRINTER_UUID")
            socket = device.createRfcommSocketToServiceRecord(PRINTER_UUID)
            Log.i(TAG, "Conectando socket Bluetooth...")
            socket.connect()
            Log.i(TAG, "Socket conectado, enviando datos...")
            val out = socket.outputStream
            val data = file.readBytes()
            Log.i(TAG, "Bytes a enviar: ${data.size}")
            // Agrega salto de línea y comando de corte
            val cutCommand = byteArrayOf(0x1D, 0x56, 0x00) // ESC/POS cut
            out.write(data)
            out.write("\n".toByteArray()) // Solo un salto de línea
            out.write(cutCommand) // Comando de corte
            out.flush()
            Log.i(TAG, "Datos enviados, esperando 300ms antes de cerrar el socket...")
            Thread.sleep(300) // Espera 300 ms antes de cerrar el socket
            Log.i(TAG, "Impresión Bluetooth enviada correctamente")
            file.delete() // Borra el archivo después de imprimir
            Log.i(TAG, "Archivo print_bt.txt eliminado tras imprimir")
            return Triple(true, device.name, null)
        } catch (e: IOException) {
            Log.e(TAG, "Error al imprimir por Bluetooth: ${e.message}", e)
            return Triple(false, device.name, "Error al imprimir por Bluetooth: ${e.message}")
        } finally {
            try { socket?.close(); Log.i(TAG, "Socket cerrado") } catch (_: Exception) { Log.w(TAG, "Error cerrando socket") }
        }
    }
} 