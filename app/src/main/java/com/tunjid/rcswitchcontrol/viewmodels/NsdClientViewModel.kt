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
import com.tunjid.rcswitchcontrol.model.Payload
import com.tunjid.rcswitchcontrol.model.RcSwitch
import com.tunjid.rcswitchcontrol.model.RcSwitch.Companion.SWITCH_PREFS
import com.tunjid.rcswitchcontrol.nsd.protocols.BleRcProtocol
import com.tunjid.rcswitchcontrol.nsd.protocols.CommsProtocol
import com.tunjid.rcswitchcontrol.services.ClientBleService
import com.tunjid.rcswitchcontrol.services.ClientNsdService
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers.mainThread
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.processors.PublishProcessor
import io.reactivex.schedulers.Schedulers.io

class NsdClientViewModel(app: Application) : AndroidViewModel(app) {

    val history: MutableList<String> = ArrayList()
    val commands: MutableList<String> = ArrayList()
    val switches: MutableList<RcSwitch> = ArrayList()

    private val noisyCommands: Set<String> = setOf(
            ClientBleService.ACTION_TRANSMITTER,
            app.getString(R.string.scanblercprotocol_sniff),
            app.getString(R.string.blercprotocol_rename_command),
            app.getString(R.string.blercprotocol_delete_command),
            app.getString(R.string.blercprotocol_refresh_switches_command)
    )

    private val disposable: CompositeDisposable = CompositeDisposable()
    private val stateProcessor: PublishProcessor<State> = PublishProcessor.create()
    private val connectionStateProcessor: PublishProcessor<String> = PublishProcessor.create()
    private val nsdConnection: ServiceConnection<ClientNsdService> = ServiceConnection(ClientNsdService::class.java, ServiceConnection.BindCallback<ClientNsdService>(this::onServiceConnected))

    val isBound: Boolean
        get() = nsdConnection.isBound

    val isConnected: Boolean
        get() = nsdConnection.isBound && !nsdConnection.boundService.isConnected

    val latestHistoryIndex: Int
        get() = history.size - 1

    init {
        nsdConnection.with(app).bind()
        disposable.add(Broadcaster.listen(
                ClientNsdService.ACTION_SOCKET_CONNECTED,
                ClientNsdService.ACTION_SOCKET_CONNECTING,
                ClientNsdService.ACTION_SOCKET_DISCONNECTED,
                ClientNsdService.ACTION_SERVER_RESPONSE,
                ClientNsdService.ACTION_START_NSD_DISCOVERY)
                .subscribe(this::onIntentReceived, Throwable::printStackTrace))
    }

    override fun onCleared() {
        disposable.clear()
        nsdConnection.unbindService()
        super.onCleared()
    }

    fun listen(): Flowable<State> = stateProcessor.observeOn(mainThread())

    fun sendMessage(message: Payload) = sendMessage(Supplier { true }, message)

    fun onBackground() = nsdConnection.boundService.onAppBackground()

    fun forgetService() {
        // Don't call unbind, when the hosting activity is finished,
        // onDestroy will be called and the connection unbound
        if (nsdConnection.isBound) nsdConnection.boundService.stopSelf()

        getApplication<Application>().getSharedPreferences(SWITCH_PREFS, MODE_PRIVATE).edit()
                .remove(ClientNsdService.LAST_CONNECTED_SERVICE).apply()
    }

    fun connectionState(): Flowable<String> {
        return connectionStateProcessor.startWith { publisher ->
            val bound = nsdConnection.isBound
            if (bound) nsdConnection.boundService.onAppForeGround()

            publisher.onNext(getConnectionText(
                    if (bound) nsdConnection.boundService.connectionState
                    else ClientNsdService.ACTION_SOCKET_DISCONNECTED)
            )
            publisher.onComplete()
        }.observeOn(mainThread())
    }

    private fun onServiceConnected(service: ClientNsdService) {
        connectionStateProcessor.onNext(getConnectionText(service.connectionState))
        sendMessage(Supplier { commands.isEmpty() }, Payload.builder().setAction(CommsProtocol.PING).build())
    }

