package to.eyed.inferno.data

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import to.eyed.inferno.data.db.GeneratedImageDao
import to.eyed.inferno.data.db.GeneratedImageEntity
import to.eyed.inferno.imagegen.GeneratedImageStore
import to.eyed.inferno.imagegen.GenerationMeta
import to.eyed.inferno.imagegen.GenerationRecord
import java.util.UUID

/**
 * Persistence of finished text-to-image generations: the PNG + 256 px thumb go under files/images/gen/
 * through [ImageUtil], the metadata into the generated_images table. The row owns the files, so [delete] is the
 * only path that removes them and ImageUtil.sweepOrphans never looks into gen/.
 */
class GeneratedImageStoreImpl(private val dao: GeneratedImageDao, private val images: ImageUtil) : GeneratedImageStore {
    /** Newest first; backs the GalleryScreen grid. */
    val all: Flow<List<GenerationRecord>> = dao.observeAll().map { rows -> rows.map { it.toRecord() } }

    override suspend fun save(png: ByteArray, meta: GenerationMeta): GenerationRecord {
        val files = images.saveGenerated(png, UUID.randomUUID().toString())
        val entity = GeneratedImageEntity(
            id = files.id, modelId = meta.modelId, prompt = meta.prompt, negative = meta.negative, width = meta.width,
            height = meta.height, steps = meta.steps, seed = meta.seed, sampler = meta.sampler, totalMs = meta.totalMs,
            createdAt = meta.createdAt, path = files.path, thumbPath = files.thumbPath,
        )
        try {
            dao.insert(entity)
        } catch (e: Exception) {
            // No row => nobody would ever find (or delete) the files again.
            images.deleteGenerated(files)
            throw e
        }
        return entity.toRecord()
    }

    suspend fun byId(id: String): GenerationRecord? = dao.byId(id)?.toRecord()

    /** Long-press delete in the gallery: row first (so a crash in between leaves at worst orphan files, never a dead row). */
    suspend fun delete(id: String) {
        val row = dao.byId(id) ?: return
        dao.delete(id)
        images.deleteGenerated(row.path, row.thumbPath)
    }

    /** "Save to Photos": copies the PNG into MediaStore Pictures/Inferno and returns the new item. */
    suspend fun exportToPictures(record: GenerationRecord): Uri = images.exportToPictures(record.path, "inferno-${record.seed}-${record.id.take(8)}")

    private fun GeneratedImageEntity.toRecord() = GenerationRecord(
        id = id, modelId = modelId, prompt = prompt, negative = negative, width = width, height = height, steps = steps,
        seed = seed, sampler = sampler, totalMs = totalMs, createdAt = createdAt, path = path, thumbPath = thumbPath,
    )
}
