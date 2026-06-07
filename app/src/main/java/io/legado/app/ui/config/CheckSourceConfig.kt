package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogCheckSourceConfigBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.CheckSource
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick

class CheckSourceConfig : BaseDialogFragment(R.layout.dialog_check_source_config) {

    private val binding by viewBinding(DialogCheckSourceConfigBinding::bind)

    //允许的最小超时时间，秒
    private val minTimeout = 0L

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.run {
            fun applyQuickDepth(depth: Int = checkSourceDepth.selectedItemPosition) {
                checkInfo.isChecked = depth >= CheckSource.CHECK_DEPTH_INFO
                checkCategory.isChecked = depth >= CheckSource.CHECK_DEPTH_CATEGORY
                checkContent.isChecked = depth >= CheckSource.CHECK_DEPTH_CONTENT
            }

            fun refreshCheckItemState() {
                val quickMode = checkSourceQuickMode.isChecked
                llCheckSourceDepth.isVisible = quickMode
                if (quickMode) {
                    applyQuickDepth()
                    checkInfo.isEnabled = false
                    checkCategory.isEnabled = false
                    checkContent.isEnabled = false
                    return
                }
                checkInfo.isEnabled = checkSearch.isChecked || checkDiscovery.isChecked
                if (!checkInfo.isEnabled || !checkInfo.isChecked) {
                    checkCategory.isChecked = false
                    checkContent.isChecked = false
                    checkCategory.isEnabled = false
                    checkContent.isEnabled = false
                } else {
                    checkCategory.isEnabled = true
                    checkContent.isEnabled = checkCategory.isChecked
                    if (!checkCategory.isChecked) {
                        checkContent.isChecked = false
                    }
                }
            }

            fun disableInfoSection() {
                checkInfo.isChecked = false
                checkInfo.isEnabled = false
                checkCategory.isChecked = false
                checkContent.isChecked = false
                checkCategory.isEnabled = false
                checkContent.isEnabled = false
            }
            checkDomain.onClick {
                if (!checkSearch.isChecked && !checkDiscovery.isChecked && !checkDomain.isChecked) {
                    checkSearch.isChecked = true
                }
                refreshCheckItemState()
            }
            checkSearch.onClick {
                if (!checkSearch.isChecked && !checkDiscovery.isChecked) {
                    disableInfoSection()
                    if (!checkDomain.isChecked) {
                        checkDiscovery.isChecked = true
                    }
                } else {
                    checkInfo.isEnabled = true
                }
                refreshCheckItemState()
            }
            checkDiscovery.onClick {
                if (!checkSearch.isChecked && !checkDiscovery.isChecked) {
                    disableInfoSection()
                    if (!checkDomain.isChecked) {
                        checkSearch.isChecked = true
                    }
                } else {
                    checkInfo.isEnabled = true
                }
                refreshCheckItemState()
            }
            checkInfo.onClick {
                if (!checkInfo.isChecked) {
                    checkCategory.isChecked = false
                    checkContent.isChecked = false
                    checkCategory.isEnabled = false
                    checkContent.isEnabled = false
                } else {
                    checkCategory.isEnabled = true
                }
                refreshCheckItemState()
            }
            checkCategory.onClick {
                if (!checkCategory.isChecked) {
                    checkContent.isChecked = false
                    checkContent.isEnabled = false
                } else {
                    checkContent.isEnabled = true
                }
                refreshCheckItemState()
            }
            checkSourceQuickMode.onClick {
                refreshCheckItemState()
            }
            val depthAdapter = ArrayAdapter(
                requireContext(),
                R.layout.item_text_common,
                listOf(
                    getString(R.string.check_source_depth_result),
                    getString(R.string.check_source_depth_info),
                    getString(R.string.check_source_depth_category),
                    getString(R.string.check_source_depth_content)
                )
            )
            depthAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
            checkSourceDepth.adapter = depthAdapter
            checkSourceDepth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (checkSourceQuickMode.isChecked) {
                        applyQuickDepth(position)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        CheckSource.run {
            binding.checkSourceTimeout.setText((timeout / 1000).toString())
            binding.checkSourceThreadCount.setText(normalizedThreadCount().toString())
            binding.checkSourceQuickMode.isChecked = quickMode
            binding.checkSourceDepth.setSelection(normalizedCheckDepth())
            binding.wSourceComment.isChecked  = wSourceComment
            binding.checkDomain.isChecked = checkDomain
            binding.checkSearch.isChecked = checkSearch
            binding.checkDiscovery.isChecked = checkDiscovery
            binding.checkInfo.isChecked = shouldCheckInfo()
            binding.checkCategory.isChecked = shouldCheckCategory()
            binding.checkContent.isChecked = shouldCheckContent()
            binding.run {
                val quickMode = checkSourceQuickMode.isChecked
                llCheckSourceDepth.isVisible = quickMode
                checkInfo.isEnabled = !quickMode && (checkSearch.isChecked || checkDiscovery.isChecked)
                checkCategory.isEnabled = !quickMode && checkInfo.isChecked
                checkContent.isEnabled = !quickMode && checkCategory.isChecked
            }
            binding.tvCancel.onClick {
                dismiss()
            }
            binding.tvOk.onClick {
                val text = binding.checkSourceTimeout.text.toString()
                when {
                    text.isBlank() -> {
                        toastOnUi("${getString(R.string.timeout)}${getString(R.string.cannot_empty)}")
                        return@onClick
                    }
                    text.toLong() <= minTimeout -> {
                        toastOnUi(
                            "${getString(R.string.timeout)}${getString(R.string.less_than)}${minTimeout}${
                                getString(
                                    R.string.seconds
                                )
                            }"
                        )
                        return@onClick
                    }
                    else -> timeout = text.toLong() * 1000
                }
                val threadText = binding.checkSourceThreadCount.text.toString()
                val threadValue = threadText.toIntOrNull()
                if (threadValue == null || threadValue !in 1..10) {
                    toastOnUi(R.string.check_source_thread_count_invalid)
                    return@onClick
                }
                threadCount = threadValue
                quickMode = binding.checkSourceQuickMode.isChecked
                checkDepth = binding.checkSourceDepth.selectedItemPosition
                wSourceComment = binding.wSourceComment.isChecked
                checkDomain = binding.checkDomain.isChecked
                checkSearch = binding.checkSearch.isChecked
                checkDiscovery = binding.checkDiscovery.isChecked
                if (quickMode) {
                    checkInfo = checkDepth >= CHECK_DEPTH_INFO
                    checkCategory = checkDepth >= CHECK_DEPTH_CATEGORY
                    checkContent = checkDepth >= CHECK_DEPTH_CONTENT
                } else {
                    checkInfo = binding.checkInfo.isChecked
                    checkCategory = binding.checkCategory.isChecked
                    checkContent = binding.checkContent.isChecked
                }
                putConfig()
                putPrefString(PreferKey.checkSource, summary)
                dismiss()
            }
        }
    }
}
