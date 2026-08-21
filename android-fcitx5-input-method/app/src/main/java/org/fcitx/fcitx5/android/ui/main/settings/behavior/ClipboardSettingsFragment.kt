/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.sync.ClipboardSyncPreferences
import org.fcitx.fcitx5.android.data.clipboard.sync.ClipboardSyncRuntime
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment

class ClipboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {
    private lateinit var syncPreferences: ClipboardSyncPreferences
    private lateinit var accountPreference: Preference

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = requireContext()
        syncPreferences = ClipboardSyncPreferences(ctx)
        val category = PreferenceCategory(ctx).apply {
            title = getString(R.string.clipboard_sync)
            isIconSpaceReserved = false
        }
        screen.addPreference(category)

        category.addPreference(SwitchPreferenceCompat(ctx).apply {
            key = "ez_clipboard_sync_enabled"
            title = getString(R.string.clipboard_sync)
            summary = getString(R.string.clipboard_sync_summary)
            isIconSpaceReserved = false
            isChecked = syncPreferences.enabled
            setOnPreferenceChangeListener { _, value ->
                if (!isAdded) return@setOnPreferenceChangeListener false
                val enabled = value as Boolean
                syncPreferences.enabled = enabled
                if (enabled) {
                    AppPrefs.getInstance().clipboard.clipboardListening.setValue(true)
                    ClipboardSyncRuntime.restart(ctx)
                } else {
                    ClipboardSyncRuntime.stop()
                }
                updateAccountSummary()
                true
            }
        })

        accountPreference = Preference(ctx).apply {
            title = getString(R.string.clipboard_sync_account)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                if (!isAdded) return@setOnPreferenceClickListener false
                showAccountDialog(ctx)
                true
            }
        }
        category.addPreference(accountPreference)
        updateAccountSummary()
    }

    override fun onResume() {
        super.onResume()
        if (::accountPreference.isInitialized) updateAccountSummary()
    }

    private fun updateAccountSummary() {
        accountPreference.summary = when {
            !syncPreferences.hasMqttConfig() ->
                getString(R.string.clipboard_sync_account_not_configured)
            syncPreferences.enabled ->
                "${syncPreferences.mqttUsername} · ${ClipboardSyncRuntime.state}"
            else ->
                syncPreferences.mqttUsername
        }
    }

    private fun showAccountDialog(ctx: Context) {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val host = EditText(ctx).apply {
            hint = getString(R.string.clipboard_sync_host)
            setText(syncPreferences.mqttHost)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val port = EditText(ctx).apply {
            hint = getString(R.string.clipboard_sync_port)
            setText(syncPreferences.mqttPort.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val username = EditText(ctx).apply {
            hint = getString(R.string.clipboard_sync_username)
            setText(syncPreferences.mqttUsername)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val password = EditText(ctx).apply {
            hint = getString(R.string.clipboard_sync_password)
            setText(syncPreferences.mqttPassword)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(
                host,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(
                port,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(
                username,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(
                password,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.clipboard_sync_account)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                syncPreferences.mqttHost = host.text.toString().trim()
                syncPreferences.mqttPort = port.text.toString().toIntOrNull()
                    ?.takeIf { it in 1..65535 }
                    ?: ClipboardSyncPreferences.DEFAULT_MQTT_PORT
                syncPreferences.mqttUsername = username.text.toString()
                syncPreferences.mqttPassword = password.text.toString()
                ClipboardSyncRuntime.restart(ctx)
                updateAccountSummary()
            }
            .show()
    }
}
