package dev.cloudsim.gateway.numbers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONObject

/**
 * The SIMs in use, from SubscriptionManager. The own number needs "read phone numbers" (READ_PHONE_NUMBERS, the
 * user's 2026-11-05 decision): first the SIM's, then the carrier's, then IMS; null when none is known.
 */
class SystemSims(private val context: Context, private val log: (String) -> Unit = {}) : SimSource {
    /** The last summary logged: the SIMs are read many times a second around calls, so only a change is logged. */
    @Volatile private var lastSummary: String? = null

    private fun summary(text: String) {
        if (text == lastSummary) return
        lastSummary = text
        log(text)
    }

    @SuppressLint("MissingPermission")
    override fun active(): List<Sim> {
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList<Sim>().also { log("no SubscriptionManager") }
        val list = runCatching { sm.activeSubscriptionInfoList }.onFailure { log("active subscriptions: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
            ?: emptyList()
        // Some builds hand an app an empty list; then ask slot by slot.
        val slots = runCatching { context.getSystemService(TelephonyManager::class.java).activeModemCount }.getOrDefault(2)
        val bySlot = if (list.isNotEmpty()) list else (0 until slots).mapNotNull { slot ->
            runCatching { sm.getActiveSubscriptionInfoForSimSlotIndex(slot) }.onFailure { log("slot $slot: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
        }
        val count = runCatching { sm.activeSubscriptionInfoCount }.getOrDefault(-1)
        if (bySlot.isEmpty()) {
            // MIUI can hide the subscription list from an app; the slot → subscription map is still there.
            val tm = context.getSystemService(TelephonyManager::class.java)
            val numbers = context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
            val found = (0 until slots).mapNotNull { slot ->
                @Suppress("DEPRECATION")
                val sub = runCatching { sm.getSubscriptionIds(slot)?.firstOrNull() }.onFailure { log("slot $slot ids: ${it.javaClass.simpleName}") }.getOrNull()
                    ?.takeIf { it >= 0 } ?: return@mapNotNull null
                val t = tm.createForSubscriptionId(sub)
                if (t.simState != TelephonyManager.SIM_STATE_READY) return@mapNotNull null
                val number = if (!numbers) null else runCatching {
                    if (Build.VERSION.SDK_INT >= 33) listOf(SubscriptionManager.PHONE_NUMBER_SOURCE_UICC, SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER, SubscriptionManager.PHONE_NUMBER_SOURCE_IMS)
                        .firstNotNullOfOrNull { src -> sm.getPhoneNumber(sub, src).takeIf { it.isNotBlank() } }
                    else @Suppress("DEPRECATION") t.line1Number
                }.getOrNull()
                Sim(sub, slot, runCatching { t.simOperatorName }.getOrDefault("") ?: "", number)
            }
            summary("sims: list ${list.size}, by slot ${bySlot.size} of $slots, count $count, by slot ids ${found.size}")
            return found
        }
        summary("sims: list ${list.size}, by slot ${bySlot.size} of $slots, count $count")
        val numbers = context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        return bySlot.map { info ->
            val number = if (!numbers) null else runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    listOf(SubscriptionManager.PHONE_NUMBER_SOURCE_UICC, SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER, SubscriptionManager.PHONE_NUMBER_SOURCE_IMS)
                        .firstNotNullOfOrNull { src -> sm.getPhoneNumber(info.subscriptionId, src).takeIf { it.isNotBlank() } }
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(info.subscriptionId).line1Number
                }
            }.getOrNull()
            Sim(info.subscriptionId, info.simSlotIndex, info.carrierName?.toString() ?: "", number)
        }
    }
}

class PrefsBindings(context: Context) : Bindings {
    private val prefs = context.getSharedPreferences("numbers", Context.MODE_PRIVATE)
    override fun load() = runCatching { JSONObject(prefs.getString("bindings", "{}")!!) }.getOrDefault(JSONObject())
    override fun save(all: JSONObject) { prefs.edit().putString("bindings", all.toString()).apply() }
}
