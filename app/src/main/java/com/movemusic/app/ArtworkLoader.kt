package com.movemusic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Artwork pipeline: memory cache -> persistent disk cache -> MediaStore album
 * artwork -> embedded artwork fallback. All I/O and decoding stay off the UI
 * thread, and simultaneous requests for one album share a single decode.
 */
internal class ArtworkLoader(context: Context) {
    private val appContext = context.applicationContext
    private val diskDirectory = File(appContext.cacheDir, "artwork").apply { mkdirs() }
    // Keep at most the currently requested album queued. Rapid track skipping
    // must not leave a long tail of obsolete artwork decodes ahead of the UI.
    private val executor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingDeque<Runnable>()
    )
    private inner class ArtworkTask(
        val key: String,
        val isPrefetch: Boolean,
        val work: () -> Unit
    ) : Runnable {
        override fun run() = work()
    }
    private val memoryCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pending = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()

    fun load(song: Song, targetSize: Int, callback: (Bitmap?) -> Unit) {
        request(song, targetSize, callback, false)
    }

    /**
     * Warm the caches for a small number of likely-to-be-opened tracks.
     * Prefetch tasks are queued behind user-requested artwork and are discarded
     * when a fresh on-demand request needs the worker queue.
     */
    fun prefetch(song: Song, targetSize: Int) {
        request(song, targetSize, null, true)
    }

    private fun request(
        song: Song,
        targetSize: Int,
        callback: ((Bitmap?) -> Unit)?,
        isPrefetch: Boolean
    ) {
        val key = if (song.albumId > 0) "album_${song.albumId}" else "song_${song.id}"
        memoryCache.get(key)?.let { callback?.invoke(it); return }

        var shouldStart = false
        synchronized(pending) {
            val listeners = pending[key]
            if (listeners != null) {
                if (callback != null) listeners.add(callback)
            } else {
                if (!isPrefetch) {
                    // User-visible artwork always takes precedence over queued
                    // speculative work. Running jobs finish and warm the cache.
                    val queuedPrefetchKeys = executor.queue.filter {
                        it is ArtworkTask && it.isPrefetch
                    }.map { (it as ArtworkTask).key }.toSet()
                    executor.queue.removeIf { it is ArtworkTask && it.isPrefetch }
                    queuedPrefetchKeys.forEach { pending.remove(it) }
                }
                pending[key] = mutableListOf<(Bitmap?) -> Unit>().also {
                    if (callback != null) it.add(callback)
                }
                shouldStart = true
            }
        }
        if (!shouldStart) return

        val task = ArtworkTask(key, isPrefetch) {
            val bitmap = try {
                loadFromDisk(key, targetSize)
                    ?: loadFromMediaStore(song.albumId, targetSize)
                    ?: loadEmbedded(song, targetSize)
            } catch (_: Exception) { null }

            if (bitmap != null) memoryCache.put(key, bitmap)
            val listeners = synchronized(pending) { pending.remove(key).orEmpty() }
            listeners.forEach { listener -> try { listener(bitmap) } catch (_: Exception) {} }
            if (bitmap != null) saveToDisk(key, bitmap)
        }

        if (isPrefetch) {
            executor.execute(task)
        } else {
            // Put the requested artwork at the front of the waiting queue.
            if (executor.poolSize < executor.corePoolSize) executor.execute(task)
            else executor.queue.offerFirst(task)
        }
    }

    private fun loadFromDisk(key: String, targetSize: Int): Bitmap? {
        val file = File(diskDirectory, "$key.webp")
        if (!file.isFile || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetSize)
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun loadFromMediaStore(albumId: Long, targetSize: Int): Bitmap? {
        if (albumId <= 0) return null
        val uri = Uri.parse("content://media/external/audio/albumart/$albumId")
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return decode(bytes, targetSize)
    }

    private fun loadEmbedded(song: Song, targetSize: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, song.uri)
            retriever.embeddedPicture?.let { decode(it, targetSize) }
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun decode(bytes: ByteArray, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetSize)
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun sampleSize(width: Int, height: Int, targetSize: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= targetSize) sample *= 2
        return sample
    }

    private fun saveToDisk(key: String, bitmap: Bitmap) {
        try {
            val file = File(diskDirectory, "$key.webp")
            val temp = File(diskDirectory, "$key.tmp")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, 92, it) }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            trimDiskCache()
        } catch (_: Exception) {}
    }

    private fun trimDiskCache() {
        val files = diskDirectory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        val maxBytes = 64L * 1024L * 1024L
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    fun close() {
        executor.shutdownNow()
        memoryCache.evictAll()
        synchronized(pending) { pending.clear() }
    }
}
