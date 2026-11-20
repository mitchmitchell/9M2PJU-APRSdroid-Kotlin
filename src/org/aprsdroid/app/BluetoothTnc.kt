package org.aprsdroid.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import net.ab0oo.aprs.parser.APRSPacket
import java.io.IOException
import java.util.UUID

/**
 * Kotlin port of the Scala `BluetoothTnc`.
 *
 * Connects to a KISS-compatible TNC over Bluetooth SPP (Serial Port
 * Profile). Reuses the [KissProto] for framing.
 */
class BluetoothTnc(private val service: AprsService, prefs: PrefsWrapper) :
    AprsBackendAbstract(prefs) {

    private val TAG = "APRSdroid.BluetoothTnc"
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var conn: Thread? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            (service.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }
    }

    override fun start(): Boolean {
        val addr = prefs.getString("bluetooth.target", "")
        if (addr.isEmpty()) {
            service.postAbort(service.getString(R.string.bt_error_no_tnc))
            return false
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            service.postAbort(service.getString(R.string.bt_error_disabled))
            return false
        }
        val device = adapter.getRemoteDevice(addr) ?: run {
            service.postAbort(service.getString(R.string.bt_error_no_tnc))
            return false
        }
        conn = Thread { connectAndRun(device) }.also { it.start() }
        return false
    }

    @Volatile
    private var tnc: TncProto? = null

    private fun connectAndRun(device: BluetoothDevice) {
        val label = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
        try {
            service.postAddPost(
                org.aprsdroid.app.data.PostEntity.TYPE_INFO, R.string.post_info,
                service.getString(R.string.bt_connecting_to_spp, label),
            )
            val s = device.createRfcommSocketToServiceRecord(uuid)
            try {
                s.connect()
            } catch (e: IOException) {
                Log.d(TAG, "Bluetooth connect failed: $e")
                try { s.close() } catch (_: IOException) {}
                service.postLinkOff(R.string.p_link_bt)
                service.postAbort(service.getString(R.string.bt_error_connect, label))
                return
            }
            socket = s
            // The protocol the user chose (KISS, TNC2 or Kenwood), created once:
            // creating it sends the TNC its init string, so it must not be redone per packet.
            val proto = AprsBackend.instanciateProto(service, s.inputStream, s.outputStream)
            tnc = proto
            service.postLinkOn(R.string.p_link_bt)
            service.postPosterStarted()
            while (Thread.currentThread() == conn && s.isConnected) {
                val line = proto.readPacket()
                if (line.isEmpty()) break
                Log.d(TAG, "recv: $line")
                service.postSubmit(line)
            }
        } catch (e: IOException) {
            // Closing the socket in stop() also lands here; only report a real drop.
            if (conn != null) {
                Log.d(TAG, "Bluetooth IOException: $e")
                service.postLinkOff(R.string.p_link_bt)
                service.postAbort(e.toString())
            }
        } catch (e: SecurityException) {
            service.postAbort("Bluetooth permission was denied. Allow Nearby devices for this app.")
        } finally {
            tnc = null
        }
    }

    override fun update(packet: APRSPacket): String {
        val proto = tnc ?: return "BT disconnected"
        return try {
            synchronized(proto) { proto.writePacket(packet) }
            "BT OK"
        } catch (e: IOException) {
            "BT error: ${e.message}"
        }
    }

    override fun stop() {
        conn = null
        try {
            tnc?.stop()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: IOException) {}
    }
}
