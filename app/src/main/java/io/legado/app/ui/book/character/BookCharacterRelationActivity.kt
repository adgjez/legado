package io.legado.app.ui.book.character

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.BookCharacterRelation
import io.legado.app.databinding.ItemThemePackageBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class BookCharacterRelationActivity : BaseActivity<ViewBinding>() {

    private lateinit var titleBar: TitleBar
    private lateinit var summaryView: TextView
    private lateinit var networkView: CharacterNetworkView
    private lateinit var recyclerView: RecyclerView
    private val adapter = RelationAdapter()
    private var bookUrl: String = ""
    private var characters: List<BookCharacter> = emptyList()
    private var relations: List<BookCharacterRelation> = emptyList()

    override val binding: ViewBinding by lazy {
        SimpleViewBinding(createContentView())
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra(BookCharacterManageActivity.EXTRA_BOOK_URL).orEmpty()
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ADD, 0, "添加关系")
            .setIcon(R.drawable.ic_add)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_ADD -> {
                editRelation(null)
                true
            }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        titleBar = TitleBar(this).apply {
            id = R.id.title_bar
            title = "角色关系网"
        }
        root.addView(titleBar)
        summaryView = TextView(this).apply {
            setPadding(18.dpToPx(), 10.dpToPx(), 18.dpToPx(), 8.dpToPx())
            textSize = 13f
            setTextColor(secondaryTextColor)
        }
        root.addView(summaryView)
        networkView = CharacterNetworkView(this).apply {
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())
            onRelationClick = { relation -> editRelation(relation) }
            onCharacterClick = { character -> showCharacterCard(character) }
        }
        root.addView(networkView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            260.dpToPx()
        ).apply {
            leftMargin = 16.dpToPx()
            rightMargin = 16.dpToPx()
        })
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BookCharacterRelationActivity)
            adapter = this@BookCharacterRelationActivity.adapter
            clipToPadding = false
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 16.dpToPx())
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
        root.addView(recyclerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        root.applyUiBodyTypefaceDeep(uiTypeface())
        return root
    }

    private fun load() {
        if (bookUrl.isBlank()) {
            summaryView.text = "当前书籍不存在"
            return
        }
        lifecycleScope.launch {
            val data = withContext(IO) {
                appDb.bookCharacterDao.characters(bookUrl) to appDb.bookCharacterDao.relations(bookUrl)
            }
            characters = data.first
            relations = data.second.filter { relation ->
                characters.any { it.id == relation.fromCharacterId } &&
                        characters.any { it.id == relation.toCharacterId }
            }
            summaryView.text = "共 ${characters.size} 个角色，${relations.size} 条关系。点击节点查看角色，点击关系线或下方条目编辑关系。"
            networkView.setData(characters, relations)
            adapter.items = relations
        }
    }

    private fun editRelation(relation: BookCharacterRelation?) {
        if (characters.size < 2) {
            toastOnUi("至少需要两个角色")
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dpToPx(), 4.dpToPx(), 18.dpToPx(), 0)
        }
        val fromSpinner = createCharacterSpinner(relation?.fromCharacterId)
        val toSpinner = createCharacterSpinner(relation?.toCharacterId)
        val nameEdit = createEdit("关系名称，例如 师徒、同伴、敌对", relation?.relationName.orEmpty())
        val typeEdit = createEdit("关系属性，例如 亲密、敌对、利益、血缘", relation?.relationType.orEmpty())
        val strengthEdit = createEdit(
            "关系强度 0-100",
            (relation?.strength ?: 50).toString(),
            InputType.TYPE_CLASS_NUMBER
        )
        val descEdit = createEdit("关系说明", relation?.description.orEmpty(), minLines = 3)
        container.addView(label("角色 A"))
        container.addView(fromSpinner)
        container.addView(label("角色 B"))
        container.addView(toSpinner)
        container.addView(nameEdit)
        container.addView(typeEdit)
        container.addView(strengthEdit)
        container.addView(descEdit)
        alert(if (relation == null) "添加关系" else "编辑关系") {
            customView { ScrollView(this@BookCharacterRelationActivity).apply { addView(container) } }
            okButton {
                val from = characters.getOrNull(fromSpinner.selectedItemPosition)
                val to = characters.getOrNull(toSpinner.selectedItemPosition)
                if (from == null || to == null || from.id == to.id) {
                    toastOnUi("请选择两个不同角色")
                    return@okButton
                }
                saveRelation(
                    (relation ?: BookCharacterRelation(bookUrl = bookUrl)).copy(
                        fromCharacterId = from.id,
                        toCharacterId = to.id,
                        relationName = nameEdit.text?.toString().orEmpty().trim(),
                        relationType = typeEdit.text?.toString().orEmpty().trim(),
                        strength = strengthEdit.text?.toString()?.toIntOrNull()?.coerceIn(0, 100) ?: 50,
                        description = descEdit.text?.toString().orEmpty().trim()
                    )
                )
            }
            if (relation != null) {
                neutralButton(R.string.delete) { deleteRelation(relation) }
            }
            cancelButton()
        }
    }

    private fun createCharacterSpinner(selectedId: Long?): Spinner {
        val names = characters.map { it.displayName() }
        return Spinner(this).apply {
            adapter = ArrayAdapter(
                this@BookCharacterRelationActivity,
                android.R.layout.simple_spinner_dropdown_item,
                names
            )
            val index = characters.indexOfFirst { it.id == selectedId }
            if (index >= 0) setSelection(index)
        }
    }

    private fun createEdit(
        hint: String,
        value: String,
        inputTypeValue: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        minLines: Int = 1
    ): EditText {
        return EditText(this).apply {
            this.hint = hint
            inputType = inputTypeValue
            setText(value)
            if (minLines > 1) {
                this.minLines = minLines
                gravity = Gravity.TOP or Gravity.START
            } else {
                setSingleLine(true)
            }
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(secondaryTextColor)
            setPadding(0, 10.dpToPx(), 0, 2.dpToPx())
        }
    }

    private fun saveRelation(relation: BookCharacterRelation) {
        lifecycleScope.launch {
            withContext(IO) {
                val now = System.currentTimeMillis()
                val saving = relation.copy(
                    bookUrl = bookUrl,
                    relationName = relation.relationName.ifBlank { "关系" },
                    sortOrder = relation.sortOrder.takeIf { it > 0 }
                        ?: ((appDb.bookCharacterDao.maxRelationOrder(bookUrl) ?: -1) + 1),
                    updatedAt = now
                )
                if (saving.id > 0) {
                    appDb.bookCharacterDao.updateRelation(saving)
                } else {
                    appDb.bookCharacterDao.insertRelation(saving)
                }
            }
            load()
        }
    }

    private fun deleteRelation(relation: BookCharacterRelation) {
        lifecycleScope.launch {
            withContext(IO) {
                appDb.bookCharacterDao.deleteRelation(relation)
            }
            load()
        }
    }

    private fun showCharacterCard(character: BookCharacter) {
        alert(character.displayName()) {
            setMessage(
                listOf(
                    "身份：${character.identity.ifBlank { "未填写" }}",
                    "技能：${character.skills.ifBlank { "未填写" }}",
                    "属性：${character.attributes.ifBlank { "未填写" }}",
                    "形象：${character.appearance.ifBlank { "未填写" }}",
                    "性格：${character.personality.ifBlank { "未填写" }}",
                    "生平：${character.biography.ifBlank { "未填写" }}"
                ).joinToString("\n")
            )
            okButton()
        }
    }

    private fun characterName(id: Long): String {
        return characters.firstOrNull { it.id == id }?.displayName().orEmpty()
    }

    private inner class RelationAdapter : RecyclerView.Adapter<RelationAdapter.Holder>() {
        var items: List<BookCharacterRelation> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemThemePackageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(private val itemBinding: ItemThemePackageBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(relation: BookCharacterRelation) = itemBinding.run {
                root.background = UiCorner.panelRounded(
                    this@BookCharacterRelationActivity,
                    ContextCompat.getColor(this@BookCharacterRelationActivity, R.color.background_card),
                    UiCorner.panelRadius(this@BookCharacterRelationActivity)
                )
                cardPreview.visibility = View.GONE
                tvSource.visibility = View.GONE
                (layInfo.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.marginStart = 0
                    layInfo.layoutParams = it
                }
                tvName.text = "${characterName(relation.fromCharacterId)} → ${characterName(relation.toCharacterId)}"
                tvInfo.text = "${relation.displayName()} · ${relation.relationType.ifBlank { "未分类" }} · 强度 ${relation.strength}"
                tvName.applyUiSectionTitleStyle(this@BookCharacterRelationActivity)
                tvInfo.applyUiLabelStyle(this@BookCharacterRelationActivity)
                tvInfo.setTextColor(secondaryTextColor)
                btnApply.text = "编辑"
                btnEdit.text = "查看"
                btnMore.text = "删除"
                listOf(btnApply, btnEdit, btnMore).forEach {
                    it.background = UiCorner.actionSelector(
                        Color.TRANSPARENT,
                        ContextCompat.getColor(this@BookCharacterRelationActivity, R.color.background_menu),
                        UiCorner.actionRadius(this@BookCharacterRelationActivity)
                    )
                    it.typeface = this@BookCharacterRelationActivity.uiTypeface()
                }
                btnApply.setTextColor(accentColor)
                btnEdit.setTextColor(primaryTextColor)
                btnMore.setTextColor(primaryTextColor)
                root.setOnClickListener { editRelation(relation) }
                btnApply.setOnClickListener { editRelation(relation) }
                btnEdit.setOnClickListener {
                    alert(relation.displayName()) {
                        setMessage(relation.description.ifBlank { "未填写说明" })
                        okButton()
                    }
                }
                btnMore.setOnClickListener { deleteRelation(relation) }
            }
        }
    }

    private inner class CharacterNetworkView(context: android.content.Context) : View(context) {
        private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val nodePositions = mutableMapOf<Long, Pair<Float, Float>>()
        private var viewCharacters: List<BookCharacter> = emptyList()
        private var viewRelations: List<BookCharacterRelation> = emptyList()
        var onRelationClick: ((BookCharacterRelation) -> Unit)? = null
        var onCharacterClick: ((BookCharacter) -> Unit)? = null

        init {
            background = UiCorner.panelRounded(
                context,
                ContextCompat.getColor(context, R.color.background_card),
                UiCorner.panelRadius(context)
            )
            nodePaint.color = context.accentColor
            selectedPaint.color = ContextCompat.getColor(context, R.color.background_menu)
            linePaint.color = ColorUtilsSafe.adjustAlpha(context.primaryTextColor, 0.32f)
            linePaint.strokeWidth = 2.dpToPx().toFloat()
            textPaint.color = context.primaryTextColor
            textPaint.textSize = 13.dpToPx().toFloat()
            textPaint.textAlign = Paint.Align.CENTER
        }

        fun setData(characters: List<BookCharacter>, relations: List<BookCharacterRelation>) {
            viewCharacters = characters
            viewRelations = relations
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            nodePositions.clear()
            val count = viewCharacters.size
            if (count == 0) return
            val content = RectF(
                paddingLeft.toFloat(),
                paddingTop.toFloat(),
                (width - paddingRight).toFloat(),
                (height - paddingBottom).toFloat()
            )
            val centerX = content.centerX()
            val centerY = content.centerY()
            val radiusX = max(42f.dpToPx(), content.width() * 0.36f)
            val radiusY = max(36f.dpToPx(), content.height() * 0.30f)
            viewCharacters.forEachIndexed { index, character ->
                val angle = -Math.PI / 2 + index * Math.PI * 2 / count
                val x = if (count == 1) centerX else centerX + cos(angle).toFloat() * radiusX
                val y = if (count == 1) centerY else centerY + sin(angle).toFloat() * radiusY
                nodePositions[character.id] = x to y
            }
            viewRelations.forEach { relation ->
                val from = nodePositions[relation.fromCharacterId] ?: return@forEach
                val to = nodePositions[relation.toCharacterId] ?: return@forEach
                linePaint.strokeWidth = (1.5f + relation.strength.coerceIn(0, 100) / 50f).dpToPx()
                canvas.drawLine(from.first, from.second, to.first, to.second, linePaint)
                val midX = (from.first + to.first) / 2f
                val midY = (from.second + to.second) / 2f
                canvas.drawText(relation.displayName().take(8), midX, midY - 4.dpToPx(), textPaint)
            }
            viewCharacters.forEach { character ->
                val point = nodePositions[character.id] ?: return@forEach
                canvas.drawCircle(point.first, point.second, 26.dpToPx().toFloat(), selectedPaint)
                canvas.drawCircle(point.first, point.second, 20.dpToPx().toFloat(), nodePaint)
                canvas.drawText(character.displayName().take(6), point.first, point.second + 43.dpToPx(), textPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val x = event.x
            val y = event.y
            nodePositions.forEach { (id, point) ->
                if (hypot((x - point.first).toDouble(), (y - point.second).toDouble()) <= 34.dpToPx()) {
                    viewCharacters.firstOrNull { it.id == id }?.let { onCharacterClick?.invoke(it) }
                    return true
                }
            }
            val relation = viewRelations.minByOrNull { relation ->
                val from = nodePositions[relation.fromCharacterId] ?: return@minByOrNull Float.MAX_VALUE
                val to = nodePositions[relation.toCharacterId] ?: return@minByOrNull Float.MAX_VALUE
                distanceToSegment(x, y, from.first, from.second, to.first, to.second)
            }?.takeIf { relation ->
                val from = nodePositions[relation.fromCharacterId] ?: return@takeIf false
                val to = nodePositions[relation.toCharacterId] ?: return@takeIf false
                distanceToSegment(x, y, from.first, from.second, to.first, to.second) <= 18.dpToPx()
            }
            relation?.let {
                onRelationClick?.invoke(it)
                return true
            }
            return true
        }

        private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            if (abs(dx) < 0.001f && abs(dy) < 0.001f) return hypot(px - ax, py - ay)
            val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
            val cx = ax + t * dx
            val cy = ay + t * dy
            return hypot(px - cx, py - cy)
        }
    }

    private object ColorUtilsSafe {
        fun adjustAlpha(color: Int, factor: Float): Int {
            val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        }
    }

    companion object {
        private const val MENU_ADD = 1
    }
}
