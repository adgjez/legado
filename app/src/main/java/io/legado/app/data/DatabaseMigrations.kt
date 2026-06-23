package io.legado.app.data

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType

object DatabaseMigrations {

    val migrations: Array<Migration> by lazy {
        arrayOf(
            migration_10_11, migration_11_12, migration_12_13, migration_13_14,
            migration_14_15, migration_15_17, migration_17_18, migration_18_19,
            migration_19_20, migration_20_21, migration_21_22, migration_22_23,
            migration_23_24, migration_24_25, migration_25_26, migration_26_27,
            migration_27_28, migration_28_29, migration_29_30, migration_30_31,
            migration_31_32, migration_32_33, migration_33_34, migration_34_35,
            migration_35_36, migration_36_37, migration_37_38, migration_38_39,
            migration_39_40, migration_40_41, migration_41_42, migration_42_43,
            migration_90_91, migration_91_92, migration_93_94, migration_94_95,
            migration_95_96, migration_96_97, migration_97_98, migration_98_99,
            migration_99_100, migration_100_101, migration_101_102,
        )
    }

    private val migration_97_98 = object : Migration(97, 98) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `ai_generated_images` ADD COLUMN `bookKey` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `ai_generated_images` ADD COLUMN `bookName` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `ai_generated_images` ADD COLUMN `bookAuthor` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `ai_generated_images` ADD COLUMN `chapterKey` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `ai_generated_images` ADD COLUMN `chapterIndex` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE `ai_generated_images` ADD COLUMN `chapterTitle` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `ai_generated_images` ADD COLUMN `characterId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `ai_generated_images` ADD COLUMN `characterName` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `ai_generated_images` ADD COLUMN `sourceType` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `ai_generated_images` ADD COLUMN `sourceText` TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_images_bookKey` ON `ai_generated_images` (`bookKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_images_chapterKey` ON `ai_generated_images` (`chapterKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_images_characterId` ON `ai_generated_images` (`characterId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_images_sourceType` ON `ai_generated_images` (`sourceType`)")
        }
    }

    private val migration_96_97 = object : Migration(96, 97) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_characters` ADD COLUMN `roleLevel` INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val migration_95_96 = object : Migration(95, 96) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `book_characters` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookUrl` TEXT NOT NULL DEFAULT '',
                    `name` TEXT NOT NULL DEFAULT '',
                    `avatar` TEXT NOT NULL DEFAULT '',
                    `identity` TEXT NOT NULL DEFAULT '',
                    `skills` TEXT NOT NULL DEFAULT '',
                    `attributes` TEXT NOT NULL DEFAULT '',
                    `appearance` TEXT NOT NULL DEFAULT '',
                    `personality` TEXT NOT NULL DEFAULT '',
                    `biography` TEXT NOT NULL DEFAULT '',
                    `sortOrder` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0
                )
                """
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_characters_bookUrl` ON `book_characters` (`bookUrl`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_book_characters_bookUrl_name` ON `book_characters` (`bookUrl`, `name`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `book_character_relations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookUrl` TEXT NOT NULL DEFAULT '',
                    `fromCharacterId` INTEGER NOT NULL DEFAULT 0,
                    `toCharacterId` INTEGER NOT NULL DEFAULT 0,
                    `relationName` TEXT NOT NULL DEFAULT '',
                    `relationType` TEXT NOT NULL DEFAULT '',
                    `description` TEXT NOT NULL DEFAULT '',
                    `strength` INTEGER NOT NULL DEFAULT 50,
                    `sortOrder` INTEGER NOT NULL DEFAULT 0,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(`fromCharacterId`) REFERENCES `book_characters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`toCharacterId`) REFERENCES `book_characters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_character_relations_bookUrl` ON `book_character_relations` (`bookUrl`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_character_relations_fromCharacterId` ON `book_character_relations` (`fromCharacterId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_character_relations_toCharacterId` ON `book_character_relations` (`toCharacterId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_book_character_relations_bookUrl_fromCharacterId_toCharacterId_relationName` ON `book_character_relations` (`bookUrl`, `fromCharacterId`, `toCharacterId`, `relationName`)")
        }
    }

    private val migration_94_95 = object : Migration(94, 95) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_image_groups` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `sortOrder` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_generated_images` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `prompt` TEXT NOT NULL,
                    `providerId` TEXT NOT NULL,
                    `providerName` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `localPath` TEXT NOT NULL,
                    `originalSource` TEXT NOT NULL,
                    `favorite` INTEGER NOT NULL,
                    `groupId` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_images_groupId` ON `ai_generated_images` (`groupId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_images_favorite` ON `ai_generated_images` (`favorite`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_images_createdAt` ON `ai_generated_images` (`createdAt`)")
        }
    }

    private val migration_93_94 = object : Migration(93, 94) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `read_menu_custom_buttons` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL DEFAULT '',
                    `iconPath` TEXT NOT NULL DEFAULT '',
                    `jsLib` TEXT NOT NULL DEFAULT '',
                    `loginUrl` TEXT NOT NULL DEFAULT '',
                    `loginUi` TEXT NOT NULL DEFAULT '',
                    `enabledCookieJar` INTEGER NOT NULL DEFAULT 0,
                    `script` TEXT NOT NULL DEFAULT '',
                    `timeoutMillisecond` INTEGER NOT NULL DEFAULT 3000,
                    `sortOrder` INTEGER NOT NULL DEFAULT 0,
                    `updateTime` INTEGER NOT NULL DEFAULT 0
                )
                """
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_read_menu_custom_buttons_id` ON `read_menu_custom_buttons` (`id`)")
        }
    }

    private val migration_91_92 = object : Migration(91, 92) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `paragraph_rules` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL DEFAULT '',
                    `jsLib` TEXT NOT NULL DEFAULT '',
                    `loginUrl` TEXT NOT NULL DEFAULT '',
                    `loginUi` TEXT NOT NULL DEFAULT '',
                    `script` TEXT NOT NULL DEFAULT '',
                    `timeoutMillisecond` INTEGER NOT NULL DEFAULT 3000,
                    `sortOrder` INTEGER NOT NULL DEFAULT 0,
                    `updateTime` INTEGER NOT NULL DEFAULT 0
                )
                """
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_paragraph_rules_id` ON `paragraph_rules` (`id`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `book_paragraph_rules` (
                    `bookUrl` TEXT NOT NULL,
                    `ruleId` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL DEFAULT 1,
                    `sortOrder` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`bookUrl`, `ruleId`),
                    FOREIGN KEY(`ruleId`) REFERENCES `paragraph_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_paragraph_rules_bookUrl` ON `book_paragraph_rules` (`bookUrl`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_paragraph_rules_ruleId` ON `book_paragraph_rules` (`ruleId`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `paragraph_rule_vars` (
                    `ruleId` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    PRIMARY KEY(`ruleId`, `name`),
                    FOREIGN KEY(`ruleId`) REFERENCES `paragraph_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_paragraph_rule_vars_ruleId` ON `paragraph_rule_vars` (`ruleId`)")
        }
    }
    private val migration_90_91 = object : Migration(90, 91) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `readRecentBooks` (
                    `bookUrl` TEXT NOT NULL,
                    `lastRead` INTEGER NOT NULL,
                    PRIMARY KEY(`bookUrl`)
                )
                """
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO `readRecentBooks` (`bookUrl`, `lastRead`)
                SELECT `bookUrl`, `durChapterTime` FROM `books`
                WHERE `durChapterTime` > 0
                  AND (
                    `durChapterIndex` > 0
                    OR `durChapterPos` > 0
                    OR (`durChapterTitle` IS NOT NULL AND `durChapterTitle` != '')
                  )
                """
            )
        }
    }

    private val migration_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE txtTocRules")
            db.execSQL(
                """CREATE TABLE txtTocRules(id INTEGER NOT NULL, 
                    name TEXT NOT NULL, rule TEXT NOT NULL, serialNumber INTEGER NOT NULL, 
                    enable INTEGER NOT NULL, PRIMARY KEY (id))"""
            )
        }
    }

    private val migration_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rssSources ADD style TEXT ")
        }
    }

    private val migration_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rssSources ADD articleStyle INTEGER NOT NULL DEFAULT 0 ")
        }
    }

    private val migration_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `books_new` (`bookUrl` TEXT NOT NULL, `tocUrl` TEXT NOT NULL, `origin` TEXT NOT NULL,
                    `originName` TEXT NOT NULL, `name` TEXT NOT NULL, `author` TEXT NOT NULL, `kind` TEXT, `customTag` TEXT, `coverUrl` TEXT, 
                    `customCoverUrl` TEXT, `intro` TEXT, `customIntro` TEXT, `charset` TEXT, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, 
                    `latestChapterTitle` TEXT, `latestChapterTime` INTEGER NOT NULL, `lastCheckTime` INTEGER NOT NULL, `lastCheckCount` INTEGER NOT NULL, 
                    `totalChapterNum` INTEGER NOT NULL, `durChapterTitle` TEXT, `durChapterIndex` INTEGER NOT NULL, `durChapterPos` INTEGER NOT NULL, 
                    `durChapterTime` INTEGER NOT NULL, `wordCount` TEXT, `canUpdate` INTEGER NOT NULL, `order` INTEGER NOT NULL, 
                    `originOrder` INTEGER NOT NULL, `useReplaceRule` INTEGER NOT NULL, `variable` TEXT, PRIMARY KEY(`bookUrl`))"""
            )
            db.execSQL("INSERT INTO books_new select * from books ")
            db.execSQL("DROP TABLE books")
            db.execSQL("ALTER TABLE books_new RENAME TO books")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_name_author` ON `books` (`name`, `author`) ")
        }
    }