    private fun onIntentReceived(intent: Intent) {
        when (val action = intent.action) {
            ClientNsdService.ACTION_SOCKET_CONNECTED, ClientNsdService.ACTION_SOCKET_CONNECTING, ClientNsdService.ACTION_SOCKET_DISCONNECTED -> connectionStateProcessor.onNext(getConnectionText(action))
            ClientNsdService.ACTION_START_NSD_DISCOVERY -> connectionStateProcessor.onNext(getConnectionText(ClientNsdService.ACTION_SOCKET_CONNECTING))
            ClientNsdService.ACTION_SERVER_RESPONSE -> {
                val serverResponse = intent.getStringExtra(ClientNsdService.DATA_SERVER_RESPONSE)
                val payload = Payload.deserialize(serverResponse)
                val isSwitchPayload = isSwitchPayload(payload)

                Lists.replace(commands, payload.commands)

                val diffSingle: Single<DiffResult> = when {
                    isSwitchPayload -> diff(switches, Supplier { diffSwitches(payload) })
                    else -> diff(history, Supplier { diffHistory(payload) })
                }

                disposable.add(diffSingle.map { State(isSwitchPayload, getMessage(payload), it) }
                        .subscribe(stateProcessor::onNext, Throwable::printStackTrace))
            }
        }
    }

    private fun getConnectionText(newState: String): String {
        var text = ""
        val context = getApplication<Application>()
        val isBound = nsdConnection.isBound

        when (newState) {
            ClientNsdService.ACTION_SOCKET_CONNECTED -> {
                sendMessage(Supplier { commands.isEmpty() }, Payload.builder().setAction(CommsProtocol.PING).build())
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

    private fun sendMessage(predicate: Supplier<Boolean>, message: Payload) {
        if (nsdConnection.isBound && predicate.get()) nsdConnection.boundService.sendMessage(message)
    }

    private fun getMessage(payload: Payload): String? {
        val response = payload.response ?: return null

        var action: String? = payload.action
        action = action ?: ""

        return if (noisyCommands.contains(action)) response else null
    }

    private fun hasSwitches(payload: Payload): Boolean {
        val action = payload.action ?: return false

        val context = getApplication<Application>()
        return (action == ClientBleService.ACTION_TRANSMITTER
                || action == context.getString(R.string.blercprotocol_delete_command)
                || action == context.getString(R.string.blercprotocol_rename_command))
    }

    private fun isSwitchPayload(payload: Payload): Boolean {
        val key = payload.key
        val payloadAction = payload.action

        val isBleRc = key == BleRcProtocol::class.java.name

        if (isBleRc) return true
        if (payloadAction == null) return false

        val context = getApplication<Application>()
        return (payloadAction == ClientBleService.ACTION_TRANSMITTER
                || payloadAction == context.getString(R.string.blercprotocol_delete_command)
                || payloadAction == context.getString(R.string.blercprotocol_rename_command))
    }

    private fun diffSwitches(payload: Payload): Diff<RcSwitch> = Diff.calculate(
            switches,
            payload.data?.let {
                if (hasSwitches(payload)) RcSwitch.deserializeSavedSwitches(it)
                else emptyList<RcSwitch>()
            } ?: emptyList<RcSwitch>(),
            { current, server ->
                if (server.isNotEmpty()) Lists.replace(current, server)
                current
            },
            { rcSwitch -> Differentiable.fromCharSequence(Supplier { rcSwitch.serialize() }) })

    private fun diffHistory(payload: Payload): Diff<String> {
        return Diff.calculate(history,
                listOf(payload.response),
                { current, server ->
                    current.addAll(server)
                    current
                },
                { response -> Differentiable.fromCharSequence(Supplier { response.toString() }) })
    }

    private fun <T> diff(list: List<T>, diffSupplier: Supplier<Diff<T>>): Single<DiffResult> {
        return Single.fromCallable<Diff<T>>(diffSupplier::get)
                .subscribeOn(io())
                .observeOn(mainThread())
                .doOnSuccess { diff -> Lists.replace(list, diff.items) }
                .map { diff -> diff.result }
    }

    class State internal constructor(val isRc: Boolean, val prompt: String?, val result: DiffResult)
}