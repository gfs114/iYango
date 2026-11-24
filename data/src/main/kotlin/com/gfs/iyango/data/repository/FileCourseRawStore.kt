package com.gfs.iyango.data.repository

import android.content.Context
import com.gfs.iyango.core.domain.CourseRawHtmlRecord
import com.gfs.iyango.core.domain.CourseRawStorePort
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 基于私有目录文件的 [CourseRawStorePort] 实现。
 *
 * 每张教务课表在 `noBackupFilesDir/course_raw/` 下保存最近一次同步的 HTML 原文与元数据；
 * 单文件上限 2 MB，写入采用临时文件原子替换。
 */
@Singleton
class FileCourseRawStore internal constructor(
    private val directory: File,
) : CourseRawStorePort {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "course_raw"))

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun saveHtml(
        timetableId: Long,
        html: String,
        term: String,
        savedAt: Long,
    ) {
        if (timetableId <= 0L || html.isBlank()) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create course_raw directory" }
                val htmlBytes = boundedUtf8Bytes(html, MAX_HTML_BYTES)
                val metaBytes = json.encodeToString(
                    RawMetaDto(term = term, savedAt = savedAt),
                ).toByteArray(Charsets.UTF_8)
                writeAtomically(htmlFile(timetableId), htmlBytes, "raw-", ".html.tmp")
                writeAtomically(metaFile(timetableId), metaBytes, "raw-", ".meta.tmp")
            }
        }
    }

    override suspend fun loadHtml(timetableId: Long): CourseRawHtmlRecord? {
        if (timetableId <= 0L) return null
        return withContext(Dispatchers.IO) {
            lock.withLock {
                val htmlTarget = htmlFile(timetableId)
                if (!htmlTarget.isFile) return@withLock null
                try {
                    val bytes = htmlTarget.readBytes()
                    if (bytes.isEmpty() || bytes.size > MAX_HTML_BYTES) return@withLock null
                    val html = bytes.toString(Charsets.UTF_8)
                    val meta = metaFile(timetableId)
                        .takeIf { it.isFile }
                        ?.let { file ->
                            runCatching {
                                json.decodeFromString<RawMetaDto>(file.readText(Charsets.UTF_8))
                            }.getOrNull()
                        }
                    CourseRawHtmlRecord(
                        html = html,
                        term = meta?.term.orEmpty(),
                        savedAt = meta?.savedAt ?: htmlTarget.lastModified(),
                    )
                } catch (_: IOException) {
                    null
                } catch (_: SerializationException) {
                    null
                }
            }
        }
    }

    private fun htmlFile(timetableId: Long): File = File(directory, "timetable_$timetableId.html")
    private fun metaFile(timetableId: Long): File = File(directory, "timetable_$timetableId.meta")

    private fun writeAtomically(target: File, bytes: ByteArray, prefix: String, suffix: String) {
        val temp = File.createTempFile(prefix, suffix, directory)
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun boundedUtf8Bytes(text: String, maxBytes: Int): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= maxBytes) return raw
        var end = maxBytes
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return raw.copyOf(end)
    }

    @Serializable
    private data class RawMetaDto(
        val term: String = "",
        val savedAt: Long = 0L,
    )

    private companion object {
        const val MAX_HTML_BYTES = 2 * 1024 * 1024
    }
}
