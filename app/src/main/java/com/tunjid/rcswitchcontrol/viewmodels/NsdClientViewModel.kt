package com.tunjid.rcswitchcontrol.viewmodels

import android.app.Application
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.recyclerview.widget.DiffUtil.DiffResult
import com.tunjid.androidbootstrap.core.components.ServiceConnection
import com.tunjid.androidbootstrap.functions.Supplier
import com.tunjid.androidbootstrap.functions.collections.Lists
import com.tunjid.androidbootstrap.recyclerview.diff.Diff
import com.tunjid.androidbootstrap.recyclerview.diff.Differentiable
import com.tunjid.rcswitchcontrol.R
import com.tunjid.rcswitchcontrol.broadcasts.Broadcaster
import com.tunjid.rcswitchcontrol.data.*
import com.tunjid.rcswitchcontrol.data.RcSwitch.Companion.SWITCH_PREFS
import com.tunjid.rcswitchcontrol.data.persistence.Converter.Companion.deserialize
import com.tunjid.rcswitchcontrol.data.persistence.Converter.Companion.deserializeList
import com.tunjid.rcswitchcontrol.nsd.protocols.BleRcProtocol
import com.tunjid.rcswitchcontrol.nsd.protocols.CommsProtocol
import com.tunjid.rcswitchcontrol.nsd.protocols.ZigBeeProtocol
import com.tunjid.rcswitchcontrol.services.ClientBleService
import com.tunjid.rcswitchcontrol.services.ClientNsdService
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers.mainThread
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.processors.PublishProcessor
import io.reactivex.schedulers.Schedulers.io
import java.util.concurrent.TimeUnit

class NsdClientViewModel(app: Application) : AndroidViewModel(app) {

    val devices: MutableList<Device> = mutableListOf()
    val history: MutableList<Record> = mutableListOf()
    val commands: MutableList<Record> = mutableListOf()

    private val noisyCommands: Set<String> = setOf(
            ClientBleService.ACTION_TRANSMITTER,
            app.getString(R.string.scanblercprotocol_sniff),
            app.getString(R.string.blercprotocol_rename_command),
            app.getString(R.string.blercprotocol_delete_command),
            app.getString(R.string.blercprotocol_refresh_switches_command)
    )

    private val disposable: CompositeDisposable = CompositeDisposable()

    private val stateProcessor: PublishProcessor<State> = PublishProcessor.create()
    private val inPayloadProcessor: PublishProcessor<Payload> = PublishProcessor.create()
    private val outPayloadProcessor: PublishProcessor<Payload> = PublishProcessor.create()
    private val connectionStateProcessor: PublishProcessor<String> = PublishProcessor.create()
    private val nsdConnection: ServiceConnection<ClientNsdService> = ServiceConnection(ClientNsdService::class.java, this::onServiceConnected)

    val isBound: Boolean
        get() = nsdConnection.isBound

    val isConnected: Boolean
        get() = nsdConnection.isBound && !nsdConnection.boundService.isConnected

    val latestHistoryIndex: Int
        get() = history.size - 1

    init {
        nsdConnection.with(app).bind()
        listenForOutputPayloads()
        listenForInputPayloads()
        listenForBroadcasts()
    }

    override fun onCleared() {
        disposable.clear()
        nsdConnection.unbindService()
        super.onCleared()
    }

    fun listen(predicate: (state: State) -> Boolean = { true }): Flowable<State> = stateProcessor.filter(predicate)

    fun dispatchPayload(key: String, payloadReceiver: Payload.() -> Unit) = dispatchPayload(key, { true }, payloadReceiver)

    fun onBackground() = nsdConnection.boundService.onAppBackground()

    fun forgetService() {
        // Don't call unbind, when the hosting activity is finished,
        // onDestroy will be called and the connection unbound
        if (nsdConnection.isBound) nsdConnection.boundService.stopSelf()

        getApplication<Application>().getSharedPreferences(SWITCH_PREFS, MODE_PRIVATE).edit()
                .remove(ClientNsdService.LAST_CONNECTED_SERVICE).apply()
    }

    fun connectionState(): Flowable<String> = connectionStateProcessor.startWith({
        val bound = nsdConnection.isBound
        if (bound) nsdConnection.boundService.onAppForeGround()

        getConnectionText(
                if (bound) nsdConnection.boundService.connectionState
                else ClientNsdService.ACTION_SOCKET_DISCONNECTED)

    }()).observeOn(mainThread())

    private fun onServiceConnected(service: ClientNsdService) {
        connectionStateProcessor.onNext(getConnectionText(service.connectionState))
        dispatchPayload(CommsProtocol::class.java.simpleName, { commands.isEmpty() }) { action = CommsProtocol.PING }
    }

    private fun onIntentReceived(intent: Intent) {
        when (val action = intent.action) {
            ClientNsdService.ACTION_SOCKET_CONNECTED,
            ClientNsdService.ACTION_SOCKET_CONNECTING,
            ClientNsdService.ACTION_SOCKET_DISCONNECTED -> connectionStateProcessor.onNext(getConnectionText(action))
            ClientNsdService.ACTION_START_NSD_DISCOVERY -> connectionStateProcessor.onNext(getConnectionText(ClientNsdService.ACTION_SOCKET_CONNECTING))
            ClientNsdService.ACTION_SERVER_RESPONSE -> {
                val serverResponse = intent.getStringExtra(ClientNsdService.DATA_SERVER_RESPONSE)
                        ?: return
                val payload = serverResponse.deserialize(Payload::class)

                inPayloadProcessor.onNext(payload)
            }
        }
    }

