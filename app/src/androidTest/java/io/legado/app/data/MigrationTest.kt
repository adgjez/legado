package io.legado.app.data

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun getMigrations(from: Int, to: Int): Array<Migration> {
        return DatabaseMigrations.migrations.filter {
            it.startVersion >= from && it.endVersion <= to
        }.toTypedArray()
    }

    private fun getSingleMigration(from: Int, to: Int): Array<Migration> {
        return DatabaseMigrations.migrations.filter {
            it.startVersion == from && it.endVersion == to
        }.toTypedArray()
    }

    @Test
    @Throws(IOException::class)
    fun migrateAll() {
        // Create the earliest version of the database
        val db = helper.createDatabase(TEST_DB, 108)

        // Insert test data into existing tables with Chinese text, special characters, null values
        db.execSQL(
            """
            INSERT INTO ai_generated_images (
                id, name, prompt, providerId, providerName, model, localPath,
                originalSource, bookKey, bookName, bookAuthor, chapterKey,
                chapterIndex, chapterTitle, characterId, characterName,
                sourceType, sourceText, favorite, groupId, createdAt, updatedAt
            ) VALUES (
                'test-img-1', '测试图片', 'A beautiful sunset over mountains 🏔️',
                'provider-1', 'Test Provider', 'model-v1', '/path/to/image.png',
                'chat', 'bookKey1', '测试书籍', '测试作者', 'chapterKey1',
                3, '第三章', NULL, NULL,
                'chat', 'selected text', 0, NULL, 1700000000000, 1700000000000
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT INTO ai_image_groups (id, name, createdAt, sortOrder)
            VALUES ('group-1', '默认分组', 1700000000000, 0)
            """.trimIndent()
        )

        db.close()

        // Migrate 108 -> 109: creates ai_purified_text_cache table
        helper.runMigrationsAndValidate(
            TEST_DB, 109, true,
            getMigrations(108, 109)
        )

        // Migrate 109 -> 110: creates ai_generated_videos and ai_video_groups tables
        var migratedDb = helper.runMigrationsAndValidate(
            TEST_DB, 110, true,
            getMigrations(109, 110)
        )

        // Verify ai_generated_videos table exists
        val videoCursor = migratedDb.query("PRAGMA table_info(ai_generated_videos)")
        val videoColumns = mutableListOf<String>()
        while (videoCursor.moveToNext()) {
            videoColumns.add(videoCursor.getString(videoCursor.getColumnIndexOrThrow("name")))
        }
        videoCursor.close()
        assert(videoColumns.contains("id")) { "ai_generated_videos missing 'id' column" }
        assert(videoColumns.contains("bookKey")) { "ai_generated_videos missing 'bookKey' column" }
        assert(videoColumns.contains("lastAccessTime")) { "ai_generated_videos missing 'lastAccessTime' column" }

        // Verify idx_video_book_chapter index exists
        val videoIndexCursor = migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_video_book_chapter'"
        )
        assert(videoIndexCursor.count > 0) { "idx_video_book_chapter index not found" }
        videoIndexCursor.close()

        // Verify idx_purify_cache index exists (created in 108->109)
        val cacheIndexCursor = migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_purify_cache'"
        )
        assert(cacheIndexCursor.count > 0) { "idx_purify_cache index not found" }
        cacheIndexCursor.close()

        // Migrate 110 -> 111: creates ai_gen_tasks, ai_story_playlists, ai_story_scenes tables
        migratedDb = helper.runMigrationsAndValidate(
            TEST_DB, 111, true,
            getMigrations(110, 111)
        )

        // Verify ai_gen_tasks table exists
        val taskCursor = migratedDb.query("PRAGMA table_info(ai_gen_tasks)")
        val taskColumns = mutableListOf<String>()
        while (taskCursor.moveToNext()) {
            taskColumns.add(taskCursor.getString(taskCursor.getColumnIndexOrThrow("name")))
        }
        taskCursor.close()
        assert(taskColumns.contains("id")) { "ai_gen_tasks missing 'id' column" }
        assert(taskColumns.contains("modality")) { "ai_gen_tasks missing 'modality' column" }
        assert(taskColumns.contains("status")) { "ai_gen_tasks missing 'status' column" }

        // Migrate 111 -> 112: placeholder migration (no schema changes)
        migratedDb = helper.runMigrationsAndValidate(
            TEST_DB, 112, true,
            getMigrations(111, 112)
        )

        // Migrate 112 -> 113: creates ai_generated_audios and ai_audio_groups tables
        migratedDb = helper.runMigrationsAndValidate(
            TEST_DB, 113, true,
            getMigrations(112, 113)
        )

        // Verify ai_generated_audios table exists
        val audioCursor = migratedDb.query("PRAGMA table_info(ai_generated_audios)")
        val audioColumns = mutableListOf<String>()
        while (audioCursor.moveToNext()) {
            audioColumns.add(audioCursor.getString(audioCursor.getColumnIndexOrThrow("name")))
        }
        audioCursor.close()
        assert(audioColumns.contains("id")) { "ai_generated_audios missing 'id' column" }
        assert(audioColumns.contains("audioType")) { "ai_generated_audios missing 'audioType' column" }

        // Verify data integrity - check that our test data survived all migrations
        val cursor = migratedDb.query(
            "SELECT * FROM ai_generated_images WHERE id = 'test-img-1'"
        )
        cursor.moveToFirst()
        assert(cursor.getString(cursor.getColumnIndexOrThrow("name")) == "测试图片") {
            "Expected name='测试图片', got='${cursor.getString(cursor.getColumnIndexOrThrow("name"))}'"
        }
        assert(cursor.getString(cursor.getColumnIndexOrThrow("prompt")) == "A beautiful sunset over mountains 🏔️") {
            "Prompt data corrupted after migration"
        }
        assert(cursor.getString(cursor.getColumnIndexOrThrow("bookName")) == "测试书籍") {
            "Book name data corrupted after migration"
        }
        assert(cursor.isNull(cursor.getColumnIndexOrThrow("characterId"))) {
            "characterId should be NULL"
        }
        assert(cursor.isNull(cursor.getColumnIndexOrThrow("groupId"))) {
            "groupId should be NULL"
        }
        cursor.close()

        // Verify ai_image_groups data integrity
        val groupCursor = migratedDb.query(
            "SELECT * FROM ai_image_groups WHERE id = 'group-1'"
        )
        groupCursor.moveToFirst()
        assert(groupCursor.getString(groupCursor.getColumnIndexOrThrow("name")) == "默认分组") {
            "Group name data corrupted after migration"
        }
        groupCursor.close()

        // Migrate 113 -> 114: adds nullable columns and composite index to ai_generated_images
        migratedDb = helper.runMigrationsAndValidate(
            TEST_DB, 114, true,
            getMigrations(113, 114)
        )

        // Verify new nullable columns exist on ai_generated_images
        val imageCursor = migratedDb.query("PRAGMA table_info(ai_generated_images)")
        val imageColumns = mutableListOf<String>()
        while (imageCursor.moveToNext()) {
            imageColumns.add(imageCursor.getString(imageCursor.getColumnIndexOrThrow("name")))
        }
        imageCursor.close()
        assert(imageColumns.contains("genTaskId")) { "ai_generated_images missing 'genTaskId' column" }
        assert(imageColumns.contains("generationMode")) { "ai_generated_images missing 'generationMode' column" }
        assert(imageColumns.contains("inputImageId")) { "ai_generated_images missing 'inputImageId' column" }
        assert(imageColumns.contains("negativePrompt")) { "ai_generated_images missing 'negativePrompt' column" }
        assert(imageColumns.contains("referenceImageId")) { "ai_generated_images missing 'referenceImageId' column" }

        // Verify idx_image_book_chapter index exists on ai_generated_images
        val imageIndexCursor = migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_image_book_chapter'"
        )
        assert(imageIndexCursor.count > 0) { "idx_image_book_chapter index not found" }
        imageIndexCursor.close()

        // Migrate 114 -> 115: adds referenceImageId column to book_characters
        migratedDb = helper.runMigrationsAndValidate(
            TEST_DB, 115, true,
            getMigrations(114, 115)
        )

        // Verify referenceImageId column exists on book_characters
        val charCursor = migratedDb.query("PRAGMA table_info(book_characters)")
        val charColumns = mutableListOf<String>()
        while (charCursor.moveToNext()) {
            charColumns.add(charCursor.getString(charCursor.getColumnIndexOrThrow("name")))
        }
        charCursor.close()
        assert(charColumns.contains("referenceImageId")) { "book_characters missing 'referenceImageId' column" }

        // Verify data integrity after final migration - test data should still be intact
        val finalCursor = migratedDb.query(
            "SELECT * FROM ai_generated_images WHERE id = 'test-img-1'"
        )
        finalCursor.moveToFirst()
        assert(finalCursor.getString(finalCursor.getColumnIndexOrThrow("name")) == "测试图片") {
            "Data corrupted after 113->114 migration"
        }
        // New columns should be NULL for pre-existing rows
        assert(finalCursor.isNull(finalCursor.getColumnIndexOrThrow("genTaskId"))) {
            "genTaskId should be NULL for pre-existing rows"
        }
        finalCursor.close()

        // Migrate 115 -> 116: creates ai_gen_failure_logs and ai_gen_vouchers tables
        migratedDb = helper.runMigrationsAndValidate(
            TEST_DB, 116, true,
            getMigrations(115, 116)
        )

        // Verify new tables exist
        val failureTableCursor = migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='ai_gen_failure_logs'"
        )
        assert(failureTableCursor.count > 0) { "ai_gen_failure_logs table not found" }
        failureTableCursor.close()

        val voucherTableCursor = migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='ai_gen_vouchers'"
        )
        assert(voucherTableCursor.count > 0) { "ai_gen_vouchers table not found" }
        voucherTableCursor.close()

        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate108to109() {
        val db = helper.createDatabase(TEST_DB, 108)
        db.execSQL(
            """
            INSERT INTO ai_generated_images (
                id, name, prompt, providerId, providerName, model, localPath,
                originalSource, bookKey, bookName, bookAuthor, chapterKey,
                chapterIndex, chapterTitle, characterId, characterName,
                sourceType, sourceText, favorite, groupId, createdAt, updatedAt
            ) VALUES (
                'test-1', 'test', 'prompt', 'p1', 'provider', 'm1', '/path',
                'chat', 'bk1', '书名', '作者', 'ck1',
                1, '第一章', NULL, NULL,
                'chat', '', 0, NULL, 1700000000000, 1700000000000
            )
            """.trimIndent()
        )
        db.close()

        helper.runMigrationsAndValidate(
            TEST_DB, 109, true,
            getSingleMigration(108, 109)
        )
    }

    @Test
    @Throws(IOException::class)
    fun migrate109to110() {
        val db = helper.createDatabase(TEST_DB, 109)
        db.close()
        helper.runMigrationsAndValidate(
            TEST_DB, 110, true,
            getSingleMigration(109, 110)
        )
    }

    @Test
    @Throws(IOException::class)
    fun migrate110to111() {
        val db = helper.createDatabase(TEST_DB, 110)
        db.close()
        helper.runMigrationsAndValidate(
            TEST_DB, 111, true,
            getSingleMigration(110, 111)
        )
    }

    @Test
    @Throws(IOException::class)
    fun migrate111to112() {
        val db = helper.createDatabase(TEST_DB, 111)
        db.close()
        helper.runMigrationsAndValidate(
            TEST_DB, 112, true,
            getSingleMigration(111, 112)
        )
    }

    @Test
    @Throws(IOException::class)
    fun migrate112to113() {
        val db = helper.createDatabase(TEST_DB, 112)
        db.close()
        helper.runMigrationsAndValidate(
            TEST_DB, 113, true,
            getSingleMigration(112, 113)
        )
    }

    @Test
    @Throws(IOException::class)
    fun migrate113to114() {
        val db = helper.createDatabase(TEST_DB, 113)
        // Insert test data into ai_generated_images before migration
        db.execSQL(
            """
            INSERT INTO ai_generated_images (
                id, name, prompt, providerId, providerName, model, localPath,
                originalSource, bookKey, bookName, bookAuthor, chapterKey,
                chapterIndex, chapterTitle, characterId, characterName,
                sourceType, sourceText, favorite, groupId, createdAt, updatedAt
            ) VALUES (
                'test-img-2', '测试图片2', 'Another prompt with émojis 🎨',
                'provider-2', 'Provider Two', 'model-v2', '/path/to/image2.png',
                'book', 'bookKey2', '第二本书', '作者二', 'chapterKey2',
                5, '第五章', NULL, NULL,
                'book', 'more text', 1, 'group-1', 1700000000000, 1700000000000
            )
            """.trimIndent()
        )
        db.close()

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB, 114, true,
            getSingleMigration(113, 114)
        )

        // Verify new columns exist
        val cursor = migratedDb.query("PRAGMA table_info(ai_generated_images)")
        val columns = mutableListOf<String>()
        while (cursor.moveToNext()) {
            columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
        cursor.close()
        assert(columns.contains("genTaskId")) { "Missing genTaskId column after migration" }
        assert(columns.contains("generationMode")) { "Missing generationMode column after migration" }
        assert(columns.contains("inputImageId")) { "Missing inputImageId column after migration" }
        assert(columns.contains("negativePrompt")) { "Missing negativePrompt column after migration" }
        assert(columns.contains("referenceImageId")) { "Missing referenceImageId column after migration" }

        // Verify data integrity - existing data should be preserved
        val dataCursor = migratedDb.query(
            "SELECT * FROM ai_generated_images WHERE id = 'test-img-2'"
        )
        dataCursor.moveToFirst()
        assert(dataCursor.getString(dataCursor.getColumnIndexOrThrow("name")) == "测试图片2") {
            "Data integrity check failed: name"
        }
        assert(dataCursor.getString(dataCursor.getColumnIndexOrThrow("prompt")) == "Another prompt with émojis 🎨") {
            "Data integrity check failed: prompt with special characters"
        }
        assert(dataCursor.getString(dataCursor.getColumnIndexOrThrow("bookName")) == "第二本书") {
            "Data integrity check failed: bookName"
        }
        // New columns should be NULL for existing rows
        assert(dataCursor.isNull(dataCursor.getColumnIndexOrThrow("genTaskId"))) {
            "genTaskId should be NULL for existing rows"
        }
        assert(dataCursor.isNull(dataCursor.getColumnIndexOrThrow("generationMode"))) {
            "generationMode should be NULL for existing rows"
        }
        dataCursor.close()

        // Verify composite index
        val indexCursor = migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_image_book_chapter'"
        )
        assert(indexCursor.count > 0) { "idx_image_book_chapter index not found after migration" }
        indexCursor.close()

        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate114to115() {
        val db = helper.createDatabase(TEST_DB, 114)
        // Insert test data into book_characters before migration
        db.execSQL(
            """
            INSERT INTO book_characters (
                id, bookUrl, name, avatar, gender, identity, skills, attributes,
                appearance, personality, biography, speechRouteJson, autoCreated,
                source, lastDetectedAt, roleLevel, sortOrder, createdAt, updatedAt
            ) VALUES (
                1, 'book://test/1', '测试角色', '', 'male', '主角', '剑法',
                '勇敢', '高大', '正义', '英雄出身', '', 0,
                'manual', 1700000000000, 2, 0, 1700000000000, 1700000000000
            )
            """.trimIndent()
        )
        db.close()

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB, 115, true,
            getSingleMigration(114, 115)
        )

        // Verify new column exists on book_characters
        val cursor = migratedDb.query("PRAGMA table_info(book_characters)")
        val columns = mutableListOf<String>()
        while (cursor.moveToNext()) {
            columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
        cursor.close()
        assert(columns.contains("referenceImageId")) { "book_characters missing 'referenceImageId' column after migration" }

        // Verify data integrity - existing data should be preserved
        val dataCursor = migratedDb.query(
            "SELECT * FROM book_characters WHERE id = 1"
        )
        dataCursor.moveToFirst()
        assert(dataCursor.getString(dataCursor.getColumnIndexOrThrow("name")) == "测试角色") {
            "Data integrity check failed: name"
        }
        // New column should be empty string (DEFAULT '') for existing rows
        assert(dataCursor.getString(dataCursor.getColumnIndexOrThrow("referenceImageId")) == "") {
            "referenceImageId should be empty string for existing rows"
        }
        dataCursor.close()

        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate115to116() {
        val db = helper.createDatabase(TEST_DB, 115)
        db.close()

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB, 116, true,
            getSingleMigration(115, 116)
        )

        // Verify ai_gen_failure_logs table exists
        val failureCursor = migratedDb.query("PRAGMA table_info(ai_gen_failure_logs)")
        val failureColumns = mutableListOf<String>()
        while (failureCursor.moveToNext()) {
            failureColumns.add(failureCursor.getString(failureCursor.getColumnIndexOrThrow("name")))
        }
        failureCursor.close()
        assert(failureColumns.contains("modality")) { "ai_gen_failure_logs missing 'modality' column" }
        assert(failureColumns.contains("errorMessage")) { "ai_gen_failure_logs missing 'errorMessage' column" }
        assert(failureColumns.contains("errorType")) { "ai_gen_failure_logs missing 'errorType' column" }

        // Verify ai_gen_vouchers table exists
        val voucherCursor = migratedDb.query("PRAGMA table_info(ai_gen_vouchers)")
        val voucherColumns = mutableListOf<String>()
        while (voucherCursor.moveToNext()) {
            voucherColumns.add(voucherCursor.getString(voucherCursor.getColumnIndexOrThrow("name")))
        }
        voucherCursor.close()
        assert(voucherColumns.contains("costEstimate")) { "ai_gen_vouchers missing 'costEstimate' column" }
        assert(voucherColumns.contains("costActual")) { "ai_gen_vouchers missing 'costActual' column" }
        assert(voucherColumns.contains("taskId")) { "ai_gen_vouchers missing 'taskId' column" }

        // Verify indices
        val indexCursor = migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name IN ('idx_failure_modality', 'idx_failure_provider', 'idx_voucher_task', 'idx_voucher_created')"
        )
        assert(indexCursor.count == 4) { "Expected 4 indices for failure_logs and vouchers, got ${indexCursor.count}" }
        indexCursor.close()

        migratedDb.close()
    }
}
