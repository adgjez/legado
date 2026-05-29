package io.legado.app.ui.config

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ActivityAiProviderManageBinding
import io.legado.app.databinding.ItemS3ContainerBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AiImageProviderManageActivity : BaseActivity<ActivityAiProviderManageBinding>() {

    override val binding by viewBinding(ActivityAiProviderManageBinding::inflate)
    private val adapter by lazy { Adapter() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.ai_image_provider_manage)
        binding.tvSummary.text = getString(R.string.ai_image_provider_manage_summary)
        binding.tvSummary.applyUiLabelStyle(this)
        binding.tvSummary.setTextColor(secondaryTextColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        (binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.btnAdd.text = getString(R.string.add)
        binding.btnAdd.background = actionBackground()
        binding.btnAdd.setOnClickListener { showAddSelector() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        adapter.setItems(AppConfig.aiImageProviderList.sortedBy { it.order })
    }

    private fun showAddSelector() {
        selector(
            getString(R.string.add),
            listOf(
                getString(R.string.ai_image_provider_openai),
                getString(R.string.ai_image_provider_js),
                getString(R.string.import_on_line)
            )
        ) { _, index ->
            if (index == 2) {
                importNetProviderAlert()
                return@selector
            }
            val type = if (index == 0) AiImageProviderConfig.TYPE_OPENAI else AiImageProviderConfig.TYPE_JS
            openEdit(AiImageProviderEditActivity.newIntent(this, null, type))
        }
    }

    private fun importNetProviderAlert() {
        alert(getString(R.string.import_on_line)) {
            val input = EditText(this@AiImageProviderManageActivity).apply {
                hint = "https://..."
                setSingleLine(true)
            }
            customView { input }
            okButton {
                val url = input.text?.toString().orEmpty().trim()
                if (url.isNotBlank()) importNetProvider(url)
            }
            cancelButton()
        }
    }

    private fun importNetProvider(importUrl: String) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val text = withContext(Dispatchers.IO) {
                    okHttpClient.newCallStrResponse {
                        url(importUrl)
                    }.body.orEmpty()
                }
                parseImportedProviders(text)
            }.onSuccess { providers ->
                if (providers.isEmpty()) {
                    toastOnUi("未找到可导入的生图规则")
                    return@onSuccess
                }
                val nextOrder = (AppConfig.aiImageProviderList.maxOfOrNull { it.order } ?: 0) + 1
                AppConfig.aiImageProviderList = AppConfig.aiImageProviderList + providers.mapIndexed { index, provider ->
                    provider.copy(order = nextOrder + index)
                }
                reload()
                toastOnUi("已导入 ${providers.size} 个生图规则")
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun parseImportedProviders(text: String): List<AiImageProviderConfig> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONArray().put(JSONObject(trimmed))
        }
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                add(parseImportedProvider(json))
            }
        }
    }

    private fun parseImportedProvider(json: JSONObject): AiImageProviderConfig {
        return if (json.has("showRule") || json.has("urlRule")) {
            parseDictImageProvider(json)
        } else {
            parseNativeImageProvider(json)
        }
    }

    private fun parseDictImageProvider(json: JSONObject): AiImageProviderConfig {
        val showRule = stripJsRule(json.optString("showRule"))
        require(showRule.isNotBlank()) { "字典规则缺少 showRule" }
        return AiImageProviderConfig(
            name = json.optString("name").ifBlank { getString(R.string.ai_image_provider_js) },
            type = AiImageProviderConfig.TYPE_JS,
            model = "fqnovel-ai-generate-v1",
            jsLib = json.optString("jsLib"),
            loginUrl = json.optString("loginUrl"),
            loginUi = json.optString("loginUi"),
            enabledCookieJar = json.optBoolean("enabledCookieJar", false),
            script = buildDictImageScript(showRule),
            timeoutMillisecond = json.optLong("timeoutMillisecond", 300_000L).takeIf { it > 0L } ?: 300_000L,
            order = json.optInt("sortNumber", 0),
            enabled = json.optBoolean("enabled", true)
        )
    }

    private fun parseNativeImageProvider(json: JSONObject): AiImageProviderConfig {
        val type = json.optString("type").takeIf {
            it == AiImageProviderConfig.TYPE_OPENAI || it == AiImageProviderConfig.TYPE_JS
        } ?: if (json.optString("script").isNotBlank()) {
            AiImageProviderConfig.TYPE_JS
        } else {
            AiImageProviderConfig.TYPE_OPENAI
        }
        return AiImageProviderConfig(
            name = json.optString("name").ifBlank { getString(R.string.ai_image_provider_manage) },
            type = type,
            baseUrl = json.optString("baseUrl"),
            apiKey = json.optString("apiKey"),
            headers = json.optString("headers"),
            model = json.optString("model"),
            defaultParamsJson = json.optString("defaultParamsJson").ifBlank { json.optString("params") },
            jsLib = json.optString("jsLib"),
            loginUrl = json.optString("loginUrl"),
            loginUi = json.optString("loginUi"),
            enabledCookieJar = json.optBoolean("enabledCookieJar", false),
            script = json.optString("script"),
            timeoutMillisecond = json.optLong("timeoutMillisecond", 300_000L).takeIf { it > 0L } ?: 300_000L,
            order = json.optInt("order", 0),
            enabled = json.optBoolean("enabled", true)
        )
    }

    private fun stripJsRule(rule: String): String {
        val text = rule.trim()
        return when {
            text.startsWith("@js:", true) -> text.substring(4).trim()
            text.startsWith("<js>", true) && text.lastIndexOf("<") > 3 ->
                text.substring(4, text.lastIndexOf("<")).trim()
            else -> text
        }
    }

    private fun buildDictImageScript(showRule: String): String {
        val quotedShowRule = JSONObject.quote(showRule)
        return """
            function run(prompt, provider) {
                var __prompt = String(prompt || '');
                var result = java.hexEncodeToString(__prompt);
                var key = __prompt;
                var html = eval($quotedShowRule);
                return __readArchiveImageFromResult(html);
            }

            function __readArchiveImageFromResult(value) {
                if (value == null) throw '未返回图片结果';
                if (Array.isArray(value) && value.length > 0) return __readArchiveImageFromResult(value[0]);
                if (typeof value === 'object') {
                    var fields = ['url', 'imageUrl', 'image', 'result', 'src'];
                    for (var i = 0; i < fields.length; i++) {
                        var item = value[fields[i]];
                        if (item) return __readArchiveImageFromResult(item);
                    }
                }
                var text = String(value).trim();
                if (/^https?:\/\//i.test(text) || /^data:image\//i.test(text)) return text;
                var img = text.match(/<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']/i);
                if (img && img[1]) return img[1];
                var url = text.match(/https?:\/\/[^\s"'<>]+/i);
                if (url && url[0]) return url[0];
                throw '未找到图片链接：' + text.replace(/<[^>]+>/g, '').slice(0, 120);
            }
        """.trimIndent()
    }

    private fun openEdit(intent: Intent) {
        startActivity(intent)
    }

    private fun showActions(provider: AiImageProviderConfig) {
        selector(
            provider.displayName(),
            listOf(
                getString(if (provider.enabled) R.string.disable else R.string.enable),
                getString(R.string.edit),
                getString(R.string.delete)
            )
        ) { _, index ->
            when (index) {
                0 -> {
                    AppConfig.aiImageProviderList = AppConfig.aiImageProviderList.map {
                        if (it.id == provider.id) it.copy(enabled = !it.enabled) else it
                    }
                    reload()
                }
                1 -> openEdit(AiImageProviderEditActivity.newIntent(this, provider.id, provider.type))
                2 -> confirmDelete(provider)
            }
        }
    }

    private fun confirmDelete(provider: AiImageProviderConfig) {
        alert(provider.displayName()) {
            setMessage(R.string.delete)
            okButton {
                AppConfig.aiImageProviderList = AppConfig.aiImageProviderList.filterNot { it.id == provider.id }
                reload()
                toastOnUi(R.string.delete)
            }
            cancelButton()
        }
    }

    private fun actionBackground() = UiCorner.actionSelector(
        ContextCompat.getColor(this, R.color.background_card),
        ContextCompat.getColor(this, R.color.background_menu),
        UiCorner.actionRadius(this)
    )

    private inner class Adapter :
        RecyclerAdapter<AiImageProviderConfig, ItemS3ContainerBinding>(this@AiImageProviderManageActivity) {

        override fun getViewBinding(parent: ViewGroup): ItemS3ContainerBinding {
            return ItemS3ContainerBinding.inflate(inflater, parent, false).apply {
                root.background = UiCorner.panelRounded(
                    root.context,
                    ContextCompat.getColor(root.context, R.color.background_card),
                    UiCorner.panelRadius(root.context)
                )
                btnMore.background = actionBackground()
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemS3ContainerBinding,
            item: AiImageProviderConfig,
            payloads: MutableList<Any>
        ) = binding.run {
            tvName.text = item.displayName()
            tvPath.text = if (item.type == AiImageProviderConfig.TYPE_OPENAI) {
                item.baseUrl.ifBlank { "OpenAI" }
            } else {
                getString(R.string.ai_image_provider_js)
            }
            tvCapacity.text = item.model.ifBlank { if (item.type == AiImageProviderConfig.TYPE_OPENAI) "gpt-image-1" else "JS" }
            tvState.text = getString(if (item.enabled) R.string.enabled else R.string.disabled)
            tvSelected.visibility = if (item.enabled) View.VISIBLE else View.GONE
            tvSelected.setTextColor(ContextCompat.getColor(this@AiImageProviderManageActivity, R.color.accent))
            tvName.applyUiSectionTitleStyle(this@AiImageProviderManageActivity)
            tvPath.applyUiLabelStyle(this@AiImageProviderManageActivity)
            tvCapacity.applyUiLabelStyle(this@AiImageProviderManageActivity)
            tvState.applyUiLabelStyle(this@AiImageProviderManageActivity)
            listOf(tvPath, tvCapacity, tvState).forEach { it.setTextColor(secondaryTextColor) }
            btnMore.setOnClickListener { showActions(item) }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemS3ContainerBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                    openEdit(AiImageProviderEditActivity.newIntent(this@AiImageProviderManageActivity, it.id, it.type))
                }
            }
        }
    }
}
