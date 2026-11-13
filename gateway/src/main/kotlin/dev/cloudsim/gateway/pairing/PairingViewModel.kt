package dev.cloudsim.gateway.pairing

import dev.cloudsim.gateway.L

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cloudsim.gateway.identity.KeystoreSigner
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.net.ServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PairingUi {
    data object Idle : PairingUi
    data object Working : PairingUi
    data class ShowingCode(val code: OpenCode) : PairingUi
    data class Compare(val code: OpenCode, val claimant: Claimant) : PairingUi
    data class Paired(val phoneId: String) : PairingUi
    data class Problem(val message: String) : PairingUi
}

class PairingViewModel(application: Application, private val config: ServiceConfig) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val signer by lazy { KeystoreSigner() }
    private val pairing by lazy { GatewayPairing(config, signer) }
    /** Changing the owner in person: the statement this gateway signs, and the record of it here (BACKUP_RESTORE_6.md). */
    private val owners by lazy {
        val sharing = application.getSharedPreferences("sharing", Context.MODE_PRIVATE)
        dev.cloudsim.gateway.calls.OwnerKeeper(dev.cloudsim.gateway.net.ControlClient(config, signer), signer, dev.cloudsim.gateway.calls.PrefsOwnerState(prefs),
            { runCatching { org.json.JSONObject(sharing.getString("policy", null) ?: "{}").optInt("revision") }.getOrDefault(0) })
    }
    private val _ui = MutableStateFlow<PairingUi>(prefs.getString("phone_id", null)?.let { PairingUi.Paired(it) } ?: PairingUi.Idle)
    val ui: StateFlow<PairingUi> = _ui
    private var polling: Job? = null

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: ServiceException) {
                _ui.value = PairingUi.Problem(e.message ?: e.code)
            }
        }
    }

    /** A new code; the service cancels any earlier unfinished one. [replace]: to change the owner phone in person. */
    fun open(replace: Boolean = false) = run {
        polling?.cancel()
        _ui.value = PairingUi.Working
        val code = withContext(Dispatchers.IO) { pairing.open(replace) }
        _ui.value = PairingUi.ShowingCode(code)
        polling = viewModelScope.launch { poll(code) }
    }

    private suspend fun poll(code: OpenCode) {
        while (currentCoroutineContext().isActive) {
            delay(2_000)
            if (System.currentTimeMillis() >= code.expiresAtMs) {
                _ui.value = PairingUi.Problem(L.t("码已过期，请重新出码"))
                return
            }
            val state = try {
                withContext(Dispatchers.IO) { pairing.poll(code) }
            } catch (e: ServiceException) {
                if (e.code == "NETWORK") continue
                _ui.value = PairingUi.Problem(e.message ?: e.code)
                return
            }
            when (state) {
                PairingState.Waiting -> Unit
                is PairingState.Claimed -> {
                    _ui.value = PairingUi.Compare(code, state.claimant)
                    return
                }
                PairingState.Mismatch -> {
                    withContext(Dispatchers.IO) { runCatching { pairing.cancel(code) } }
                    _ui.value = PairingUi.Problem(L.t("扫码对象不符，这个码已作废。请重新出码"))
                    return
                }
                PairingState.Confirmed -> return
                is PairingState.Ended -> {
                    _ui.value = PairingUi.Problem(if (state.state == "expired") L.t("码已过期，请重新出码") else L.t("这个码已作废"))
                    return
                }
            }
        }
    }

    fun confirm(code: OpenCode, claimant: Claimant) = run {
        _ui.value = PairingUi.Working
        if (code.replace) {
            val st = owners.statement(claimant.phoneKey)
            withContext(Dispatchers.IO) { pairing.confirm(code, claimant, st) }
            owners.take(claimant.phoneId, st)
        } else {
            withContext(Dispatchers.IO) { pairing.confirm(code, claimant) }
            prefs.edit().putString("phone_id", claimant.phoneId).apply()
        }
        _ui.value = PairingUi.Paired(claimant.phoneId)
    }

    /** Back from a replace code that was not used, or from a problem: to the owner if there is one, else to the start. */
    fun keepOwner() {
        polling?.cancel()
        _ui.value = prefs.getString("phone_id", null)?.let { PairingUi.Paired(it) } ?: PairingUi.Idle
    }

    /**
     * "清除主人关联" (GATEWAY_RESET.md): the service drops the owner and family and tells them, then this gateway forgets
     * them and starts its service again without them, ready for a new pairing code.
     */
    fun clearOwner() = run {
        polling?.cancel()
        _ui.value = PairingUi.Working
        val app = getApplication<Application>()
        withContext(Dispatchers.IO) { OwnerReset.run(app, dev.cloudsim.gateway.net.ControlClient(config, signer)) }
        if (dev.cloudsim.gateway.calls.GatewayService.isEnabled(app)) {
            dev.cloudsim.gateway.calls.GatewayService.stop(app)
            dev.cloudsim.gateway.calls.GatewayService.start(app)
        }
        _ui.value = PairingUi.Idle
    }

    /** The codes differ or the device is unknown: withdraw the code. */
    fun reject(code: OpenCode) = run {
        _ui.value = PairingUi.Working
        withContext(Dispatchers.IO) { pairing.cancel(code) }
        _ui.value = PairingUi.Idle
    }

    override fun onCleared() {
        polling?.cancel()
    }
}
