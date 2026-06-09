package io.legado.app.lib.prefs.fragment

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceGroup
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.legado.app.lib.prefs.EditTextPreferenceDialog
import io.legado.app.lib.prefs.ListPreferenceDialog
import io.legado.app.lib.prefs.MultiSelectListPreferenceDialog
import io.legado.app.utils.applyNavigationBarPadding

abstract class PreferenceFragment : PreferenceFragmentCompat() {

    private val dialogFragmentTag = "androidx.preference.PreferenceFragment.DIALOG"
    private val composeRefreshTick = mutableIntStateOf(0)
    private var composeView: ComposeView? = null
    private val composePreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            refreshComposePreferences()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(Color.TRANSPARENT)
        listView.setBackgroundColor(Color.TRANSPARENT)
        listView.clipToPadding = false
        listView.applyNavigationBarPadding()
        listView.itemAnimator = null
        installComposePreferenceContent()
        preferenceManager.sharedPreferences
            ?.registerOnSharedPreferenceChangeListener(composePreferenceChangeListener)
    }

    override fun onDestroyView() {
        preferenceManager.sharedPreferences
            ?.unregisterOnSharedPreferenceChangeListener(composePreferenceChangeListener)
        composeView = null
        super.onDestroyView()
    }

    /**
     * 按标题/副标题过滤偏好项
     */
    fun filterPreferences(query: String?) {
        val keyword = query?.trim().orEmpty()
        val root = preferenceScreen ?: return
        if (keyword.isBlank()) {
            setPreferenceVisible(root)
            refreshComposePreferences()
            return
        }
        filterPreferenceGroup(root, keyword.lowercase())
        refreshComposePreferences()
    }

    private fun filterPreferenceGroup(group: PreferenceGroup, keyword: String): Boolean {
        var anyVisible = false
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            val visible = when (preference) {
                is PreferenceGroup -> filterPreferenceGroup(preference, keyword) || preference.matches(keyword)
                else -> preference.matches(keyword)
            }
            preference.isVisible = visible
            anyVisible = anyVisible || visible
        }
        group.isVisible = anyVisible || group == preferenceScreen
        return anyVisible
    }

    private fun setPreferenceVisible(group: PreferenceGroup) {
        group.isVisible = true
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            preference.isVisible = true
            if (preference is PreferenceGroup) {
                setPreferenceVisible(preference)
            }
        }
    }

    private fun Preference.matches(keyword: String): Boolean {
        val titleText = title?.toString().orEmpty().lowercase()
        val summaryText = summary?.toString().orEmpty().lowercase()
        val keyText = key?.lowercase().orEmpty()
        return titleText.contains(keyword)
            || summaryText.contains(keyword)
            || keyText.contains(keyword)
    }

    protected fun refreshComposePreferences() {
        composeRefreshTick.intValue += 1
    }

    private fun installComposePreferenceContent() {
        val parent = listView.parent as? ViewGroup ?: return
        listView.visibility = View.GONE
        if (composeView != null) return
        composeView = ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ComposePreferenceScreen(
                    root = preferenceScreen,
                    refreshTick = composeRefreshTick.intValue,
                    onPreferenceClick = ::handleComposePreferenceClick,
                    onSwitchChange = ::handleComposeSwitchChange,
                    onSeekChange = ::handleComposeSeekChange
                )
            }
        }
        parent.addView(composeView)
    }

    private fun handleComposePreferenceClick(preference: Preference) {
        if (!preference.isEnabled || !preference.isSelectable) return
        preference.performClick()
        refreshComposePreferences()
    }

    private fun handleComposeSwitchChange(
        preference: androidx.preference.SwitchPreferenceCompat,
        checked: Boolean
    ) {
        if (!preference.isEnabled || preference.isChecked == checked) return
        if (preference.callChangeListener(checked)) {
            preference.isChecked = checked
        }
        refreshComposePreferences()
    }

    private fun handleComposeSeekChange(
        preference: io.legado.app.lib.prefs.SeekBarPreference,
        value: Int
    ) {
        if (!preference.isEnabled || preference.value == value) return
        if (preference.callChangeListener(value)) {
            preference.value = value
        }
        refreshComposePreferences()
    }

    @SuppressLint("RestrictedApi")
    override fun onDisplayPreferenceDialog(preference: Preference) {

        var handled = false
        if (callbackFragment is OnPreferenceDisplayDialogCallback) {
            handled =
                (callbackFragment as OnPreferenceDisplayDialogCallback)
                    .onPreferenceDisplayDialog(this, preference)
        }
        if (!handled && activity is OnPreferenceDisplayDialogCallback) {
            handled = (activity as OnPreferenceDisplayDialogCallback)
                .onPreferenceDisplayDialog(this, preference)
        }

        if (handled) {
            return
        }

        // check if dialog is already showing
        if (parentFragmentManager.findFragmentByTag(dialogFragmentTag) != null) {
            return
        }

        val dialogFragment: DialogFragment = when (preference) {
            is EditTextPreference -> {
                EditTextPreferenceDialog.newInstance(preference.getKey())
            }
            is ListPreference -> {
                ListPreferenceDialog.newInstance(preference.getKey())
            }
            is MultiSelectListPreference -> {
                MultiSelectListPreferenceDialog.newInstance(preference.getKey())
            }
            else -> {
                throw IllegalArgumentException(
                    "Cannot display dialog for an unknown Preference type: "
                            + preference.javaClass.simpleName
                            + ". Make sure to implement onPreferenceDisplayDialog() to handle "
                            + "displaying a custom dialog for this Preference."
                )
            }
        }
        @Suppress("DEPRECATION")
        dialogFragment.setTargetFragment(this, 0)

        dialogFragment.show(parentFragmentManager, dialogFragmentTag)
    }

}
