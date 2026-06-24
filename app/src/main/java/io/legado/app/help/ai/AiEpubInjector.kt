package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import org.w3c.dom.Element
import java.io.File
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

object AiEpubInjector {

    data class MediaEntry(
        val fileName: String, // e.g., "images/ai_video_001.mp4"
        val mediaType: String, // "video/mp4", "audio/mpeg"
        val sourceFile: File
    )

    fun injectMedia(epubFile: File, mediaEntries: List<MediaEntry>, outputFile: File): Boolean {
        return runCatching {
            ZipFile(epubFile).use { zipIn ->
                // Locate the OPF entry (usually content.opf or any *.opf) so we can
                // rewrite its manifest while copying the rest of the archive verbatim.
                val opfEntry = zipIn.entries().asSequence()
                    .firstOrNull { entry ->
                        !entry.isDirectory && entry.name.endsWith(".opf", ignoreCase = true)
                    }
                var modifiedOpfXml: String? = null
                if (opfEntry != null && mediaEntries.isNotEmpty()) {
                    val opfContent = zipIn.getInputStream(opfEntry).use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    }
                    modifiedOpfXml = updateOpfManifest(opfContent, mediaEntries)
                }

                ZipOutputStream(outputFile.outputStream()).use { zipOut ->
                    // Copy existing entries, intercepting the OPF entry to inject the
                    // updated manifest XML.
                    zipIn.entries().asSequence().forEach { entry ->
                        if (!entry.isDirectory) {
                            zipOut.putNextEntry(ZipEntry(entry.name))
                            if (entry.name == opfEntry?.name && modifiedOpfXml != null) {
                                zipOut.write(modifiedOpfXml!!.toByteArray(Charsets.UTF_8))
                            } else {
                                zipIn.getInputStream(entry).use { it.copyTo(zipOut) }
                            }
                            zipOut.closeEntry()
                        }
                    }
                    // Add media files
                    for (media in mediaEntries) {
                        zipOut.putNextEntry(ZipEntry(media.fileName))
                        media.sourceFile.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }
            }
            true
        }.getOrElse { e ->
            AppLog.put("EPUB injection failed", e)
            false
        }
    }

    /**
     * Parse the OPF document and append an <item> element for every media entry
     * to the <manifest> section. Video/audio entries also get an <itemref> in the
     * <spine> so readers are aware of them. Returns the serialized OPF XML.
     */
    private fun updateOpfManifest(opfXml: String, mediaEntries: List<MediaEntry>): String {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            // Do not attempt to load any external DTD/entities referenced by the OPF.
            runCatching {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
        }
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(opfXml.byteInputStream(Charsets.UTF_8))

        val manifest = document.getElementsByTagName("manifest").item(0) as? Element
            ?: return opfXml
        // Reuse the manifest namespace (if any) so newly created items stay valid.
        val ns = manifest.namespaceURI

        mediaEntries.forEachIndexed { index, media ->
            val item = if (ns != null) {
                document.createElementNS(ns, "item")
            } else {
                document.createElement("item")
            }
            item.setAttribute("id", "ai_media_$index")
            item.setAttribute("href", media.fileName)
            item.setAttribute("media-type", media.mediaType)
            manifest.appendChild(item)
        }

        // Optionally announce video/audio entries in the spine as well.
        val spine = document.getElementsByTagName("spine").item(0) as? Element
        if (spine != null) {
            mediaEntries.forEachIndexed { index, media ->
                if (media.mediaType.startsWith("video/", ignoreCase = true) ||
                    media.mediaType.startsWith("audio/", ignoreCase = true)
                ) {
                    val itemref = if (ns != null) {
                        document.createElementNS(ns, "itemref")
                    } else {
                        document.createElement("itemref")
                    }
                    itemref.setAttribute("idref", "ai_media_$index")
                    spine.appendChild(itemref)
                }
            }
        }

        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "no")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }
}
