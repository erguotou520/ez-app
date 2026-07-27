/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

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
        syncPreferences = ClipboardSyncPreferences(requireContext())
        val category = PreferenceCategory(requireContext()).apply {
            title = getString(R.string.clipboard_sync)
            isIconSpaceReserved = false
        }
        screen.addPreference(category)

        category.addPreference(SwitchPreferenceCompat(requireContext()).apply {
            key = "ez_clipboard_sync_enabled"
            title = getString(R.string.clipboard_sync)
            summary = getString(R.string.clipboard_sync_summary)
            isIconSpaceReserved = false
            isChecked = syncPreferences.enabled
            setOnPreferenceChangeListener { _, value ->
                val enabled = value as Boolean
                syncPreferences.enabled = enabled
                if (enabled) {
                    AppPrefs.getInstance().clipboard.clipboardListening.setValue(true)
                    ClipboardSyncRuntime.restart(requireContext())
                } else {
                    ClipboardSyncRuntime.stop()
                }
                updateAccountSummary()
                true
            }
        })

        accountPreference = Preference(requireContext()).apply {
            title = getString(R.string.clipboard_sync_account)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showAccountDialog()
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

    private fun showAccountDialog() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val username = EditText(requireContext()).apply {
            hint = getString(R.string.clipboard_sync_username)
            setText(syncPreferences.mqttUsername)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val password = EditText(requireContext()).apply {
            hint = getString(R.string.clipboard_sync_password)
            setText(syncPreferences.mqttPassword)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clipboard_sync_account)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                syncPreferences.mqttUsername = username.text.toString()
                syncPreferences.mqttPassword = password.text.toString()
                ClipboardSyncRuntime.restart(requireContext())
                updateAccountSummary()
            }
            .show()
    }
}
