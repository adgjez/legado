package io.legado.app.ui.book

import android.content.Context
import android.content.Intent
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.VideoPrepareManager
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.toastOnUi

object SearchBookOpenHelper {

    fun open(context: Context, book: SearchBook, isVideo: Boolean) {
        if (isVideo) {
            openVideo(context, book)
            return
        }
        openActivity(context, book, BookInfoActivity::class.java, false)
    }

    private fun openVideo(context: Context, book: SearchBook) {
        val waitDialog = WaitDialog(context).setText("正在准备视频目录")
        Coroutine.async {
            VideoPrepareManager.prepare(this, book)
        }.onStart {
            waitDialog.show()
        }.onSuccess {
            openActivity(context, it.book.toSearchBook(), VideoPlayerActivity::class.java, false)
        }.onError {
            context.toastOnUi("准备视频目录失败，尝试直接打开\n${it.localizedMessage}")
            openActivity(context, book, VideoPlayerActivity::class.java, true)
        }.onFinally {
            waitDialog.dismiss()
        }
    }

    private fun openActivity(
        context: Context,
        book: SearchBook,
        target: Class<*>,
        prepareInPlayer: Boolean
    ) {
        context.startActivity(Intent(context, target).apply {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
            putExtra("origin", book.origin)
            putExtra("originName", book.originName)
            if (target == VideoPlayerActivity::class.java && prepareInPlayer) {
                putExtra(VideoPlayerActivity.EXTRA_PREPARE_BOOK_INFO, true)
            }
        })
    }

    fun isVideoResult(book: SearchBook, sourceTypeHint: Int? = null): Boolean {
        return book.type and BookType.video > 0 ||
                sourceTypeHint == BookSourceType.video ||
                appDb.bookSourceDao.getBookSource(book.origin)?.bookSourceType == BookSourceType.video
    }
}
