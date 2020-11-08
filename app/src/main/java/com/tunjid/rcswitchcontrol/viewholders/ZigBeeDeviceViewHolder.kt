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

package com.tunjid.rcswitchcontrol.viewholders

import android.view.ViewGroup
import com.flask.colorpicker.ColorPickerView
import com.flask.colorpicker.builder.ColorPickerDialogBuilder
import com.rcswitchcontrol.zigbee.models.ZigBeeCommand
import com.rcswitchcontrol.zigbee.models.ZigBeeDevice
import com.rcswitchcontrol.zigbee.models.ZigBeeInput
import com.tunjid.androidx.recyclerview.viewbinding.BindingViewHolder
import com.tunjid.androidx.recyclerview.viewbinding.viewHolderFrom
import com.tunjid.rcswitchcontrol.R
import com.tunjid.rcswitchcontrol.databinding.ViewholderZigbeeDeviceBinding
import com.tunjid.rcswitchcontrol.dialogfragments.Throttle
import com.tunjid.rcswitchcontrol.dialogfragments.throttleColorChanges

interface ZigBeeDeviceListener : DeviceLongClickListener {
    fun send(command: ZigBeeCommand)
}

var BindingViewHolder<ViewholderZigbeeDeviceBinding>.device by BindingViewHolder.Prop<ZigBeeDevice>()
var BindingViewHolder<ViewholderZigbeeDeviceBinding>.listener by BindingViewHolder.Prop<ZigBeeDeviceListener>()

fun BindingViewHolder<ViewholderZigbeeDeviceBinding>.bind(device: ZigBeeDevice) {
    this.device = device
    binding.switchName.text = device.name
    device.highlightViewHolder(this, listener::isSelected)
}

fun ViewGroup.zigbeeDeviceViewHolder(
        listener: ZigBeeDeviceListener
) = viewHolderFrom(ViewholderZigbeeDeviceBinding::inflate).apply binding@{
    this.listener = listener
    val throttle = Throttle { listener.send(device.command(ZigBeeInput.Level(level = it / 100F))) }

    binding.apply {
        itemView.setOnClickListener { listener.onClicked(device) }
        itemView.setOnLongClickListener {
            device.highlightViewHolder(this@binding, listener::onLongClicked)
            true
        }

        zigbeeIcon.setOnClickListener { listener.send(device.command(ZigBeeInput.Node)) }
        offSwitch.setOnClickListener { listener.send(device.command(ZigBeeInput.Toggle(isOn = false))) }
        onSwitch.setOnClickListener { listener.send(device.command(ZigBeeInput.Toggle(isOn = true))) }
        leveler.addOnChangeListener { _, value, fromUser -> if (fromUser) throttle.run(value.toInt()) }
        colorPicker.setOnClickListener {
            ColorPickerDialogBuilder
                    .with(context)
                    .setTitle(R.string.color_picker_choose)
                    .wheelType(ColorPickerView.WHEEL_TYPE.CIRCLE)
                    .showLightnessSlider(true)
                    .showAlphaSlider(false)
                    .density(12)
                    .throttleColorChanges { rgb ->
                        listener.send(device.command(ZigBeeInput.Color(rgb)))
                    }
                    .build()
                    .show()
        }
    }
}
