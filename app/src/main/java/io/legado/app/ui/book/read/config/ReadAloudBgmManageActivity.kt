package io.legado.app.ui.book.read.config

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadAloudBgmGroup
import io.legado.app.data.entities.ReadAloudBgmTrack
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReadAloudBgmManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val adapter = BgmAdapter()
    private var groups: List<ReadAloudBgmGroup> = emptyList()
    private var tracks: List<ReadAloudBgmTrack> = emptyList()
    private val selectedIds = linkedSetOf<Long>()

    private val importAudio = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            lifecycleScope.launch {
                kotlin.runCatching {
                    withContext(Dispatchers.IO) { importTrack(uri) }
                }.onSuccess {
                    selectedIds.clear()
                    load()
                    toastOnUi("导入完成")
                }.onFailure {
                    toastOnUi(it.localizedMessage ?: "导入失败")
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        load()
    }

    private fun initView() = binding.run {
        titleBar.title = "智能配乐"
        tabBar.visibility = View.GONE
        btnAdd.text = "导入音乐"
        btnAdd.background = UiCorner.actionSelector(
            ContextCompat.getColor(this@ReadAloudBgmManageActivity, R.color.background_card),
            ContextCompat.getColor(this@ReadAloudBgmManageActivity, R.color.background_menu),
            UiCorner.actionRadius(this@ReadAloudBgmManageActivity)
        )
        btnAdd.setOnClickListener { showImportActions() }
        tvSummary.setTextColor(secondaryTextColor)
        recyclerView.layoutManager = LinearLayoutManager(this@ReadAloudBgmManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        root.applyUiBodyTypefaceDeep(uiTypeface())
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ADD_GROUP, 0, "新增分组").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_MOVE_SELECTED, 1, "批量分组").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_DELETE_SELECTED, 2, "批量删除").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_ADD_GROUP -> showGroupEditor()
            MENU_MOVE_SELECTED -> moveSelected()
            MENU_DELETE_SELECTED -> deleteSelected()
        }
        return true
    }

    private fun showImportActions() {
        selector("智能配乐", listOf("导入音频文件", "新增分组")) { _, index ->
            when (index) {
                0 -> importAudio.launch {
                    mode = HandleFileContract.FILE
                    title = "导入配乐"
                    allowExtensions = arrayOf("mp3", "wav", "m4a", "aac", "ogg", "flac")
                }
                1 -> showGroupEditor()
            }
        }
    }

    private fun load() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                appDb.readAloudBgmDao.groups() to appDb.readAloudBgmDao.enabledTracks()
            }
            groups = data.first
            tracks = data.second
            adapter.submit(tracks)
            binding.tvSummary.text = if (tracks.isEmpty()) {
                "暂无配乐。导入音乐后，AI 可以按段落范围选择配乐。"
            } else {
                "${tracks.size} 首音乐 · ${groups.size.coerceAtLeast(1)} 个分组 · 已选 ${selectedIds.size}"
            }
        }
    }

    private fun showGroupEditor(group: ReadAloudBgmGroup? = null) {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = "分组名称"
            editView.setText(group?.name.orEmpty())
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(if (group == null) "新增分组" else "编辑分组") {
            customView { binding.root }
            okButton {
                val name = binding.editView.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    toastOnUi("分组名称不能为空")
                    return@okButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    if (group == null) {
                        appDb.readAloudBgmDao.insertGroup(
                            ReadAloudBgmGroup(
                                name = name,
                                sortOrder = (appDb.readAloudBgmDao.maxGroupOrder() ?: 0) + 1,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    } else {
                        appDb.readAloudBgmDao.updateGroup(group.copy(name = name, updatedAt = now))
                    }
                    launch(Dispatchers.Main) { load() }
                }
            }
            cancelButton()
        }
    }

    private suspend fun importTrack(uri: Uri) {
        val groupId = ensureDefaultGroup()
        val displayName = displayName(uri).ifBlank { "bgm-${System.currentTimeMillis()}" }
        val extension = displayName.substringAfterLast('.', "mp3").lowercase()
        val folder = File(filesDir, "readAloudBgm").apply { mkdirs() }
        val target = File(folder, "${System.currentTimeMillis()}_${displayName.toSafeFileName(extension)}")
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取音频文件")
        val checksum = target.inputStream().use { MD5Utils.md5Encode(it) }
        val duration = readDuration(target)
        val now = System.currentTimeMillis()
        appDb.readAloudBgmDao.insertTrack(
            ReadAloudBgmTrack(
                groupId = groupId,
                name = displayName.substringBeforeLast('.'),
                fileName = displayName,
                filePath = target.absolutePath,
                checksum = checksum,
                durationMs = duration,
                sortOrder = (appDb.readAloudBgmDao.maxTrackOrder(groupId) ?: 0) + 1,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty().substringAfterLast('/')
    }

    private fun readDuration(file: File): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }

    private fun ensureDefaultGroup(): Long {
        groups.firstOrNull { it.id == 0L || it.name == "默认分组" }?.let { return it.id }
        appDb.readAloudBgmDao.groups().firstOrNull { it.name == "默认分组" }?.let { return it.id }
        val now = System.currentTimeMillis()
        return appDb.readAloudBgmDao.insertGroup(
            ReadAloudBgmGroup(name = "默认分组", sortOrder = 0, createdAt = now, updatedAt = now)
        )
    }

    private fun showTrackActions(track: ReadAloudBgmTrack) {
        selector(track.displayName(), listOf("编辑标签", "移动分组", "删除")) { _, index ->
            when (index) {
                0 -> showTagsEditor(track)
                1 -> moveTracks(listOf(track.id))
                2 -> confirmDelete(listOf(track.id))
            }
        }
    }

    private fun showTagsEditor(track: ReadAloudBgmTrack) {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = "标签用逗号分隔，例如 紧张,日常,战斗"
            editView.setText(track.tags)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert("编辑标签") {
            customView { binding.root }
            okButton {
                val tags = binding.editView.text?.toString().orEmpty()
                    .split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(",")
                lifecycleScope.launch(Dispatchers.IO) {
                    appDb.readAloudBgmDao.updateTrack(track.copy(tags = tags, updatedAt = System.currentTimeMillis()))
                    launch(Dispatchers.Main) { load() }
                }
            }
            cancelButton()
        }
    }

    private fun moveSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) {
            toastOnUi("请先长按选择音乐")
            return
        }
        moveTracks(ids)
    }

    private fun moveTracks(ids: List<Long>) {
        val options = listOf(ReadAloudBgmGroup(id = 0L, name = "默认分组")) + groups
        selector("选择分组", options.map { it.displayName() }) { _, index ->
            val groupId = options[index].id
            lifecycleScope.launch(Dispatchers.IO) {
                appDb.readAloudBgmDao.moveTracks(ids, groupId)
                selectedIds.clear()
                launch(Dispatchers.Main) { load() }
            }
        }
    }

    private fun deleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) {
            toastOnUi("请先长按选择音乐")
            return
        }
        confirmDelete(ids)
    }

    private fun confirmDelete(ids: List<Long>) {
        alert("删除配乐") {
            setMessage("确定删除选中的 ${ids.size} 首音乐？文件也会从本地移除。")
            okButton {
                lifecycleScope.launch(Dispatchers.IO) {
                    ids.mapNotNull { appDb.readAloudBgmDao.track(it) }.forEach { track ->
                        runCatching { File(track.filePath).delete() }
                    }
                    appDb.readAloudBgmDao.deleteTracks(ids)
                    selectedIds.clear()
                    launch(Dispatchers.Main) { load() }
                }
            }
            cancelButton()
        }
    }

    private fun String.toSafeFileName(extension: String): String {
        val safe = replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "bgm.$extension" }
        return if (safe.contains('.')) safe else "$safe.$extension"
    }

    private fun groupName(groupId: Long): String {
        if (groupId == 0L) return "默认分组"
        return groups.firstOrNull { it.id == groupId }?.displayName() ?: "默认分组"
    }

    private inner class BgmAdapter : RecyclerView.Adapter<BgmHolder>() {
        private var items: List<ReadAloudBgmTrack> = emptyList()

        fun submit(value: List<ReadAloudBgmTrack>) {
            items = value
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BgmHolder {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
                background = UiCorner.actionSelector(
                    ContextCompat.getColor(parent.context, R.color.background_card),
                    ContextCompat.getColor(parent.context, R.color.background_menu),
                    UiCorner.panelRadius(parent.context)
                )
            }
            val title = TextView(parent.context).apply {
                textSize = 15f
                setTextColor(primaryTextColor)
                typeface = uiTypeface()
            }
            val sub = TextView(parent.context).apply {
                textSize = 12f
                setTextColor(secondaryTextColor)
                gravity = Gravity.START
            }
            root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 4.dpToPx()
            })
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dpToPx()
            }
            return BgmHolder(root, title, sub)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: BgmHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class BgmHolder(
        itemView: View,
        private val title: TextView,
        private val sub: TextView
    ) : RecyclerView.ViewHolder(itemView) {

        fun bind(track: ReadAloudBgmTrack) {
            val selected = track.id in selectedIds
            title.text = if (selected) "✓ ${track.displayName()}" else track.displayName()
            sub.text = buildList {
                add(groupName(track.groupId))
                if (track.tags.isNotBlank()) add(track.tags)
                if (track.durationMs > 0) add("${track.durationMs / 1000}s")
            }.joinToString(" · ")
            itemView.setOnClickListener {
                if (selectedIds.isNotEmpty()) {
                    toggle(track.id)
                } else {
                    showTrackActions(track)
                }
            }
            itemView.setOnLongClickListener {
                toggle(track.id)
                true
            }
        }

        private fun toggle(id: Long) {
            if (!selectedIds.add(id)) selectedIds.remove(id)
            load()
        }
    }

    companion object {
        private const val MENU_ADD_GROUP = 1
        private const val MENU_MOVE_SELECTED = 2
        private const val MENU_DELETE_SELECTED = 3
    }
}
