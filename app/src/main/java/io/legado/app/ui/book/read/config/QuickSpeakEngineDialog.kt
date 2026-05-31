package io.legado.app.ui.book.read.config

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemHttpTtsBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.gone
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class QuickSpeakEngineDialog : BaseDialogFragment(R.layout.dialog_recycler_view) {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel: SpeakEngineViewModel by viewModels()
    private val adapter by lazy { Adapter(requireContext()) }
    private val callBack: SpeakEngineDialog.CallBack? get() = parentFragment as? SpeakEngineDialog.CallBack
    private var selectedTts: String? = ReadAloud.ttsEngine

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            val sheetHeight = minOf(
                (resources.displayMetrics.heightPixels * 0.62f).toInt(),
                520.dpToPx()
            ).coerceAtLeast(320.dpToPx())
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, sheetHeight)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
    }

    private fun initView() = binding.run {
        root.background = ReaderSheetStyle.topSheetDrawable(ReaderSheetStyle.resolve(requireContext()))
        root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        toolBar.setBackgroundColor(primaryColor)
        toolBar.setTitle(R.string.speak_engine)
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        tvFooterLeft.text = "\u7ba1\u7406\u5f15\u64ce"
        tvFooterLeft.typeface = requireContext().uiTypeface()
        tvFooterLeft.visible()
        tvFooterLeft.setOnClickListener {
            parentFragment?.showDialogFragment(SpeakEngineDialog())
            dismissAllowingStateLoss()
        }
        tvCancel.visible()
        tvCancel.typeface = requireContext().uiTypeface()
        tvCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
        tvOk.gone()
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.httpTTSDao.flowAll().catch {
                AppLog.put("\u5feb\u901f\u6717\u8bfb\u5f15\u64ce\u83b7\u53d6\u5931\u8d25\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { httpTtsList ->
                adapter.setItems(systemOptions() + httpTtsList.map { QuickTtsOption.Http(it) })
            }
        }
    }

    private fun systemOptions(): List<QuickTtsOption> {
        val options = arrayListOf<QuickTtsOption>()
        options.add(
            QuickTtsOption.System(
                title = getString(R.string.system_tts),
                value = GSON.toJson(SelectItem(getString(R.string.system_tts), ""))
            )
        )
        viewModel.sysEngines.forEach { engine ->
            options.add(
                QuickTtsOption.System(
                    title = engine.label,
                    value = GSON.toJson(SelectItem(engine.label, engine.name))
                )
            )
        }
        return options
    }

    private fun saveGlobal(option: QuickTtsOption) {
        val value = option.value
        selectedTts = value
        ReadBook.book?.setTtsEngine(null)
        AppConfig.ttsEngine = value
        ReadAloud.upReadAloudClass()
        callBack?.upSpeakEngineSummary()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        if (option is QuickTtsOption.Http &&
            !option.httpTTS.loginUrl.isNullOrBlank() &&
            option.httpTTS.getLoginInfo().isNullOrBlank()
        ) {
            startActivity<SourceLoginActivity> {
                putExtra("type", "httpTts")
                putExtra("key", option.httpTTS.id.toString())
            }
        }
        dismissAllowingStateLoss()
    }

    private fun isSelected(option: QuickTtsOption): Boolean {
        val tts = selectedTts
        return when (option) {
            is QuickTtsOption.Http -> option.httpTTS.id.toString() == tts
            is QuickTtsOption.System -> {
                if (tts.isNullOrBlank()) {
                    option.systemValue.isBlank()
                } else {
                    tts.isJsonObject() &&
                        GSON.fromJsonObject<SelectItem<String>>(tts).getOrNull()?.value == option.systemValue
                }
            }
        }
    }

    private sealed class QuickTtsOption {
        abstract val title: String
        abstract val value: String

        data class System(
            override val title: String,
            override val value: String
        ) : QuickTtsOption() {
            val systemValue: String
                get() = GSON.fromJsonObject<SelectItem<String>>(value).getOrNull()?.value.orEmpty()
        }

        data class Http(
            val httpTTS: HttpTTS
        ) : QuickTtsOption() {
            override val title: String get() = httpTTS.name
            override val value: String get() = httpTTS.id.toString()
        }
    }

    private inner class Adapter(context: Context) :
        RecyclerAdapter<QuickTtsOption, ItemHttpTtsBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemHttpTtsBinding {
            return ItemHttpTtsBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemHttpTtsBinding,
            item: QuickTtsOption,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                cbName.text = item.title
                cbName.typeface = context.uiTypeface()
                cbName.isChecked = isSelected(item)
                labelSys.visible(item is QuickTtsOption.System)
                ivEdit.gone()
                ivMenuDelete.gone()
                root.applyUiBodyTypefaceDeep(context.uiTypeface())
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemHttpTtsBinding) {
            val clickListener = View.OnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { option ->
                    saveGlobal(option)
                }
            }
            binding.root.setOnClickListener(clickListener)
            binding.cbName.setOnClickListener(clickListener)
            binding.cbName.setOnLongClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { option ->
                    if (option is QuickTtsOption.Http) {
                        if (!option.httpTTS.loginUrl.isNullOrBlank()) {
                            startActivity<SourceLoginActivity> {
                                putExtra("type", "httpTts")
                                putExtra("key", option.httpTTS.id.toString())
                            }
                            return@setOnLongClickListener true
                        }
                    }
                }
                false
            }
        }
    }
}
