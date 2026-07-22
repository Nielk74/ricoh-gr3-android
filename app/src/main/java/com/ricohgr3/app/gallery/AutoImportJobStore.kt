package com.ricohgr3.app.gallery

import android.content.Context
import android.os.StatFs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Durable single-job store plus the app-private spool that backs drain-first importing. */
internal class AutoImportJobStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "auto-import")
    private val spool = File(root, "spool")
    private val jobFile = File(root, "job.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun load(): AutoImportJob? {
        val loaded = readStoredJob() ?: return null
        spool.listFiles { file -> file.name.endsWith(PART_SUFFIX) }
            ?.forEach { it.delete() }
        val recovered = loaded.recoverAfterProcessDeath()
        if (recovered != loaded) save(recovered)
        return recovered
    }

    @Synchronized
    fun create(job: AutoImportJob) {
        clear()
        spool.mkdirs()
        save(job)
    }

    @Synchronized
    fun save(job: AutoImportJob) {
        root.mkdirs()
        spool.mkdirs()
        val temporary = File(root, "job.json.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(job).encodeToByteArray())
            output.fd.sync()
        }
        moveReplacing(temporary, jobFile)
    }

    fun stagedFile(entry: StoredImportFile): File = File(spool, entry.spoolName)

    fun partialFile(entry: StoredImportFile): File = File(spool, entry.spoolName + PART_SUFFIX)

    fun commitPartial(entry: StoredImportFile) {
        val partial = partialFile(entry)
        if (!partial.isFile) throw IOException("Partial download is missing for ${entry.file}")
        moveReplacing(partial, stagedFile(entry))
    }

    fun deleteStaged(entry: StoredImportFile) {
        stagedFile(entry).delete()
        partialFile(entry).delete()
    }

    fun availableBytes(): Long {
        root.mkdirs()
        return StatFs(root.absolutePath).availableBytes
    }

    @Synchronized
    fun clear() {
        if (root.exists() && !root.deleteRecursively()) {
            throw IOException("Could not clear the previous auto-import spool")
        }
    }

    /** Delete an asynchronously dismissed spool only if no newer job has replaced it. */
    @Synchronized
    fun clearIfCurrentJob(expectedJobId: String?) {
        val currentJobId = readStoredJob()?.id
        if (currentJobId == expectedJobId) clear()
    }

    private fun readStoredJob(): AutoImportJob? {
        if (!jobFile.isFile) return null
        return runCatching { json.decodeFromString<AutoImportJob>(jobFile.readText()) }.getOrNull()
    }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}