    private val migration_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE bookmarks ADD bookAuthor TEXT NOT NULL DEFAULT ''")
        }
    }

    private val migration_15_17 = object : Migration(15, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `readRecord` (`bookName` TEXT NOT NULL, `readTime` INTEGER NOT NULL, PRIMARY KEY(`bookName`))")
        }
    }

    private val migration_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `httpTTS` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, PRIMARY KEY(`id`))")
        }
    }

    private val migration_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `readRecordNew` (`androidId` TEXT NOT NULL, `bookName` TEXT NOT NULL, `readTime` INTEGER NOT NULL, 
                    PRIMARY KEY(`androidId`, `bookName`))"""
            )
            db.execSQL("INSERT INTO readRecordNew(androidId, bookName, readTime) select '${AppConst.androidId}' as androidId, bookName, readTime from readRecord")
            db.execSQL("DROP TABLE readRecord")
            db.execSQL("ALTER TABLE readRecordNew RENAME TO readRecord")
        }
    }
    private val migration_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE book_sources ADD bookSourceComment TEXT")
        }
    }

    private val migration_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE book_groups ADD show INTEGER NOT NULL DEFAULT 1")
        }
    }

    private val migration_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `books_new` (`bookUrl` TEXT NOT NULL, `tocUrl` TEXT NOT NULL, `origin` TEXT NOT NULL, 
                    `originName` TEXT NOT NULL, `name` TEXT NOT NULL, `author` TEXT NOT NULL, `kind` TEXT, `customTag` TEXT, 
                    `coverUrl` TEXT, `customCoverUrl` TEXT, `intro` TEXT, `customIntro` TEXT, `charset` TEXT, `type` INTEGER NOT NULL, 
                    `group` INTEGER NOT NULL, `latestChapterTitle` TEXT, `latestChapterTime` INTEGER NOT NULL, `lastCheckTime` INTEGER NOT NULL, 
                    `lastCheckCount` INTEGER NOT NULL, `totalChapterNum` INTEGER NOT NULL, `durChapterTitle` TEXT, `durChapterIndex` INTEGER NOT NULL, 
                    `durChapterPos` INTEGER NOT NULL, `durChapterTime` INTEGER NOT NULL, `wordCount` TEXT, `canUpdate` INTEGER NOT NULL, 
                    `order` INTEGER NOT NULL, `originOrder` INTEGER NOT NULL, `variable` TEXT, `readConfig` TEXT, PRIMARY KEY(`bookUrl`))"""
            )
            db.execSQL(
                """INSERT INTO books_new select `bookUrl`, `tocUrl`, `origin`, `originName`, `name`, `author`, `kind`, `customTag`, `coverUrl`, 
                    `customCoverUrl`, `intro`, `customIntro`, `charset`, `type`, `group`, `latestChapterTitle`, `latestChapterTime`, `lastCheckTime`, 
                    `lastCheckCount`, `totalChapterNum`, `durChapterTitle`, `durChapterIndex`, `durChapterPos`, `durChapterTime`, `wordCount`, `canUpdate`, 
                    `order`, `originOrder`, `variable`, null
                    from books"""
            )
            db.execSQL("DROP TABLE books")
            db.execSQL("ALTER TABLE books_new RENAME TO books")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_name_author` ON `books` (`name`, `author`) ")
        }
    }

    private val migration_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chapters ADD baseUrl TEXT NOT NULL DEFAULT ''")
        }
    }

    private val migration_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `caches` (`key` TEXT NOT NULL, `value` TEXT, `deadline` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_caches_key` ON `caches` (`key`)")
        }
    }

    private val migration_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `sourceSubs` 
                    (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `type` INTEGER NOT NULL, `customOrder` INTEGER NOT NULL, 
                    PRIMARY KEY(`id`))"""
            )
        }
    }

    private val migration_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `ruleSubs` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `type` INTEGER NOT NULL, 
                    `customOrder` INTEGER NOT NULL, `autoUpdate` INTEGER NOT NULL, `update` INTEGER NOT NULL, PRIMARY KEY(`id`))"""
            )
            db.execSQL(" insert into `ruleSubs` select *, 0, 0 from `sourceSubs` ")
            db.execSQL("DROP TABLE `sourceSubs`")
        }
    }

    private val migration_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(" ALTER TABLE rssSources ADD singleUrl INTEGER NOT NULL DEFAULT 0 ")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `bookmarks1` (`time` INTEGER NOT NULL, `bookUrl` TEXT NOT NULL, `bookName` TEXT NOT NULL, 
                        `bookAuthor` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `chapterPos` INTEGER NOT NULL, `chapterName` TEXT NOT NULL, 
                        `bookText` TEXT NOT NULL, `content` TEXT NOT NULL, PRIMARY KEY(`time`))"""
            )
            db.execSQL(
                """insert into `bookmarks1` 
                        select `time`, `bookUrl`, `bookName`, `bookAuthor`, `chapterIndex`, `pageIndex`, `chapterName`, '', `content` 
                        from bookmarks"""
            )
            db.execSQL(" DROP TABLE `bookmarks` ")
            db.execSQL(" ALTER TABLE bookmarks1 RENAME TO bookmarks ")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_time` ON `bookmarks` (`time`)")
        }
    }

    private val migration_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rssArticles ADD variable TEXT")
            db.execSQL("ALTER TABLE rssStars ADD variable TEXT")
        }
    }

    private val migration_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rssSources ADD sourceComment TEXT")
        }
    }

    private val migration_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chapters ADD `startFragmentId` TEXT")
            db.execSQL("ALTER TABLE chapters ADD `endFragmentId` TEXT")
            db.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS `epubChapters` 
                    (`bookUrl` TEXT NOT NULL, `href` TEXT NOT NULL, `parentHref` TEXT, 
                    PRIMARY KEY(`bookUrl`, `href`), FOREIGN KEY(`bookUrl`) REFERENCES `books`(`bookUrl`) ON UPDATE NO ACTION ON DELETE CASCADE )
                """
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_epubChapters_bookUrl` ON `epubChapters` (`bookUrl`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_epubChapters_bookUrl_href` ON `epubChapters` (`bookUrl`, `href`)")
        }
    }

    private val migration_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE readRecord RENAME TO readRecord1")
            db.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS `readRecord` (`deviceId` TEXT NOT NULL, `bookName` TEXT NOT NULL, `readTime` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `bookName`))
                """
            )
            db.execSQL("insert into readRecord (deviceId, bookName, readTime) select androidId, bookName, readTime from readRecord1")
        }
    }

    private val migration_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE `epubChapters`")
        }
    }

    private val migration_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE bookmarks RENAME TO bookmarks_old")
            db.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS `bookmarks` (`time` INTEGER NOT NULL,
                    `bookName` TEXT NOT NULL, `bookAuthor` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, 
                    `chapterPos` INTEGER NOT NULL, `chapterName` TEXT NOT NULL, `bookText` TEXT NOT NULL, 
                    `content` TEXT NOT NULL, PRIMARY KEY(`time`))
                """
            )
            db.execSQL(
                """
                    CREATE INDEX IF NOT EXISTS `index_bookmarks_bookName_bookAuthor` ON `bookmarks` (`bookName`, `bookAuthor`)
                """
            )
            db.execSQL(
                """
                    insert into bookmarks (time, bookName, bookAuthor, chapterIndex, chapterPos, chapterName, bookText, content)
                    select time, ifNull(b.name, bookName) bookName, ifNull(b.author, bookAuthor) bookAuthor, 
                    chapterIndex, chapterPos, chapterName, bookText, content from bookmarks_old o
                    left join books b on o.bookUrl = b.bookUrl
                """
            )
        }
    }

    private val migration_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_groups` ADD `cover` TEXT")
        }
    }

    private val migration_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_sources` ADD `concurrentRate` TEXT")
        }
    }

    private val migration_35_36 = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_sources` ADD `loginUi` TEXT")
            db.execSQL("ALTER TABLE `book_sources` ADD`loginCheckJs` TEXT")
        }
    }

    private val migration_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `rssSources` ADD `loginUrl` TEXT")
            db.execSQL("ALTER TABLE `rssSources` ADD `loginUi` TEXT")
            db.execSQL("ALTER TABLE `rssSources` ADD `loginCheckJs` TEXT")
        }
    }

    private val migration_37_38 = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_sources` ADD `respondTime` INTEGER NOT NULL DEFAULT 180000")
        }
    }

    private val migration_38_39 = object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `rssSources` ADD `concurrentRate` TEXT")
        }
    }

    private val migration_39_40 = object : Migration(39, 40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chapters` ADD `isVip` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `chapters` ADD `isPay` INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val migration_40_41 = object : Migration(40, 41) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `httpTTS` ADD `loginUrl` TEXT")
            db.execSQL("ALTER TABLE `httpTTS` ADD `loginUi` TEXT")
            db.execSQL("ALTER TABLE `httpTTS` ADD `loginCheckJs` TEXT")
            db.execSQL("ALTER TABLE `httpTTS` ADD `header` TEXT")
            db.execSQL("ALTER TABLE `httpTTS` ADD `concurrentRate` TEXT")
        }
    }

    private val migration_41_42 = object : Migration(41, 42) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE 'httpTTS' ADD `contentType` TEXT")
        }
    }

    private val migration_42_43 = object : Migration(42, 43) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chapters` ADD `isVolume` INTEGER NOT NULL DEFAULT 0")
        }
    }


    @Suppress("ClassName")
    class Migration_54_55 : AutoMigrationSpec {

        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                update books set type = ${BookType.audio}
                where type = ${BookSourceType.audio}
            """.trimIndent()
            )
            db.execSQL(
                """
                update books set type = ${BookType.image}
                where type = ${BookSourceType.image}
            """.trimIndent()
            )
            db.execSQL(
                """
                update books set type = ${BookType.webFile}
                where type = ${BookSourceType.file}
            """.trimIndent()
            )
            db.execSQL(
                """
                update books set type = ${BookType.text}
                where type = ${BookSourceType.default}
            """.trimIndent()
            )
            db.execSQL(
                """
                update books set type = type | ${BookType.local}
                where origin like '${BookType.localTag}%' or origin like '${BookType.webDavTag}%'
            """.trimIndent()
            )
        }

    }


    @Suppress("ClassName")
    @DeleteColumn(
        tableName = "book_sources",
        columnName = "enabledReview"
    )
    class Migration_64_65 : AutoMigrationSpec

    @Suppress("ClassName")
    class Migration_80_81 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
            CREATE TABLE rssArticles_new (
                origin TEXT NOT NULL DEFAULT '',
                sort TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                `order` INTEGER NOT NULL DEFAULT 0,
                link TEXT NOT NULL DEFAULT '',
                pubDate TEXT,
                description TEXT,
                content TEXT,
                image TEXT,
                `group` TEXT NOT NULL DEFAULT '默认分组',
                read INTEGER NOT NULL DEFAULT 0,
                variable TEXT,
                PRIMARY KEY (origin, link, sort)
            )
        """.trimIndent())
            db.execSQL("""
            INSERT INTO rssArticles_new (origin, sort, title, `order`, link, pubDate, description, content, image, `group`, read, variable)
            SELECT origin, sort, title, `order`, link, pubDate, description, content, image, `group`, read, variable FROM rssArticles
        """.trimIndent())
            db.execSQL("DROP TABLE rssArticles")
            db.execSQL("ALTER TABLE rssArticles_new RENAME TO rssArticles")
        }
    }

    @Suppress("ClassName")
    @DeleteColumn(
        tableName = "rssArticles",
        columnName = "ratio"
    )
    class Migration_83_84 : AutoMigrationSpec

    @Suppress("ClassName")
    @DeleteColumn(
        tableName = "chapters",
        columnName = "lyric"
    )
    @DeleteColumn(
        tableName = "chapters",
        columnName = "reviewImg"
    )
    class Migration_84_85 : AutoMigrationSpec

    private val migration_98_99 = object : Migration(98, 99) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_video_groups` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `order` INTEGER NOT NULL DEFAULT 0,
                    `cover` TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_generated_videos` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `prompt` TEXT NOT NULL,
                    `negativePrompt` TEXT NOT NULL DEFAULT '',
                    `providerId` TEXT NOT NULL,
                    `providerName` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `localPath` TEXT NOT NULL DEFAULT '',
                    `remoteUrl` TEXT NOT NULL DEFAULT '',
                    `coverPath` TEXT NOT NULL DEFAULT '',
                    `durationMs` INTEGER NOT NULL DEFAULT 0,
                    `width` INTEGER NOT NULL DEFAULT 0,
                    `height` INTEGER NOT NULL DEFAULT 0,
                    `sizeBytes` INTEGER NOT NULL DEFAULT 0,
                    `aspectRatio` TEXT NOT NULL DEFAULT '',
                    `seed` INTEGER NOT NULL DEFAULT -1,
                    `bookKey` TEXT NOT NULL DEFAULT '',
                    `bookName` TEXT NOT NULL DEFAULT '',
                    `bookAuthor` TEXT NOT NULL DEFAULT '',
                    `chapterKey` TEXT NOT NULL DEFAULT '',
                    `chapterIndex` INTEGER NOT NULL DEFAULT -1,
                    `chapterTitle` TEXT NOT NULL DEFAULT '',
                    `characterId` INTEGER NOT NULL DEFAULT 0,
                    `characterName` TEXT NOT NULL DEFAULT '',
                    `sourceType` TEXT NOT NULL DEFAULT '',
                    `sourceText` TEXT NOT NULL DEFAULT '',
                    `status` TEXT NOT NULL DEFAULT 'pending',
                    `failReason` TEXT NOT NULL DEFAULT '',
                    `progress` INTEGER NOT NULL DEFAULT 0,
                    `externalTaskId` TEXT NOT NULL DEFAULT '',
                    `metadataJson` TEXT NOT NULL DEFAULT '',
                    `favorite` INTEGER NOT NULL DEFAULT 0,
                    `groupId` TEXT DEFAULT NULL,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0,
                    `completedAt` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_videos_groupId` ON `ai_generated_videos` (`groupId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_videos_favorite` ON `ai_generated_videos` (`favorite`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_videos_status` ON `ai_generated_videos` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_videos_createdAt` ON `ai_generated_videos` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_videos_bookKey` ON `ai_generated_videos` (`bookKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_videos_chapterKey` ON `ai_generated_videos` (`chapterKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_videos_characterId` ON `ai_generated_videos` (`characterId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_generated_videos_sourceType` ON `ai_generated_videos` (`sourceType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_video_groups_order` ON `ai_video_groups` (`order`)")
            db.execSQL(
                "INSERT OR IGNORE INTO `ai_video_groups` (`id`, `name`, `order`, `cover`) " +
                    "VALUES ('default', '默认分组', 0, '')"
            )
        }
    }

    /**
     * P3：从 99 升到 100。创建 [io.legado.app.data.entities.AiVideoAnalysis] 表。
     *
     * 原先使用 AutoMigration(99, 100)，但 v99/v100 的 schema JSON identityHash 全为零
     *（手工编辑导致），AutoMigration 运行时 schema 校验失败直接崩溃。
     * 改用手写 migration 彻底解决。
     */
    private val migration_99_100 = object : Migration(99, 100) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_video_analysis` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookId` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `language` TEXT NOT NULL,
                    `payloadJson` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `providerId` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `failReason` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `idx_book_kind_lang` " +
                    "ON `ai_video_analysis` (`bookId`, `kind`, `language`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_book` " +
                    "ON `ai_video_analysis` (`bookId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_status` " +
                    "ON `ai_video_analysis` (`status`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_updatedAt` " +
                    "ON `ai_video_analysis` (`updatedAt`)"
            )
        }
    }

    /**
     * P3 修复：从 100 升到 101。重建 [io.legado.app.data.entities.AiVideoAnalysis] 表，
     * 移除手写 migration_99_100（已被 [androidx.room.AutoMigration] 替代）误加的列级
     * DEFAULT 约束，与 Room 编译生成的 101.json 保持一致。
     *
     * 历史原因：手写 migration_99_100 在 CREATE TABLE 时给所有 TEXT/INTEGER 列加了
     * DEFAULT ''/0，但 [io.legado.app.data.entities.AiVideoAnalysis] 实体类本身没有
     * 默认值。Room 升级到 101 后 onValidateSchema 对比 101.json（列无 DEFAULT）与 db 中
     * 真实表（带 DEFAULT）发现 defaultValue 不匹配会抛 "Migration didn't properly handle"。
     * AutoMigration 不支持改 defaultValue，故保留此手写 migration。
     */
    private val migration_100_101 = object : Migration(100, 101) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1) 备份旧表（保留可能存在的 v100 阶段数据）
            db.execSQL("ALTER TABLE `ai_video_analysis` RENAME TO `_ai_video_analysis_v100_backup`")
            // 2) 按 101.json 定义创建新表（无列级 DEFAULT）
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_video_analysis` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bookId` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `language` TEXT NOT NULL,
                    `payloadJson` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `providerId` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `failReason` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            // 3) 复制旧表数据；IFNULL 防御极端情况下 v100 数据出现 NULL
            db.execSQL(
                """
                INSERT OR IGNORE INTO `ai_video_analysis`
                    (`id`, `bookId`, `kind`, `language`, `payloadJson`, `model`,
                     `providerId`, `status`, `failReason`, `createdAt`, `updatedAt`)
                SELECT
                    `id`, IFNULL(`bookId`, ''), IFNULL(`kind`, ''), IFNULL(`language`, ''),
                    IFNULL(`payloadJson`, ''), IFNULL(`model`, ''), IFNULL(`providerId`, ''),
                    IFNULL(`status`, 'pending'), IFNULL(`failReason`, ''),
                    IFNULL(`createdAt`, 0), IFNULL(`updatedAt`, 0)
                FROM `_ai_video_analysis_v100_backup`
                """.trimIndent()
            )
            // 4) 删除备份
            db.execSQL("DROP TABLE IF EXISTS `_ai_video_analysis_v100_backup`")
            // 5) 重建索引
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `idx_book_kind_lang` " +
                    "ON `ai_video_analysis` (`bookId`, `kind`, `language`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_book` " +
                    "ON `ai_video_analysis` (`bookId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_status` " +
                    "ON `ai_video_analysis` (`status`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_updatedAt` " +
                    "ON `ai_video_analysis` (`updatedAt`)"
            )
        }
    }

    /**
     * 修复 migration_97_98 / migration_98_99 中 CREATE TABLE / ALTER TABLE
     * 给 NOT NULL 列加了 DEFAULT 约束，但实体类 [AiGeneratedImage]、
     * [AiGeneratedVideo]、[AiVideoGroup] 未声明 @ColumnInfo(defaultValue=...)，
     * 导致 Room onValidateSchema 比对 101.json（无 defaultValue）与 db 真实表
     * （有 DEFAULT）时抛 "Migration didn't properly handle: ... Expected ... found ...".
     *
     * 修复方式：在实体类上补齐 @ColumnInfo(defaultValue=...) 使 102.json 的
     * defaultValue 与 db 中已存在的 DEFAULT 约束一致。此 migration 本身无需
     * 执行任何 SQL——DEFAULT 约束在 v97/v98 阶段已写入 db，此处仅触发版本号
     * 升级让 Room 用新的 102.json 重新校验 schema。
     */
    private val migration_101_102 = object : Migration(101, 102) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No-op: DEFAULT 约束已由 migration_97_98 / migration_98_99 写入 db，
            // 此处仅升级版本号让 Room 用 102.json 校验 schema。
        }
    }

}
