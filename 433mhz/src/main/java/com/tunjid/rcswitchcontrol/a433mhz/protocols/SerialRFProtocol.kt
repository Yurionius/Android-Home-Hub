/*
 * MIT License
 *
 * Copyright (c) 2019 Adetunji Dahunsi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tunjid.rcswitchcontrol.a433mhz.protocols


import android.hardware.usb.UsbManager
import android.util.Base64
import android.util.Log
import androidx.core.content.getSystemService
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.rcswitchcontrol.protocols.CommsProtocol
import com.rcswitchcontrol.protocols.models.Payload
import com.tunjid.rcswitchcontrol.a433mhz.R
import com.tunjid.rcswitchcontrol.a433mhz.models.RfSwitch
import com.tunjid.rcswitchcontrol.a433mhz.models.bytes
import com.tunjid.rcswitchcontrol.a433mhz.persistence.RfSwitchDataStore
import com.tunjid.rcswitchcontrol.a433mhz.services.ClientBleService
import com.tunjid.rcswitchcontrol.common.ContextProvider
import com.tunjid.rcswitchcontrol.common.deserialize
import java.io.PrintWriter

/**
 * A protocol for communicating with RF 433 MhZ devices over a serial port
 *
 *
 * Created by tj.dahunsi on 5/07/19.
 */

