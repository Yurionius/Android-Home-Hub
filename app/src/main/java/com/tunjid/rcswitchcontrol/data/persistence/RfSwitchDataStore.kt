package com.tunjid.rcswitchcontrol.data.persistence

import android.content.Context
import com.tunjid.rcswitchcontrol.App
import com.tunjid.rcswitchcontrol.data.RfSwitch
import com.tunjid.rcswitchcontrol.data.RfSwitch.Companion.SWITCH_PREFS
import com.tunjid.rcswitchcontrol.data.persistence.Converter.Companion.deserializeList

class RfSwitchDataStore {

    val savedSwitches: MutableList<RfSwitch>
        get() = serializedSavedSwitches.let {
            if (it.isEmpty()) mutableListOf()
            else it.deserializeList(RfSwitch::class).toMutableList()
        }

    val serializedSavedSwitches: String
        get() = preferences.getString(SWITCHES_KEY, "")!!

    private val preferences = App.instance.getSharedPreferences(SWITCH_PREFS, Context.MODE_PRIVATE)

    fun saveSwitches(switches: List<RfSwitch>) {
        preferences.edit().putString(SWITCHES_KEY, Converter.converter.toJson(switches)).apply()
    }

    companion object {
        private const val SWITCHES_KEY = "Switches"
    }
}