package io.legado.app.help.ai

import android.app.Application
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.dao.NovelVideoDao
import io.legado.app.data.entities.NovelVideoCompilation
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.utils.GSON
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * [NovelVideoCompiler] 单元测试。子项目 E。
 *
 * 用 in-memory Room + 注入 [NovelVideoCompiler.MediaMergeOutcome] stub，
 * 覆盖 spec §9.1 的 9 项用例（校验链 / 排序 / 持久化 / 清理）。
 *
 * 真实 mp4 合并由 [VideoMuxer] 已验证的生产路径负责，此处用 stub 隔离 MediaMuxer
 * （Robolectric 下 MediaExtractor/MediaMuxer 无法产出真实可解析的 mp4）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class NovelVideoCompilerTest {

    private lateinit var db: TestCompilationDatabase
    private lateinit var dao: NovelVideoDao
    private lateinit var tempDir: File

    @Before
    fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, TestCompilationDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.novelVideoDao
        tempDir = File(System.getProperty("java.io.tmpdir"), "nvc_test_${System.nanoTime()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    /** 创建一个 COMPLETED job，其 outputPath 指向 tempDir 下的真实（占位）文件。 */
    private suspend fun completedJob(
        id: String,
        bookUrl: String = "book_1",
        bookName: String = "测试书",
        chapterStart: Int,
        chapterEnd: Int,
        durationMs: Long = 5000L
    ): NovelVideoJob {
        val mp4 = File(tempDir, "$id.mp4")
        mp4.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70)) // ftyp 占位
        val job = NovelVideoJob(
            id = id,
            bookUrl = bookUrl,
            bookName = bookName,
            chapterStartIndex = chapterStart,
            chapterEndIndex = chapterEnd,
            status = NovelVideoJobStatus.COMPLETED,
            outputPath = mp4.absolutePath,
            totalDurationMs = durationMs,
            createdAt = chapterStart * 1000L
        )
        dao.insertJob(job)
        return job
    }

    /** 始终成功的 mediaMerger stub：产出 outputPath 文件 + 固定 duration/segmentCount。 */
    private suspend fun successMerger(
        inputPaths: List<String>,
        outputPath: String
    ): NovelVideoCompiler.MediaMergeOutcome {
        File(outputPath).apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3, 4))
        return NovelVideoCompiler.MediaMergeOutcome.Merged(
            outputPath = outputPath,
            totalDurationMs = inputPaths.size * 5000L,
            segmentCount = inputPaths.size
        )
    }

    // ============================================================
    // 校验链（spec §5.2）
    // ============================================================

    @Test
    fun compileRejectsLessThanTwoJobs() = runTest {
        val r = NovelVideoCompiler.compile(listOf("job_1"), dao = dao, mediaMerger = ::successMerger)
        assertTrue(r is NovelVideoCompiler.CompileResult.Failed)
        assertTrue((r as NovelVideoCompiler.CompileResult.Failed).reason.contains("至少选择 2 个"))
    }

    @Test
    fun compileRejectsNonExistentJob() = runTest {
        val r = NovelVideoCompiler.compile(listOf("ghost", "also_ghost"), dao = dao, mediaMerger = ::successMerger)
        assertTrue(r is NovelVideoCompiler.CompileResult.Failed)
        assertTrue((r as NovelVideoCompiler.CompileResult.Failed).reason.contains("不存在"))
    }

    @Test
    fun compileRejectsNonCompletedJob() = runTest {
        completedJob("job_1", chapterStart = 0, chapterEnd = 1)
        dao.insertJob(NovelVideoJob(
            id = "job_2", bookUrl = "book_1", bookName = "测试书",
            chapterStartIndex = 2, chapterEndIndex = 3,
            status = NovelVideoJobStatus.GENERATING, outputPath = "/tmp/x.mp4"
        ))
        val r = NovelVideoCompiler.compile(listOf("job_1", "job_2"), dao = dao, mediaMerger = ::successMerger)
        assertTrue(r is NovelVideoCompiler.CompileResult.Failed)
        assertTrue((r as NovelVideoCompiler.CompileResult.Failed).reason.contains("尚未完成"))
    }

    @Test
    fun compileRejectsMissingOutputFile() = runTest {
        dao.insertJob(NovelVideoJob(
            id = "job_1", bookUrl = "book_1", bookName = "测试书",
            chapterStartIndex = 0, chapterEndIndex = 1,
            status = NovelVideoJobStatus.COMPLETED,
            outputPath = "/this/path/does/not/exist.mp4"
        ))
        completedJob("job_2", chapterStart = 2, chapterEnd = 3)
        val r = NovelVideoCompiler.compile(listOf("job_1", "job_2"), dao = dao, mediaMerger = ::successMerger)
        assertTrue(r is NovelVideoCompiler.CompileResult.Failed)
        assertTrue((r as NovelVideoCompiler.CompileResult.Failed).reason.contains("合并产物文件缺失"))
    }

    @Test
    fun compileRejectsMixedBooks() = runTest {
        completedJob("job_1", bookUrl = "book_A", bookName = "书A", chapterStart = 0, chapterEnd = 1)
        completedJob("job_2", bookUrl = "book_B", bookName = "书B", chapterStart = 0, chapterEnd = 1)
        val r = NovelVideoCompiler.compile(listOf("job_1", "job_2"), dao = dao, mediaMerger = ::successMerger)
        assertTrue(r is NovelVideoCompiler.CompileResult.Failed)
        val reason = (r as NovelVideoCompiler.CompileResult.Failed).reason
        assertTrue("错误应提及两本书：$reason", reason.contains("书A") && reason.contains("书B") && reason.contains("同一本书"))
    }

    @Test
    fun compileRejectsInconsistentFormatWithJobContext() = runTest {
        completedJob("job_1", bookName = "甲书", chapterStart = 0, chapterEnd = 1)
        completedJob("job_2", bookName = "甲书", chapterStart = 2, chapterEnd = 3)
        val rawErr = "第 2 段分辨率 1280x720 与首段 1920x1080 不一致，无法无损合并"
        val merger: suspend (List<String>, String) -> NovelVideoCompiler.MediaMergeOutcome = { _, _ ->
            NovelVideoCompiler.MediaMergeOutcome.FormatInconsistent(rawErr)
        }
        val r = NovelVideoCompiler.compile(listOf("job_1", "job_2"), dao = dao, mediaMerger = merger)
        assertTrue(r is NovelVideoCompiler.CompileResult.Failed)
        val reason = (r as NovelVideoCompiler.CompileResult.Failed).reason
        assertTrue("应含原始错误：$reason", reason.contains("1280x720"))
        assertTrue("应含 job 章节标识：$reason", reason.contains("第0-1章") && reason.contains("第2-3章"))
    }

    // ============================================================
    // 成功路径
    // ============================================================

    @Test
    fun compileSucceedsAndPersists() = runTest {
        completedJob("job_1", bookName = "成功书", chapterStart = 0, chapterEnd = 1)
        completedJob("job_2", bookName = "成功书", chapterStart = 2, chapterEnd = 3)
        val r = NovelVideoCompiler.compile(listOf("job_1", "job_2"), title = "我的整部视频", dao = dao, mediaMerger = ::successMerger)
        assertTrue(r is NovelVideoCompiler.CompileResult.Success)
        val c = (r as NovelVideoCompiler.CompileResult.Success).compilation
        assertEquals("我的整部视频", c.title)
        assertEquals("book_1", c.bookUrl)
        assertEquals("成功书", c.bookName)
        assertEquals(2, c.segmentCount)
        assertEquals(10000L, c.totalDurationMs)
        assertTrue(File(c.outputPath!!).isFile)
        // 落库可查
        val reloaded = dao.getCompilation(c.id)
        assertNotNull(reloaded)
        assertEquals(c.id, reloaded!!.id)
    }

    @Test
    fun compileOrdersByChapterStartIndex() = runTest {
        completedJob("job_A", bookName = "顺序书", chapterStart = 10, chapterEnd = 12)
        completedJob("job_B", bookName = "顺序书", chapterStart = 0, chapterEnd = 2)
        completedJob("job_C", bookName = "顺序书", chapterStart = 5, chapterEnd = 7)
        // 传入乱序
        val r = NovelVideoCompiler.compile(listOf("job_A", "job_C", "job_B"), dao = dao, mediaMerger = ::successMerger)
        assertTrue(r is NovelVideoCompiler.CompileResult.Success)
        val c = (r as NovelVideoCompiler.CompileResult.Success).compilation
        val ids = GSON.fromJson(c.sourceJobIdsJson, List::class.java)
        assertEquals("应按 chapterStartIndex 升序", listOf("job_B", "job_C", "job_A"), ids)
    }

    @Test
    fun compileCleansUpOnFailure() = runTest {
        completedJob("job_1", chapterStart = 0, chapterEnd = 1)
        completedJob("job_2", chapterStart = 2, chapterEnd = 3)
        val merger: suspend (List<String>, String) -> NovelVideoCompiler.MediaMergeOutcome = { _, _ ->
            NovelVideoCompiler.MediaMergeOutcome.Failed("磁盘满")
        }
        val r = NovelVideoCompiler.compile(listOf("job_1", "job_2"), dao = dao, mediaMerger = merger)
        assertTrue(r is NovelVideoCompiler.CompileResult.Failed)
        // 失败不应落库
        assertEquals(0, dao.getCompilations().size)
        // 失败不应残留产出目录（nvc_* 目录被 finally 清理）
        val compilationsDir = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "novel_video/compilations")
        if (compilationsDir.exists()) {
            val leftover = compilationsDir.listFiles { f -> f.name.startsWith("nvc_") && (f.listFiles()?.isEmpty() ?: true) }
            leftover?.forEach { it.delete() }
            assertEquals("失败不应残留空 nvc_ 目录", 0, leftover?.size ?: 0)
        }
    }

    @Test
    fun compileIsIdempotentNewIdEachTime() = runTest {
        completedJob("job_1", chapterStart = 0, chapterEnd = 1)
        completedJob("job_2", chapterStart = 2, chapterEnd = 3)
        val r1 = NovelVideoCompiler.compile(listOf("job_1", "job_2"), dao = dao, mediaMerger = ::successMerger)
        val r2 = NovelVideoCompiler.compile(listOf("job_1", "job_2"), dao = dao, mediaMerger = ::successMerger)
        assertTrue(r1 is NovelVideoCompiler.CompileResult.Success)
        assertTrue(r2 is NovelVideoCompiler.CompileResult.Success)
        val c1 = (r1 as NovelVideoCompiler.CompileResult.Success).compilation
        val c2 = (r2 as NovelVideoCompiler.CompileResult.Success).compilation
        assertFalse("重复编译应生成新 nvc_id（旧的需要用户手动删）", c1.id == c2.id)
        assertEquals(2, dao.getCompilations().size)
    }
}

@Database(
    entities = [
        NovelVideoJob::class,
        io.legado.app.data.entities.NovelVideoSegment::class,
        io.legado.app.data.entities.NovelVideoCharacterSheet::class,
        NovelVideoCompilation::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TestCompilationDatabase : RoomDatabase() {
    abstract val novelVideoDao: NovelVideoDao
}
