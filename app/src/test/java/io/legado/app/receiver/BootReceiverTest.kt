package io.legado.app.receiver

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * [BootReceiver] / [handleBoot] 的单元测试（P5）。
 *
 * 验证：
 * - ACTION_BOOT_COMPLETED → 启动服务一次
 * - 其它 action（如 MEDIA_BUTTON）→ 不启动服务
 * - null action → 不启动服务
 *
 * 技术：直接调顶层 [handleBoot]，注入 startService lambda 计数，
 * 避开 NovelVideoService.start 的 appCtx 依赖。用 Robolectric 提供 Context。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class BootReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `BOOT_COMPLETED 时启动服务一次`() {
        val startCount = AtomicInteger(0)
        handleBoot(
            action = Intent.ACTION_BOOT_COMPLETED,
            context = context,
            startService = { startCount.incrementAndGet() }
        )
        assertEquals(1, startCount.get())
    }

    @Test
    fun `非 BOOT_COMPLETED action 时不启动服务`() {
        val startCount = AtomicInteger(0)
        handleBoot(
            action = Intent.ACTION_MEDIA_BUTTON,
            context = context,
            startService = { startCount.incrementAndGet() }
        )
        assertEquals(0, startCount.get())
    }

    @Test
    fun `null action 时不启动服务`() {
        val startCount = AtomicInteger(0)
        handleBoot(
            action = null,
            context = context,
            startService = { startCount.incrementAndGet() }
        )
        assertEquals(0, startCount.get())
    }
}
