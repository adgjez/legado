package io.legado.app.ui.main.ai.pipeline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.legado.app.ui.theme.LegadoTheme

/**
 * 视频流水线 Activity
 * 
 * 从阅读界面跳转，接收当前章节文本，启动"小说→视频"流水线
 */
class VideoPipelineActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val novelText = intent.getStringExtra(EXTRA_NOVEL_TEXT) ?: ""
        val novelTitle = intent.getStringExtra(EXTRA_NOVEL_TITLE) ?: ""
        val chapterName = intent.getStringExtra(EXTRA_CHAPTER_NAME) ?: ""
        val episode = intent.getIntExtra(EXTRA_EPISODE, 1)

        setContent {
            LegadoTheme {
                VideoPipelineScreen(
                    novelText = novelText,
                    novelTitle = novelTitle,
                    chapterName = chapterName,
                    episode = episode,
                    onDismiss = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_NOVEL_TEXT = "extra_novel_text"
        const val EXTRA_NOVEL_TITLE = "extra_novel_title"
        const val EXTRA_CHAPTER_NAME = "extra_chapter_name"
        const val EXTRA_EPISODE = "extra_episode"
    }
}