    private fun getConnectionText(newState: String): String {
        var text = ""
        val context = getApplication<Application>()
        val isBound = nsdConnection.isBound

        when (newState) {
            ClientNsdService.ACTION_SOCKET_CONNECTED -> {
                dispatchPayload(CommsProtocol::class.java.simpleName, { commands.isEmpty() }) { action = (CommsProtocol.PING) }
                text = if (!isBound) context.getString(R.string.connected)
                else context.getString(R.string.connected_to, nsdConnection.boundService.serviceName)
            }

            ClientNsdService.ACTION_SOCKET_CONNECTING -> text =
                    if (!isBound) context.getString(R.string.connecting)
                    else context.getString(R.string.connecting_to, nsdConnection.boundService.serviceName)

            ClientNsdService.ACTION_SOCKET_DISCONNECTED -> text = context.getString(R.string.disconnected)
        }
        return text
    }

    private fun dispatchPayload(key: String, predicate: (() -> Boolean), payloadReceiver: Payload.() -> Unit) = Payload(key).run {
        payloadReceiver.invoke(this)
        if (predicate.invoke()) outPayloadProcessor.onNext(this)
    }

    private fun diffDevices(devices: List<Device>): Diff<Device> = Diff.calculate(this.devices, devices) { current, server ->
        mutableSetOf<Device>().apply {
            addAll(server)
            addAll(current)
        }.toList()
    }

    private fun diffHistory(payload: Payload): Diff<Record> = Diff.calculate(
            history,
            listOf(Record(payload.key, (payload.response ?: "Unknown response"), true)),
            { current, responses -> current.apply { addAll(responses) } },
            { response -> Differentiable.fromCharSequence { response.toString() } })

    private fun <T> diff(list: List<T>, diffSupplier: Supplier<Diff<T>>): Single<DiffResult> =
            Single.fromCallable<Diff<T>>(diffSupplier::get)
                    .subscribeOn(io())
                    .observeOn(mainThread())
                    .doOnSuccess { diff -> Lists.replace(list, diff.items) }
                    .map { diff -> diff.result }

    private fun Payload.getMessage(): String? {
        response ?: return null
        return if (noisyCommands.contains(action)) response else null
    }

    private fun Payload.extractCommandInfo(): ZigBeeCommandInfo? {
        if (BleRcProtocol::class.java.name == key) return null
        if (extractDevices() != null) return null
        return data?.deserialize(ZigBeeCommandInfo::class)
    }

    private fun Payload.extractDevices(): List<Device>? {
        val serialized = data ?: return null
        val context = getApplication<Application>()

        return when (key) {
            BleRcProtocol::class.java.name -> when (action) {
                ClientBleService.ACTION_TRANSMITTER,
                context.getString(R.string.blercprotocol_delete_command),
                context.getString(R.string.blercprotocol_rename_command) -> serialized.deserializeList(RcSwitch::class)
                else -> null
            }
            ZigBeeProtocol::class.java.name -> when (action) {
                context.getString(R.string.zigbeeprotocol_saved_devices) -> serialized.deserializeList(ZigBeeDevice::class)
                else -> null
            }
            else -> null
        }
    }

    private fun listenForBroadcasts() {
        disposable.add(Broadcaster.listen(
                ClientNsdService.ACTION_SOCKET_CONNECTED,
                ClientNsdService.ACTION_SOCKET_CONNECTING,
                ClientNsdService.ACTION_SOCKET_DISCONNECTED,
                ClientNsdService.ACTION_SERVER_RESPONSE,
                ClientNsdService.ACTION_START_NSD_DISCOVERY)
                .subscribe(this::onIntentReceived) { it.printStackTrace(); listenForBroadcasts() })
    }

    private fun listenForInputPayloads() {
        disposable.add(inPayloadProcessor.concatMap { payload ->
            Single.concat(mutableListOf<Single<DiffResult>>().let { singleList ->
                val devices = payload.extractDevices()

                singleList.add(diff(history, Supplier { diffHistory(payload) }))
                if (devices != null) singleList.add(diff(this.devices, Supplier { diffDevices(devices) }))

                singleList.map { single -> single.map { State(payload.key, devices != null, payload.getMessage(), payload.extractCommandInfo(), payload.commands, it) } }
            })
        }
                .observeOn(mainThread())
                .doOnNext { Lists.replace(commands, it.let { state -> state.commands.map { command -> Record(state.key, command, true) } }) }
                .subscribe(stateProcessor::onNext) { it.printStackTrace(); listenForInputPayloads() })
    }

    private fun listenForOutputPayloads() {
        disposable.add(outPayloadProcessor
                .sample(200, TimeUnit.MILLISECONDS)
                .filter { nsdConnection.isBound }
                .subscribe(nsdConnection.boundService::sendMessage) { it.printStackTrace(); listenForOutputPayloads() })
    }

    class State internal constructor(
            val key: String,
            val isRc: Boolean,
            val prompt: String?,
            val commandInfo: ZigBeeCommandInfo?,
            val commands: Set<String>,
            val result: DiffResult
    )
}