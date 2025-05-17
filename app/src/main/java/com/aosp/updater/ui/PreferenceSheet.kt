package com.aosp.updater.ui

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemProperties
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.aosp.updater.R
import com.aosp.updater.UpdatesCheckReceiver
import com.aosp.updater.controller.UpdaterService
import com.aosp.updater.misc.Constants
import com.aosp.updater.misc.Utils
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

@SuppressLint("UseSwitchCompatOrMaterialCode")
class PreferenceSheet : BottomSheetDialogFragment() {

    private var prefs: SharedPreferences? = null

    private var mUpdaterService: UpdaterService? = null
    
    private lateinit var preferencesAbPerfMode: Switch
    private lateinit var preferencesAutoDeleteUpdates: Switch
    private lateinit var preferencesMeteredNetworkWarning: Switch
    private lateinit var preferencesAutoUpdatesCheckInterval: Spinner

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.preferences_dialog, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(view) {
            preferencesAbPerfMode = requireViewById(R.id.preferences_ab_perf_mode)
            preferencesAutoDeleteUpdates = requireViewById(R.id.preferences_auto_delete_updates)
            preferencesMeteredNetworkWarning = requireViewById(R.id.preferences_metered_network_warning)
            preferencesAutoUpdatesCheckInterval = requireViewById(R.id.preferences_auto_updates_check_interval)
        }

        if (!Utils.isABDevice() || Utils.isABPerfModeForceEnabled(requireContext())) {
            preferencesAbPerfMode.visibility = View.GONE
        }

        if (Utils.isDeleteUpdatesForceEnabled(requireContext())) {
            preferencesAutoDeleteUpdates.visibility = View.GONE
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        preferencesAutoUpdatesCheckInterval.setSelection(Utils.getUpdateCheckSetting(requireContext()))
        preferencesAutoDeleteUpdates.isChecked =
            prefs!!.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, true)
        preferencesMeteredNetworkWarning.isChecked =
            prefs!!.getBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                prefs!!.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, true))
        preferencesAbPerfMode.isChecked =
            prefs!!.getBoolean(Constants.PREF_AB_PERF_MODE, true)
    }

    override fun onDismiss(dialog: DialogInterface) {
        prefs!!.edit()
            .putInt(
                Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                preferencesAutoUpdatesCheckInterval.selectedItemPosition
            )
            .putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, preferencesAutoDeleteUpdates.isChecked)
            .putBoolean(Constants.PREF_METERED_NETWORK_WARNING, preferencesMeteredNetworkWarning.isChecked)
            .putBoolean(Constants.PREF_AB_PERF_MODE, preferencesAbPerfMode.isChecked)
            .apply()

        if (Utils.isUpdateCheckEnabled(requireContext())) {
            UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(requireContext())
        } else {
            UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(requireContext())
            UpdatesCheckReceiver.cancelUpdatesCheck(requireContext())
        }

        if (Utils.isABDevice()) {
            val enableABPerfMode: Boolean = preferencesAbPerfMode.isChecked
            mUpdaterService?.updaterController?.setPerformanceMode(enableABPerfMode)
        }
        super.onDismiss(dialog)
    }

    fun setupPreferenceSheet(updaterService: UpdaterService): PreferenceSheet {
        this.mUpdaterService = updaterService
        return this
    }

}
