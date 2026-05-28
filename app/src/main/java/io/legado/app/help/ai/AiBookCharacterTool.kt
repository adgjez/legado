package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.BookCharacterRelation
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object AiBookCharacterTool {

    private const val TOOL_LIST_CHARACTERS = "list_book_characters"
    private const val TOOL_UPSERT_CHARACTER = "upsert_book_character"
    private const val TOOL_DELETE_CHARACTER = "delete_book_character"
    private const val TOOL_LIST_RELATIONS = "list_book_character_relations"
    private const val TOOL_UPSERT_RELATION = "upsert_book_character_relation"
    private const val TOOL_DELETE_RELATION = "delete_book_character_relation"

    fun resolvedTools(): List<AiResolvedTool> {
        return listOf(
            AiResolvedTool(TOOL_LIST_CHARACTERS, listCharactersDefinition()) { args -> listCharacters(args) },
            AiResolvedTool(TOOL_UPSERT_CHARACTER, upsertCharacterDefinition()) { args -> upsertCharacter(args) },
            AiResolvedTool(TOOL_DELETE_CHARACTER, deleteCharacterDefinition()) { args -> deleteCharacter(args) },
            AiResolvedTool(TOOL_LIST_RELATIONS, listRelationsDefinition()) { args -> listRelations(args) },
            AiResolvedTool(TOOL_UPSERT_RELATION, upsertRelationDefinition()) { args -> upsertRelation(args) },
            AiResolvedTool(TOOL_DELETE_RELATION, deleteRelationDefinition()) { args -> deleteRelation(args) }
        )
    }

    private fun listCharactersDefinition() = function(
        TOOL_LIST_CHARACTERS,
        "读取指定书籍的角色资料列表。优先传 bookUrl；没有 bookUrl 时可传 bookName 和 author。"
    ) {
        bookProps(this)
    }

    private fun upsertCharacterDefinition() = function(
        TOOL_UPSERT_CHARACTER,
        "新增或更新指定书籍的角色资料。更新时优先用 characterId，其次用同名角色。"
    ) {
        bookProps(this)
        put("characterId", intProp("可选，角色 ID。"))
        put("name", stringProp("角色名称，必填。"))
        put("avatar", stringProp("角色头像 URL 或本地路径。"))
        put("identity", stringProp("角色身份。"))
        put("skills", stringProp("角色技能。"))
        put("attributes", stringProp("角色属性。"))
        put("appearance", stringProp("角色形象描述。"))
        put("personality", stringProp("角色性格描述。"))
        put("biography", stringProp("角色生平。"))
    }

    private fun deleteCharacterDefinition() = function(
        TOOL_DELETE_CHARACTER,
        "删除指定书籍的角色资料，并同步删除该角色相关关系。"
    ) {
        bookProps(this)
        put("characterId", intProp("可选，角色 ID。"))
        put("name", stringProp("可选，角色名称。"))
    }

    private fun listRelationsDefinition() = function(
        TOOL_LIST_RELATIONS,
        "读取指定书籍的角色关系网。"
    ) {
        bookProps(this)
    }

    private fun upsertRelationDefinition() = function(
        TOOL_UPSERT_RELATION,
        "新增或更新两个角色之间的关系。可用角色 ID 或角色名称定位。"
    ) {
        bookProps(this)
        put("relationId", intProp("可选，关系 ID。"))
        put("fromCharacterId", intProp("角色 A 的 ID。"))
        put("toCharacterId", intProp("角色 B 的 ID。"))
        put("fromName", stringProp("角色 A 名称。"))
        put("toName", stringProp("角色 B 名称。"))
        put("relationName", stringProp("关系名称，例如 师徒、同伴、敌对。"))
        put("relationType", stringProp("关系属性，例如 亲密、敌对、利益、血缘。"))
        put("description", stringProp("关系说明。"))
        put("strength", intProp("关系强度，0 到 100。"))
    }

    private fun deleteRelationDefinition() = function(
        TOOL_DELETE_RELATION,
        "删除指定书籍中的一条角色关系。"
    ) {
        bookProps(this)
        put("relationId", intProp("关系 ID。"))
    }

    private suspend fun listCharacters(args: JSONObject?): String = withContext(IO) {
        val book = resolveBook(args) ?: return@withContext errorJson("未找到书籍")
        val characters = appDb.bookCharacterDao.characters(book.bookUrl)
        JSONObject().apply {
            put("ok", true)
            put("book", bookJson(book))
            put("characters", JSONArray().apply {
                characters.forEach { put(characterJson(it)) }
            })
        }.toString()
    }

    private suspend fun upsertCharacter(args: JSONObject?): String = withContext(IO) {
        val book = resolveBook(args) ?: return@withContext errorJson("未找到书籍")
        val name = args?.optString("name")?.trim().orEmpty()
        if (name.isBlank()) return@withContext errorJson("name 不能为空")
        val now = System.currentTimeMillis()
        val id = args?.optLong("characterId", 0L) ?: 0L
        val old = id.takeIf { it > 0 }?.let { appDb.bookCharacterDao.getCharacter(it) }
            ?: appDb.bookCharacterDao.getCharacter(book.bookUrl, name)
        val character = (old ?: BookCharacter(bookUrl = book.bookUrl)).copy(
            bookUrl = book.bookUrl,
            name = name,
            avatar = optText(args, "avatar") ?: old?.avatar.orEmpty(),
            identity = optText(args, "identity") ?: old?.identity.orEmpty(),
            skills = optText(args, "skills") ?: old?.skills.orEmpty(),
            attributes = optText(args, "attributes") ?: old?.attributes.orEmpty(),
            appearance = optText(args, "appearance") ?: old?.appearance.orEmpty(),
            personality = optText(args, "personality") ?: old?.personality.orEmpty(),
            biography = optText(args, "biography") ?: old?.biography.orEmpty(),
            sortOrder = old?.sortOrder ?: ((appDb.bookCharacterDao.maxCharacterOrder(book.bookUrl) ?: -1) + 1),
            createdAt = old?.createdAt?.takeIf { it > 0 } ?: now,
            updatedAt = now
        )
        val savedId = if (character.id > 0) {
            appDb.bookCharacterDao.updateCharacter(character)
            character.id
        } else {
            appDb.bookCharacterDao.insertCharacter(character)
        }
        JSONObject().apply {
            put("ok", true)
            put("character", characterJson(character.copy(id = savedId)))
        }.toString()
    }

    private suspend fun deleteCharacter(args: JSONObject?): String = withContext(IO) {
        val book = resolveBook(args) ?: return@withContext errorJson("未找到书籍")
        val character = resolveCharacter(book.bookUrl, args)
            ?: return@withContext errorJson("未找到角色")
        appDb.bookCharacterDao.deleteCharacterWithRelations(character)
        JSONObject().apply {
            put("ok", true)
            put("deletedCharacterId", character.id)
        }.toString()
    }

    private suspend fun listRelations(args: JSONObject?): String = withContext(IO) {
        val book = resolveBook(args) ?: return@withContext errorJson("未找到书籍")
        val characters = appDb.bookCharacterDao.characters(book.bookUrl)
        val relations = appDb.bookCharacterDao.relations(book.bookUrl)
        JSONObject().apply {
            put("ok", true)
            put("book", bookJson(book))
            put("characters", JSONArray().apply {
                characters.forEach { put(characterJson(it)) }
            })
            put("relations", JSONArray().apply {
                relations.forEach { put(relationJson(it, characters)) }
            })
        }.toString()
    }

    private suspend fun upsertRelation(args: JSONObject?): String = withContext(IO) {
        val book = resolveBook(args) ?: return@withContext errorJson("未找到书籍")
        val characters = appDb.bookCharacterDao.characters(book.bookUrl)
        val fromId = resolveCharacterId(args, "fromCharacterId", "fromName", characters)
        val toId = resolveCharacterId(args, "toCharacterId", "toName", characters)
        if (fromId == null || toId == null || fromId == toId) {
            return@withContext errorJson("请选择两个不同角色")
        }
        val relationId = args?.optLong("relationId", 0L) ?: 0L
        val old = relationId.takeIf { it > 0 }?.let { appDb.bookCharacterDao.getRelation(it) }
        val now = System.currentTimeMillis()
        val relation = (old ?: BookCharacterRelation(bookUrl = book.bookUrl)).copy(
            bookUrl = book.bookUrl,
            fromCharacterId = fromId,
            toCharacterId = toId,
            relationName = args?.optString("relationName")?.trim()?.ifBlank { null }
                ?: old?.relationName
                ?: "关系",
            relationType = optText(args, "relationType") ?: old?.relationType.orEmpty(),
            description = optText(args, "description") ?: old?.description.orEmpty(),
            strength = (args?.takeIf { it.has("strength") }?.optInt("strength") ?: old?.strength ?: 50)
                .coerceIn(0, 100),
            sortOrder = old?.sortOrder ?: ((appDb.bookCharacterDao.maxRelationOrder(book.bookUrl) ?: -1) + 1),
            updatedAt = now
        )
        val savedId = if (relation.id > 0) {
            appDb.bookCharacterDao.updateRelation(relation)
            relation.id
        } else {
            appDb.bookCharacterDao.insertRelation(relation)
        }
        JSONObject().apply {
            put("ok", true)
            put("relation", relationJson(relation.copy(id = savedId), characters))
        }.toString()
    }

    private suspend fun deleteRelation(args: JSONObject?): String = withContext(IO) {
        resolveBook(args) ?: return@withContext errorJson("未找到书籍")
        val relationId = args?.optLong("relationId", 0L) ?: 0L
        if (relationId <= 0L) return@withContext errorJson("relationId 不能为空")
        appDb.bookCharacterDao.deleteRelationById(relationId)
        JSONObject().apply {
            put("ok", true)
            put("deletedRelationId", relationId)
        }.toString()
    }

    private fun resolveBook(args: JSONObject?): Book? {
        val bookUrl = args?.optString("bookUrl")?.trim().orEmpty()
        if (bookUrl.isNotBlank()) {
            appDb.bookDao.getBook(bookUrl)?.let { return it }
        }
        val name = args?.optString("bookName")?.trim()
            ?: args?.optString("name")?.trim()
            ?: ""
        val author = args?.optString("author")?.trim().orEmpty()
        if (name.isBlank()) return null
        return if (author.isBlank()) {
            appDb.bookDao.findByName(name).firstOrNull()
        } else {
            appDb.bookDao.getBook(name, author)
                ?: appDb.bookDao.findByName(name).firstOrNull { it.author == author }
        }
    }

    private fun resolveCharacter(bookUrl: String, args: JSONObject?): BookCharacter? {
        val id = args?.optLong("characterId", 0L) ?: 0L
        if (id > 0L) return appDb.bookCharacterDao.getCharacter(id)?.takeIf { it.bookUrl == bookUrl }
        val name = args?.optString("name")?.trim().orEmpty()
        if (name.isBlank()) return null
        return appDb.bookCharacterDao.getCharacter(bookUrl, name)
    }

    private fun resolveCharacterId(
        args: JSONObject?,
        idKey: String,
        nameKey: String,
        characters: List<BookCharacter>
    ): Long? {
        val id = args?.optLong(idKey, 0L) ?: 0L
        if (id > 0L && characters.any { it.id == id }) return id
        val name = args?.optString(nameKey)?.trim().orEmpty()
        if (name.isBlank()) return null
        return characters.firstOrNull { it.name == name }?.id
            ?: characters.firstOrNull { it.name.contains(name, ignoreCase = true) }?.id
    }

    private fun characterJson(character: BookCharacter): JSONObject {
        return JSONObject().apply {
            put("id", character.id)
            put("bookUrl", character.bookUrl)
            put("name", character.name)
            put("avatar", character.avatar)
            put("identity", character.identity)
            put("skills", character.skills)
            put("attributes", character.attributes)
            put("appearance", character.appearance)
            put("personality", character.personality)
            put("biography", character.biography)
            put("updatedAt", character.updatedAt)
        }
    }

    private fun relationJson(relation: BookCharacterRelation, characters: List<BookCharacter>): JSONObject {
        val from = characters.firstOrNull { it.id == relation.fromCharacterId }
        val to = characters.firstOrNull { it.id == relation.toCharacterId }
        return JSONObject().apply {
            put("id", relation.id)
            put("bookUrl", relation.bookUrl)
            put("fromCharacterId", relation.fromCharacterId)
            put("fromName", from?.name.orEmpty())
            put("toCharacterId", relation.toCharacterId)
            put("toName", to?.name.orEmpty())
            put("relationName", relation.relationName)
            put("relationType", relation.relationType)
            put("description", relation.description)
            put("strength", relation.strength)
            put("updatedAt", relation.updatedAt)
        }
    }

    private fun bookJson(book: Book): JSONObject {
        return JSONObject().apply {
            put("bookUrl", book.bookUrl)
            put("name", book.name)
            put("author", book.author)
            put("origin", book.origin)
            put("originName", book.originName)
        }
    }

    private fun function(name: String, description: String, props: JSONObject.() -> Unit): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply(props))
                    put("additionalProperties", false)
                })
            })
        }
    }

    private fun bookProps(props: JSONObject) {
        props.put("bookUrl", stringProp("书籍 URL，优先用于精确定位当前书。"))
        props.put("bookName", stringProp("书名。没有 bookUrl 时使用。"))
        props.put("author", stringProp("作者名。"))
    }

    private fun stringProp(description: String) = JSONObject().apply {
        put("type", "string")
        put("description", description)
    }

    private fun intProp(description: String) = JSONObject().apply {
        put("type", "integer")
        put("description", description)
    }

    private fun optText(args: JSONObject?, key: String): String? {
        return args?.takeIf { it.has(key) }?.optString(key)?.trim()
    }

    private fun errorJson(message: String): String {
        return JSONObject().apply {
            put("ok", false)
            put("error", message)
        }.toString()
    }
}