@Suppress("PrivatePropertyName")
class SerialRFProtocol constructor(
        driver: UsbSerialDriver,
        printWriter: PrintWriter
) : CommsProtocol(printWriter) {

    private val SNIFF: String = appContext.getString(R.string.scanblercprotocol_sniff)
    private val RENAME: String = appContext.getString(R.string.blercprotocol_rename_command)
    private val DELETE: String = appContext.getString(R.string.blercprotocol_delete_command)
    private val REFRESH_SWITCHES: String = appContext.getString(R.string.blercprotocol_refresh_switches_command)

    private val switchStore = RfSwitchDataStore()
    private val switchCreator = RfSwitch.SwitchCreator()

    private val port: UsbSerialPort
    private val serialInputOutputManager: SerialInputOutputManager

    init {
        val manager = ContextProvider.appContext.getSystemService<UsbManager>()
        val connection = manager?.openDevice(driver.device)

        port = driver.ports[0]
        port.open(connection)
        port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        serialInputOutputManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onRunError(e: Exception) = e.printStackTrace()

            override fun onNewData(rawData: ByteArray) = onSerialRead(rawData)
        })

        sharedPool.submit(serialInputOutputManager)
    }

    override fun close() {
        port.close()
        serialInputOutputManager.stop()
    }

    override fun processInput(payload: Payload): Payload {
        val output = serialRfPayload().apply { addCommand(RESET) }

        when (val receivedAction = payload.action) {
            PING, REFRESH_SWITCHES -> output.apply {
                action = ClientBleService.ACTION_TRANSMITTER
                data = switchStore.serializedSavedSwitches
                response = (getString(
                        if (receivedAction == PING) R.string.blercprotocol_ping_response
                        else R.string.blercprotocol_refresh_response
                ))
                addRefreshAndSniff()
            }

            SNIFF -> sharedPool.submit { port.write(byteArrayOf(SNIFF_FLAG), SERIAL_TIMEOUT) }

            RENAME -> output.apply {
                val switches = switchStore.savedSwitches
                val rcSwitch = payload.data?.deserialize(RfSwitch::class)

                val position = switches.map(RfSwitch::bytes).indexOf(rcSwitch?.bytes)
                val hasSwitch = position > -1

                response = if (hasSwitch && rcSwitch != null)
                    getString(R.string.blercprotocol_renamed_response, switches[position].name, rcSwitch.name)
                else
                    getString(R.string.blercprotocol_no_such_switch_response)

                // Switches are equal based on their codes, not their names.
                // Remove the switch with the old name, and add the switch with the new name.
                if (hasSwitch && rcSwitch != null) {
                    switches.removeAt(position)
                    switches.add(position, rcSwitch)
                    switchStore.saveSwitches(switches)
                }

                action = receivedAction
                data = switchStore.serializedSavedSwitches
                addRefreshAndSniff()
            }

            DELETE -> output.apply {
                val switches = switchStore.savedSwitches
                val rcSwitch = payload.data?.deserialize(RfSwitch::class)
                val removed = switches.filterNot { it.bytes.contentEquals(rcSwitch?.bytes) }

                val response = if (rcSwitch == null || switches.size == removed.size)
                    getString(R.string.blercprotocol_no_such_switch_response)
                else
                    getString(R.string.blercprotocol_deleted_response, rcSwitch.name)

                // Save switches before sending them
                switchStore.saveSwitches(removed)

                output.response = response
                action = receivedAction
                data = switchStore.serializedSavedSwitches
                addRefreshAndSniff()
            }

            ClientBleService.ACTION_TRANSMITTER -> output.apply {
                response = getString(R.string.blercprotocol_transmission_response)
                addRefreshAndSniff()

                val transmission = Base64.decode(payload.data, Base64.DEFAULT)
                sharedPool.submit { port.write(transmission, SERIAL_TIMEOUT) }
            }
        }

        return output
    }

    private fun Payload.addRefreshAndSniff() {
        addCommand(REFRESH_SWITCHES)
        addCommand(SNIFF)
    }

    private fun onSerialRead(rawData: ByteArray) = serialRfPayload().let {
        it.addCommand(RESET)

        when (rawData.size) {
            NOTIFICATION -> {
                Log.i("IOT", "RF NOTIFICATION: ${String(rawData)}")
                val flag = rawData.first()
                it.response = when (flag) {
                    TRANSMIT_FLAG -> getString(R.string.blercprotocol_stop_sniff_response)
                    SNIFF_FLAG -> getString(R.string.blercprotocol_start_sniff_response)
                    ERROR_FLAG -> getString(R.string.wiredrcprotocol_invalid_command)
                    else -> String(rawData)
                }
                it.action = ClientBleService.DATA_AVAILABLE_CONTROL
                it.addCommand(REFRESH_SWITCHES)
                if (flag != SNIFF_FLAG) it.addCommand(SNIFF)
            }
            SNIFF_PAYLOAD -> {
                Log.i("IOT", "RF SNIFF: ${String(rawData)}")
                it.data = switchCreator.state
                it.action = ClientBleService.ACTION_SNIFFER

                it.addRefreshAndSniff()

                when (switchCreator.state) {
                    RfSwitch.ON_CODE -> {
                        switchCreator.withOnCode(rawData)
                        it.response = appContext.getString(R.string.blercprotocol_sniff_on_response)
                    }
                    RfSwitch.OFF_CODE -> {
                        val switches = switchStore.savedSwitches
                        val rcSwitch = switchCreator.withOffCode(rawData)
                        val containsSwitch = switches.map(RfSwitch::bytes).contains(rcSwitch.bytes)

                        it.response = getString(
                                if (containsSwitch) R.string.scanblercprotocol_sniff_already_exists_response
                                else R.string.blercprotocol_sniff_off_response
                        )

                        if (!containsSwitch) {
                            switches.add(rcSwitch.copy(name = "Switch " + (switches.size + 1)))
                            switchStore.saveSwitches(switches)

                            it.action = (ClientBleService.ACTION_TRANSMITTER)
                            it.data = (switchStore.serializedSavedSwitches)
                        }
                    }
                }
            }
            else -> Log.i("IOT", "RF Unknown read. Size: ${rawData.size}, as String: ${String(rawData)}")
        }

        sharedPool.submit { pushOut(it) }
        Unit
    }

    companion object {
        const val ARDUINO_VENDOR_ID = 0x2341
        const val ARDUINO_PRODUCT_ID = 0x0010

        const val BAUD_RATE = 115200
        const val SERIAL_TIMEOUT = 99999

        const val NOTIFICATION = 1
        const val SNIFF_PAYLOAD = 10

        const val ERROR_FLAG: Byte = 'E'.toByte()
        const val SNIFF_FLAG: Byte = 'R'.toByte()
        const val TRANSMIT_FLAG: Byte = 'T'.toByte()

        val key = Key(SerialRFProtocol::class.java.name)

        internal fun serialRfPayload(
                data: String? = null,
                action: String? = null,
                response: String? = null
        ) = Payload(
                key = key,
                data = data,
                action = action,
                response = response
        )
    }
}
