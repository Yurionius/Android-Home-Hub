package com.tunjid.rcswitchcontrol.nsd.protocols

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.text.TextUtils
import android.util.Log
import com.tunjid.androidbootstrap.communications.bluetooth.BLEScanner
import com.tunjid.androidbootstrap.communications.bluetooth.ScanFilterCompat
import com.tunjid.androidbootstrap.communications.bluetooth.ScanResultCompat
import com.tunjid.androidbootstrap.core.components.ServiceConnection
import com.tunjid.rcswitchcontrol.R
import com.tunjid.rcswitchcontrol.broadcasts.Broadcaster
import com.tunjid.rcswitchcontrol.data.Payload
import com.tunjid.rcswitchcontrol.data.RcSwitch
import com.tunjid.rcswitchcontrol.data.persistence.Converter.Companion.serialize
import com.tunjid.rcswitchcontrol.services.ClientBleService
import io.reactivex.disposables.CompositeDisposable
import java.io.PrintWriter
import java.util.*

/**
 * A protocol for scanning for BLE devices for remote devices
 *
 *
 * Created by tj.dahunsi on 4/12/17.
 */

@Suppress("PrivatePropertyName")
internal class ScanBleRcProtocol(printWriter: PrintWriter) : CommsProtocol(printWriter), BLEScanner.BleScanCallback {

    private val SCAN: String = appContext.getString(R.string.button_scan)
    private val CONNECT: String = appContext.getString(R.string.connect)
    private val DISCONNECT: String = appContext.getString(R.string.menu_disconnect)

    private var currentDevice: BluetoothDevice? = null
    private val scanner: BLEScanner

    private val scanThread: HandlerThread = HandlerThread("Hi").apply { start() }
    private val scanHandler: Handler = Handler(scanThread.looper)

    private val deviceMap = HashMap<String, BluetoothDevice>()
    private val bleConnection: ServiceConnection<ClientBleService> = ServiceConnection(ClientBleService::class.java)

    private val disposable = CompositeDisposable()

    init {

        val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) bluetoothAdapter.enable()

        val serviceUUID = UUID.fromString(ClientBleService.DATA_TRANSCEIVER_SERVICE)
        scanner = BLEScanner.getBuilder(bluetoothAdapter)
                .addFilter(ScanFilterCompat.getBuilder()
                        .setServiceUuid(ParcelUuid(serviceUUID))
                        .build())
                .withCallBack(this)
                .build()

        val preferences = appContext.getSharedPreferences(RcSwitch.SWITCH_PREFS, MODE_PRIVATE)
        val lastConnectedDevice = preferences.getString(ClientBleService.LAST_PAIRED_DEVICE, "")

        // Retreive device from shared preferences if it exists
        if (!TextUtils.isEmpty(lastConnectedDevice) && bluetoothAdapter.isEnabled) {
            currentDevice = bluetoothAdapter.getRemoteDevice(lastConnectedDevice)
            val extras = Bundle()
            extras.putParcelable(ClientBleService.BLUETOOTH_DEVICE, currentDevice)

            bleConnection.with(appContext).setExtras(extras).bind()
        }

        disposable.add(Broadcaster.listen(
                ClientBleService.ACTION_GATT_CONNECTED,
                ClientBleService.ACTION_GATT_CONNECTING,
                ClientBleService.ACTION_GATT_DISCONNECTED,
                ClientBleService.ACTION_GATT_SERVICES_DISCOVERED,
                ClientBleService.ACTION_CONTROL,
                ClientBleService.ACTION_SNIFFER,
                ClientBleService.DATA_AVAILABLE_UNKNOWN)
                .subscribe(this::onBroadcastReceived, Throwable::printStackTrace))
    }

    private fun onScanComplete() {
        scanner.stopScan()

        val resources = appContext.resources
        val output = Payload(this@ScanBleRcProtocol.javaClass.name)
        output.addCommand(RESET)
        output.addCommand(SCAN)

        for (device in deviceMap.values) output.addCommand(device.name)

        output.response = resources.getString(R.string.scanblercprotocol_scan_response, deviceMap.size)
        printWriter.println(output.serialize())
    }

    private fun onBroadcastReceived(intent: Intent) {
        val action = intent.action ?: return
        val output = Payload(javaClass.name)
        output.addCommand(RESET)

        when (action) {
            ClientBleService.ACTION_GATT_CONNECTED -> output.apply { response = appContext.getString(R.string.connected);addCommand(DISCONNECT) }
            ClientBleService.ACTION_GATT_CONNECTING -> output.response = appContext.getString(R.string.connecting)
            ClientBleService.ACTION_GATT_DISCONNECTED -> output.apply { response = appContext.getString(R.string.disconnected);addCommand(CONNECT) }
        }

        pushData(output)
    }

    override fun processInput(payload: Payload): Payload {
        val resources = appContext.resources
        val output = Payload(javaClass.name)
        output.addCommand(RESET)

        val action = payload.action

        when {
            action == PING || action == RESET -> output.apply {
                response = resources.getString(R.string.scanblercprotocol_ping_reponse)
                addCommand(SCAN)
            }
            action == SCAN -> {
                deviceMap.clear()
                scanner.startScan()
                scanHandler.postDelayed({ this.onScanComplete() }, SCAN_DURATION.toLong())

                output.response = resources.getString(R.string.scanblercprotocol_start_scan_reponse)
            }
            action == CONNECT && bleConnection.isBound -> bleConnection.boundService.connect(currentDevice)
            action == DISCONNECT && bleConnection.isBound -> bleConnection.boundService.disconnect()
            deviceMap.containsKey(action) -> {
                val extras = Bundle()
                extras.putParcelable(ClientBleService.BLUETOOTH_DEVICE, deviceMap[action])

                bleConnection.with(appContext).setExtras(extras).start()
                bleConnection.with(appContext).setExtras(extras).bind()

                Log.i(TAG, "Started ClientBleService, device: $action")
            }
        }
        return output
    }

    override fun onDeviceFound(result: ScanResultCompat) {
        val record = result.scanRecord
        val device = result.device
        val deviceName = record?.deviceName

        if (deviceName != null) deviceMap[deviceName] = device
    }

    override fun close() {
        disposable.clear()
        scanThread.quitSafely()

        if (bleConnection.isBound) bleConnection.unbindService()
    }

    private fun pushData(payload: Payload) {
        scanHandler.post { printWriter.println(payload.serialize()) }
    }

    companion object {

        private val TAG = ScanBleRcProtocol::class.java.simpleName
        private const val SCAN_DURATION = 5000
    }
}
