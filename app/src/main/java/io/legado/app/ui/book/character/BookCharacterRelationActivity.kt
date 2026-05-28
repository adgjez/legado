package io.legado.app.ui.book.character

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
import io.legado.app.help.glide.ImageLoader
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
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
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
            onCharacterClick = { character -> openCharacterCard(character) }
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

    private fun openCharacterCard(character: BookCharacter) {
        startActivity<BookCharacterCardActivity> {
            putExtra(BookCharacterManageActivity.EXTRA_BOOK_URL, bookUrl)
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, character.id)
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
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        private val nodePath = Path()
        private val bitmapRect = Rect()
        private val nodePositions = mutableMapOf<Long, Pair<Float, Float>>()
        private val avatarBitmaps = mutableMapOf<Long, Bitmap>()
        private val avatarTargets = mutableMapOf<Long, CustomTarget<Bitmap>>()
        private var viewCharacters: List<BookCharacter> = emptyList()
        private var viewRelations: List<BookCharacterRelation> = emptyList()
        private var scale = 1f
        private var offsetX = 0f
        private var offsetY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        private var moved = false
        var onRelationClick: ((BookCharacterRelation) -> Unit)? = null
        var onCharacterClick: ((BookCharacter) -> Unit)? = null
        private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldScale = scale
                scale = (scale * detector.scaleFactor).coerceIn(0.65f, 2.8f)
                val factor = scale / oldScale
                offsetX = detector.focusX - (detector.focusX - offsetX) * factor
                offsetY = detector.focusY - (detector.focusY - offsetY) * factor
                invalidate()
                return true
            }
        })
        private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetViewport()
                return true
            }
        })

        init {
            background = UiCorner.panelRounded(
                context,
                ContextCompat.getColor(context, R.color.background_card),
                UiCorner.panelRadius(context)
            )
            nodePaint.color = context.accentColor
            selectedPaint.color = ContextCompat.getColor(context, R.color.background_menu)
            gridPaint.color = ColorUtilsSafe.adjustAlpha(context.primaryTextColor, 0.08f)
            gridPaint.style = Paint.Style.STROKE
            gridPaint.strokeWidth = 1.dpToPx().toFloat()
            linePaint.color = ColorUtilsSafe.adjustAlpha(context.primaryTextColor, 0.32f)
            linePaint.strokeWidth = 2.dpToPx().toFloat()
            textPaint.color = context.primaryTextColor
            textPaint.textSize = 13.dpToPx().toFloat()
            textPaint.textAlign = Paint.Align.CENTER
        }

        fun setData(characters: List<BookCharacter>, relations: List<BookCharacterRelation>) {
            viewCharacters = characters
            viewRelations = relations
            preloadAvatars(characters)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            nodePositions.clear()
            val count = viewCharacters.size
            if (count == 0) return
            canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(scale, scale)
            val content = worldContentRect()
            drawMapBackground(canvas, content)
            layoutNodes(content)
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
                drawCharacterNode(canvas, character, point.first, point.second)
            }
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastX = event.x
                    lastY = event.y
                    dragging = true
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && dragging) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(dx) > 2.dpToPx() || abs(dy) > 2.dpToPx()) moved = true
                        offsetX += dx
                        offsetY += dy
                        lastX = event.x
                        lastY = event.y
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    dragging = false
                    if (!moved) handleClick(event.x, event.y)
                }
                MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    dragging = false
                }
            }
            return true
        }

        private fun handleClick(rawX: Float, rawY: Float): Boolean {
            val x = (rawX - offsetX) / scale
            val y = (rawY - offsetY) / scale
            nodePositions.forEach { (id, point) ->
                if (hypot((x - point.first).toDouble(), (y - point.second).toDouble()) <= nodeHitRadius(id)) {
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
            return false
        }

        private fun resetViewport() {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            invalidate()
        }

        private fun worldContentRect(): RectF {
            return RectF(
                paddingLeft.toFloat(),
                paddingTop.toFloat(),
                (width - paddingRight).toFloat(),
                (height - paddingBottom).toFloat()
            )
        }

        private fun drawMapBackground(canvas: Canvas, content: RectF) {
            val centerX = content.centerX()
            val centerY = content.centerY()
            val maxRadius = min(content.width(), content.height()) * 0.44f
            canvas.drawCircle(centerX, centerY, maxRadius * 0.42f, gridPaint)
            canvas.drawCircle(centerX, centerY, maxRadius * 0.72f, gridPaint)
            canvas.drawCircle(centerX, centerY, maxRadius, gridPaint)
            val step = 48.dpToPx().toFloat()
            var x = content.left + step
            while (x < content.right) {
                canvas.drawLine(x, content.top, x, content.bottom, gridPaint)
                x += step
            }
            var y = content.top + step
            while (y < content.bottom) {
                canvas.drawLine(content.left, y, content.right, y, gridPaint)
                y += step
            }
        }

        private fun layoutNodes(content: RectF) {
            val centerX = content.centerX()
            val centerY = content.centerY()
            val centers = centerCharacters()
            val outer = viewCharacters.filterNot { c -> centers.any { it.id == c.id } }
            if (centers.size <= 1) {
                centers.firstOrNull()?.let { nodePositions[it.id] = centerX to centerY }
            } else {
                val centerRadius = 42f.dpToPx()
                centers.forEachIndexed { index, character ->
                    val angle = -Math.PI / 2 + index * Math.PI * 2 / centers.size
                    nodePositions[character.id] = centerX + cos(angle).toFloat() * centerRadius to
                            centerY + sin(angle).toFloat() * centerRadius
                }
            }
            val radiusX = max(70f.dpToPx(), content.width() * 0.38f)
            val radiusY = max(58f.dpToPx(), content.height() * 0.33f)
            outer.forEachIndexed { index, character ->
                val relatedWeight = relationWeightToCenters(character.id, centers)
                val ringBias = if (relatedWeight > 0) 0.82f else 1f
                val angle = -Math.PI / 2 + index * Math.PI * 2 / outer.size.coerceAtLeast(1)
                val x = centerX + cos(angle).toFloat() * radiusX * ringBias
                val y = centerY + sin(angle).toFloat() * radiusY * ringBias
                nodePositions[character.id] = x to y
            }
        }

        private fun centerCharacters(): List<BookCharacter> {
            val main = viewCharacters.filter { it.roleLevel == BookCharacter.ROLE_MAIN }
            if (main.isNotEmpty()) return main
            val important = viewCharacters.filter { it.roleLevel == BookCharacter.ROLE_IMPORTANT }
            if (important.isNotEmpty()) return important
            return viewCharacters.take(1)
        }

        private fun relationWeightToCenters(characterId: Long, centers: List<BookCharacter>): Int {
            val centerIds = centers.map { it.id }.toSet()
            return viewRelations.sumOf { relation ->
                if ((relation.fromCharacterId == characterId && relation.toCharacterId in centerIds) ||
                    (relation.toCharacterId == characterId && relation.fromCharacterId in centerIds)
                ) relation.strength.coerceIn(0, 100) else 0
            }
        }

        private fun drawCharacterNode(canvas: Canvas, character: BookCharacter, x: Float, y: Float) {
            val roleBoost = when (character.roleLevel) {
                BookCharacter.ROLE_MAIN -> 8.dpToPx()
                BookCharacter.ROLE_IMPORTANT -> 4.dpToPx()
                else -> 0
            }
            val outerRadius = 27.dpToPx().toFloat() + roleBoost
            val innerRadius = outerRadius - 5.dpToPx()
            selectedPaint.color = if (character.roleLevel == BookCharacter.ROLE_MAIN) {
                ColorUtilsSafe.adjustAlpha(accentColor, 0.22f)
            } else {
                ContextCompat.getColor(context, R.color.background_menu)
            }
            canvas.drawCircle(x, y, outerRadius, selectedPaint)
            val bitmap = avatarBitmaps[character.id]
            if (bitmap != null && !bitmap.isRecycled) {
                bitmapRect.set(0, 0, bitmap.width, bitmap.height)
                val dst = RectF(x - innerRadius, y - innerRadius, x + innerRadius, y + innerRadius)
                nodePath.reset()
                nodePath.addCircle(x, y, innerRadius, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(nodePath)
                canvas.drawBitmap(bitmap, bitmapRect, dst, avatarPaint)
                canvas.restore()
            } else {
                canvas.drawCircle(x, y, innerRadius, nodePaint)
                val label = character.displayName().take(1)
                canvas.drawText(label, x, y + 5.dpToPx(), textPaint)
            }
            canvas.drawText(character.displayName().take(6), x, y + outerRadius + 16.dpToPx(), textPaint)
        }

        private fun nodeHitRadius(id: Long): Int {
            val roleLevel = viewCharacters.firstOrNull { it.id == id }?.roleLevel ?: BookCharacter.ROLE_NORMAL
            return when (roleLevel) {
                BookCharacter.ROLE_MAIN -> 46.dpToPx()
                BookCharacter.ROLE_IMPORTANT -> 42.dpToPx()
                else -> 36.dpToPx()
            }
        }

        private fun preloadAvatars(characters: List<BookCharacter>) {
            val ids = characters.map { it.id }.toSet()
            avatarBitmaps.keys.filterNot { it in ids }.forEach { avatarBitmaps.remove(it) }
            avatarTargets.keys.filterNot { it in ids }.forEach { avatarTargets.remove(it) }
            characters.forEach { character ->
                val avatar = character.avatar
                if (avatar.isBlank() || avatarBitmaps.containsKey(character.id) || avatarTargets.containsKey(character.id)) return@forEach
                val target = object : CustomTarget<Bitmap>(56.dpToPx(), 56.dpToPx()) {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        avatarBitmaps[character.id] = resource
                        avatarTargets.remove(character.id)
                        invalidate()
                    }

                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) = Unit
                }
                avatarTargets[character.id] = target
                ImageLoader.loadBitmap(context, avatar).into(target)
            }
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
