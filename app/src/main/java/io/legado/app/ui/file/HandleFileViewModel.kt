package io.legado.app.ui.file

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.help.DirectLinkUpload
import io.legado.app.utils.*

import java.io.File

class HandleFileViewModel(application: Application) : BaseViewModel(application) {

    val errorLiveData = MutableLiveData<String>()

    fun upload(
        fileName: String,
        file: Any,
        contentType: String,
        success: (url: String) -> Unit
    ) {
        execute {
            DirectLinkUpload.upLoad(fileName, file, contentType)
        }.onSuccess {
            success.invoke(it)
        }.onError {
            AppLog.put("上传文件失败\n${it.localizedMessage}", it)
            it.printOnDebug()
            errorLiveData.postValue(it.localizedMessage)
        }
    }

    fun saveToLocal(uri: Uri, fileName: String, data: Any, success: (uri: Uri) -> Unit) {
        execute {
            return@execute if (uri.isContentScheme()) {
                val doc = DocumentFile.fromTreeUri(context, uri)!!
                doc.findFile(fileName)?.delete()
                val newDoc = doc.createFile("", fileName) ?: error("Create file failed")
                if (data is File) {
                    newDoc.openOutputStream()!!.use { output ->
                        data.inputStream().use { input -> input.copyTo(output) }
                    }
                } else {
                    newDoc.writeBytes(context, data.toSaveBytes())
                }
                newDoc.uri
            } else {
                val file = File(uri.path ?: uri.toString())
                val newFile = FileUtils.createFileIfNotExist(file, fileName)
                if (data is File) {
                    newFile.outputStream().use { output ->
                        data.inputStream().use { input -> input.copyTo(output) }
                    }
                } else {
                    newFile.writeBytes(data.toSaveBytes())
                }
                Uri.fromFile(newFile)
            }
        }.onError {
            it.printOnDebug()
            errorLiveData.postValue(it.localizedMessage)
        }.onSuccess {
            success.invoke(it)
        }
    }

    private fun Any.toSaveBytes(): ByteArray {
        return when (this) {
            is ByteArray -> this
            is String -> toByteArray()
            else -> GSON.toJson(this).toByteArray()
        }
    }

}
