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
import java.util.concurrent.Executors

/**
 * Artwork pipeline: memory cache -> persistent disk cache -> MediaStore album
 * artwork -> embedded artwork fallback. All I/O and decoding stay off the UI
 * thread, and simultaneous requests for one album share a single decode.
 */
internal class ArtworkLoader(context: Context) {
    private val appContext = context.applicationContext
    private val diskDirectory = File(appContext.cacheDir, "artwork").apply { mkdirs() }
    private val executor = Executors.newFixedThreadPool(2)
    private val memoryCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pending = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()

    fun load(song: Song, targetSize: Int, callback: (Bitmap?) -> Unit) {
        val key = if (song.albumId > 0) "album_\${song.albumId}" else "song_\${song.id}"
        memoryCache.get(key)?.let { callback(it); return }

        var shouldStart = false
        synchronized(pending) {
            val listeners = pending[key]
            if (listeners != null) listeners.add(callback)
            else {
                pending[key] = mutableListOf(callback)
                shouldStart = true
            }
        }
        if (!shouldStart) return

        executor.execute {
            val bitmap = try {
                loadFromDisk(key, targetSize)
                    ?: loadFromMediaStore(song.albumId, targetSize)
                    ?: loadEmbedded(song, targetSize)
            } catch (_: Exception) { null }

            if (bitmap != null) memoryCache.put(key, bitmap)
            val listeners = synchronized(pending) { pending.remove(key).orEmpty() }
            // Deliver as soon as decoding finishes; disk persistence must not
            // delay the visible cover on the main thread.
            listeners.forEach { listener -> try { listener(bitmap) } catch (_: Exception) {} }
            if (bitmap != null) saveToDisk(key, bitmap)
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
