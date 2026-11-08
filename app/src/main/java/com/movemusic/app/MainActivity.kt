package com.movemusic.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import coil3.load
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.size.Precision

data class Song(val id: Long, val title: String, val artist: String, val albumId: Long, val uri: Uri)

class MainActivity : Activity() {
    private val blue = 0xFF168BFF.toInt()
    private val bg = 0xFF05070B.toInt()
    private val panel = 0xFF111722.toInt()
    private val white = 0xFFF2F6FC.toInt()
    private val muted = 0xFF9AA8B8.toInt()
    private val prefs by lazy { getSharedPreferences("movemusic", Context.MODE_PRIVATE) }
    private val playlists = linkedMapOf<String, MutableList<Long>>()
    private val songs = mutableListOf<Song>()
    private var currentPlaylist = "همه آهنگ‌ها"
    private var displayedSongCount = 50
    private var player: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingSong: Song? = null
    private var playbackRequestToken = 0
    private var playbackQueue: List<Song> = emptyList()
    private var currentSongIndex = -1
    private var shuffleEnabled = false
    private var currentSong: Song? = null
    private var playerScreenVisible = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var playPauseButton: ImageButton? = null
    private var miniPlayPauseButton: ImageButton? = null
    private var seekBarTask: Runnable? = null
    private lateinit var body: LinearLayout
    private lateinit var header: TextView
    private var homeRoot: View? = null
    private var miniPlayerHost: LinearLayout? = null
    private var songScrollView: ScrollView? = null
    private var fastScrollOverlay: FastScrollOverlay? = null
    private var songRowsStartIndex = 0
    private var pendingFastScrollIndex = -1
    private var searchQuery = ""
    private var searchField: EditText? = null
    private var searchContainer: LinearLayout? = null
    private var isLoadingNextPage = false
    private var hasMoreSongs = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPlaylists()
        startService(Intent(this, PlaybackService::class.java))
        connectPlaybackController()
        showPermissionOrLoad()
    }

    private fun connectPlaybackController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val controller = future.get()
                if (isFinishing || isDestroyed) {
                    MediaController.releaseFuture(future)
                    return@addListener
                }
                player = controller
                controller.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayerControls()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updatePlayerControls()
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        toast("پخش این فایل ممکن نیست.")
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        restorePlaybackState()
                    }
                })
                pendingSong?.let {
                    pendingSong = null
                    startSong(it)
                } ?: restorePlaybackState()
            } catch (_: Exception) {
                toast("اتصال به سرویس پخش موسیقی برقرار نشد.")
            }
        }, java.util.concurrent.Executor { command -> runOnUiThread(command) })
    }

    private fun restorePlaybackState() {
        val activeUri = player?.currentMediaItem?.localConfiguration?.uri ?: return
        val song = songs.firstOrNull { it.uri == activeUri } ?: return
        val songChanged = currentSong?.id != song.id
        currentSong = song
        if (playbackQueue.isEmpty()) playbackQueue = songs
        currentSongIndex = playbackQueue.indexOfFirst { it.id == song.id }
        if (songChanged) prefetchAdjacentArtwork(currentSongIndex)
        if (playerScreenVisible) {
            // Avoid rebuilding the screen for the initial transition of the
            // track that is already displayed.
            if (songChanged) showPlayerScreen()
        } else {
            miniPlayerHost?.let { host ->
                host.removeAllViews()
                host.visibility = View.VISIBLE
                host.addView(buildMiniPlayerBar(), LinearLayout.LayoutParams(-1, -2))
            }
        }
        updatePlayerControls()
    }

    private fun showPermissionOrLoad() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                         else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 10)
        } else {
            loadSongsAsync()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 10 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            loadSongsAsync()
        } else {
            setContentView(label("برای نمایش آهنگ‌های گوشی، مجوز دسترسی به فایل‌های صوتی لازم است.", 18))
        }
    }

    private fun loadSongsAsync() {
        setContentView(label("در حال بارگذاری آهنگ‌ها…", 18, white))
        Thread {
            val loadedSongs = mutableListOf<Song>()
            try {
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.IS_MUSIC
                )
                contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                    null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        loadedSongs.add(
                            Song(
                                id,
                                cursor.getString(titleCol) ?: "بدون عنوان",
                                cursor.getString(artistCol) ?: "هنرمند نامشخص",
                                cursor.getLong(albumIdCol),
                                ContentUris.withAppendedId(collection, id)
                            )
                        )
                    }
                }
                runOnUiThread {
                    songs.clear()
                    songs.addAll(loadedSongs)
                    displayedSongCount = 50
                    restorePlaybackState()
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setContentView(label("خواندن آهنگ‌ها با خطا مواجه شد. مجوز دسترسی به موسیقی را بررسی کن و برنامه را دوباره باز کن.", 16))
                }
            }
        }.start()
    }

    private fun loadPlaylists() {
        playlists.clear()
        val raw = prefs.getString("lists", null)
        if (raw != null) try {
            val obj = JSONObject(raw)
            obj.keys().forEach { name ->
                val arr = obj.getJSONArray(name)
                playlists[name] = MutableList(arr.length()) { i -> arr.getLong(i) }
            }
        } catch (_: Exception) {}
        if (playlists.isEmpty()) {
            playlists["موردعلاقه‌ها"] = mutableListOf()
            playlists["آرام"] = mutableListOf()
            savePlaylists()
        }
    }

    private fun savePlaylists() {
        val obj = JSONObject()
        playlists.forEach { (name, ids) -> obj.put(name, JSONArray(ids)) }
        prefs.edit().putString("lists", obj.toString()).apply()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            setPadding(12, 12, 12, 8)
        }
        header = label("MOVEMUSIC  ✦", 22, blue)
        root.addView(header)
        root.addView(label("موسیقی آفلاین، مدیریت ساده", 12, muted))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(button("همه آهنگ‌ها") { currentPlaylist = "همه آهنگ‌ها"; displayedSongCount = 50; refreshBody() })
        nav.addView(button("پلی‌لیست‌ها") { showPlaylistChooser() })
        nav.addView(button("+ جدید") { createPlaylist() })
        nav.addView(button("⌕ جست‌وجو") { toggleSearch() })
        root.addView(nav)
        searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, 6, 0, 6)
        }
        searchField = EditText(this).apply {
            hint = "نام آهنگ یا خواننده…"
            textSize = 14f
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(muted)
            setCompoundDrawablePadding(8)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim().orEmpty()
                    displayedSongCount = 50
                    pendingFastScrollIndex = -1
                    songScrollView?.scrollTo(0, 0)
                    if (::body.isInitialized) refreshBody()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        searchContainer?.addView(searchField, LinearLayout.LayoutParams(0, -2, 1f))
        searchContainer?.addView(button("✕") {
            searchField?.text?.clear()
            searchContainer?.visibility = View.GONE
            searchField?.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(searchField?.windowToken, 0)
        })
        root.addView(searchContainer)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }
        val scrollContainer = FrameLayout(this)
        scrollContainer.addView(
            scroll,
            FrameLayout.LayoutParams(-1, -1)
        )
        val fastScroller = FastScrollOverlay(scroll).apply {
            visibility = View.VISIBLE
        }
        scrollContainer.addView(
            fastScroller,
            FrameLayout.LayoutParams(
                (48 * resources.displayMetrics.density).toInt(),
                -1,
                Gravity.END
            )
        )
        root.addView(scrollContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        songScrollView = scroll
        fastScrollOverlay = fastScroller
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            fastScroller.invalidate()
            val contentHeight = scroll.getChildAt(0)?.height ?: 0
            if (hasMoreSongs && !isLoadingNextPage && contentHeight > 0 &&
                scrollY + scroll.height >= contentHeight - scroll.height / 2) {
                loadNextSongsPage()
            }
        }
        val miniHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (currentSong != null) miniHost.addView(buildMiniPlayerBar(), LinearLayout.LayoutParams(-1, -2))
        else miniHost.visibility = View.GONE
        root.addView(miniHost, LinearLayout.LayoutParams(-1, -2))
        miniPlayerHost = miniHost
        root.addView(label("برای انتقال، آهنگ را انتخاب کن و «انتقال به…» را بزن.", 10, muted))
        homeRoot = root
        setContentView(root)
        refreshBody()
    }

    private fun toggleSearch() {
        val container = searchContainer ?: return
        if (container.visibility == View.VISIBLE) {
            searchField?.requestFocus()
        } else {
            container.visibility = View.VISIBLE
            searchField?.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun visibleSongSource(): List<Song> {
        val playlistIds = if (currentPlaylist == "همه آهنگ‌ها") null
            else (playlists[currentPlaylist] ?: mutableListOf()).toHashSet()
        val source = songs.asSequence().filter { playlistIds == null || it.id in playlistIds }
        val terms = searchQuery.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return if (terms.isEmpty()) source.toList()
        else source.filter { song ->
            // Every search term must match either the title or artist.
            // This supports combined queries such as "song name artist"
            // and multiple artist names stored in the same artist field.
            terms.all { term ->
                song.title.contains(term, ignoreCase = true) ||
                    song.artist.contains(term, ignoreCase = true)
            }
        }.toList()
    }

    private inner class FastScrollOverlay(
        private val scrollView: ScrollView
    ) : View(this@MainActivity) {
        private val density = resources.displayMetrics.density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55808080
            strokeWidth = 2.5f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBDBDBD.toInt()
            strokeWidth = 4f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val hideTask = Runnable {
            indicatorVisible = false
            invalidate()
        }
        private var dragging = false
        private var indicatorVisible = false
        private var dragOffset = 0f

        private val trackInset get() = 8f * density
        private val minimumThumb get() = 38f * density

        init {
            // Keep a transparent touch strip attached: a GONE view cannot receive
            // ACTION_DOWN, so the fast scroller could never be grabbed by the user.
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        private fun scrollRange(): Int {
            val contentHeight = scrollView.getChildAt(0)?.height ?: 0
            return (contentHeight - scrollView.height).coerceAtLeast(0)
        }

        private fun totalSongCount(): Int {
            return visibleSongSource().size
        }

        private fun thumbHeight(): Float {
            if (height <= 0) return minimumThumb
            val estimatedContentHeight = (totalSongCount() * 72f * density)
                .coerceAtLeast(height.toFloat())
            return (height.toFloat() * height / estimatedContentHeight)
                .coerceAtLeast(minimumThumb)
                .coerceAtMost(height.toFloat())
        }

        private fun thumbTop(): Float {
            val available = (height - thumbHeight()).coerceAtLeast(0f)
            val total = totalSongCount()
            if (total <= 1) return 0f
            val firstVisibleIndex = scrollView.scrollY / (72f * density)
            return available * (firstVisibleIndex / (total - 1f)).coerceIn(0f, 1f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!indicatorVisible || scrollRange() <= 0 || height <= 0) return
            val centerX = width - 7f * density
            val top = trackInset
            val bottom = height - trackInset
            canvas.drawLine(centerX, top, centerX, bottom, trackPaint)
            val thumb = thumbHeight()
            val thumbTop = thumbTop().coerceIn(top, (bottom - thumb).coerceAtLeast(top))
            canvas.drawLine(centerX, thumbTop, centerX, thumbTop + thumb, thumbPaint)
        }

        private fun moveTo(y: Float) {
            val available = (height - thumbHeight()).coerceAtLeast(1f)
            val top = (y - dragOffset - thumbHeight() / 2f)
                .coerceIn(0f, available)
            val fraction = (top / available).coerceIn(0f, 1f)
            val totalSongs = visibleSongSource().size
            if (totalSongs > 0) {
                val targetIndex = (fraction * (totalSongs - 1)).toInt()
                scrollToSongIndex(targetIndex)
            }
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    uiHandler.removeCallbacks(hideTask)
                    indicatorVisible = true
                    invalidate()
                    val currentTop = thumbTop()
                    val currentBottom = currentTop + thumbHeight()
                    dragging = true
                    dragOffset = if (event.y in currentTop..currentBottom) {
                        event.y - currentTop - thumbHeight() / 2f
                    } else {
                        0f
                    }
                    moveTo(event.y)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) moveTo(event.y)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    uiHandler.removeCallbacks(hideTask)
                    uiHandler.postDelayed(hideTask, 1_000L)
                    return true
                }
            }
            return true
        }
    }

    private fun refreshBody() {
        body.removeAllViews()
        body.addView(label(
            if (searchQuery.isEmpty()) currentPlaylist else "نتایج جست‌وجو",
            18, white
        ))
        val allVisibleSongs = visibleSongSource()
        val page = allVisibleSongs.take(displayedSongCount)
        this.hasMoreSongs = allVisibleSongs.size > displayedSongCount
        if (currentPlaylist != "همه آهنگ‌ها" && searchQuery.isEmpty()) {
            body.addView(button("افزودن همه آهنگ‌ها به این پلی‌لیست") {
                val target = playlists[currentPlaylist] ?: return@button
                val existingIds = target.toHashSet()
                val newSongs = songs.filter { existingIds.add(it.id) }
                target.addAll(newSongs.map { it.id })
                savePlaylists()
                refreshBody()
                toast(if (newSongs.isEmpty()) "همه آهنگ‌ها از قبل در این پلی‌لیست هستند."
                      else "${newSongs.size} آهنگ به پلی‌لیست اضافه شد.")
            })
        }
        if (page.isEmpty()) {
            body.addView(label(
                if (searchQuery.isEmpty()) "اینجا هنوز آهنگی نیست." else "نتیجه‌ای پیدا نشد.",
                13, muted
            ))
        }
        if (searchQuery.isEmpty()) {
            body.addView(button(if (shuffleEnabled) "پخش تصادفی: روشن" else "پخش تصادفی: خاموش") {
                shuffleEnabled = !shuffleEnabled
                refreshBody()
                toast(if (shuffleEnabled) "پخش تصادفی فعال شد." else "پخش تصادفی غیرفعال شد.")
            })
        }
        songRowsStartIndex = body.childCount
        page.forEach { song -> addSongRow(song) }
        fastScrollOverlay?.invalidate()
    }

    private fun addSongRow(song: Song) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 5, 6, 5)
            setBackgroundColor(panel)
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        details.addView(TextView(this).apply {
            text = song.title
            textSize = 14f
            setTextColor(white)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        details.addView(TextView(this).apply {
            text = song.artist
            textSize = 11f
            setTextColor(muted)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (currentPlaylist != "همه آهنگ‌ها") {
            actions.addView(button("انتقال") { moveSong(song) })
            actions.addView(button("حذف") {
                playlists[currentPlaylist]?.remove(song.id)
                savePlaylists()
                refreshBody()
            })
        } else {
            actions.addView(button("+ لیست") { addSong(song) })
        }
        row.addView(actions)
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { play(song) }
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 3 })
    }

    private fun loadNextSongsPage() {
        if (!hasMoreSongs || isLoadingNextPage) return
        isLoadingNextPage = true
        val nextPage = visibleSongSource().drop(displayedSongCount).take(50).toList()
        val batch = nextPage
        hasMoreSongs = visibleSongSource().size > displayedSongCount + batch.size
        displayedSongCount += batch.size

        // Add rows in small UI batches instead of rebuilding the whole list.
        // This keeps scrolling responsive while the next page is appended.
        var index = 0
        fun appendChunk() {
            val end = minOf(index + 10, batch.size)
            while (index < end) {
                addSongRow(batch[index])
                index++
            }
            if (index < batch.size) {
                uiHandler.post { appendChunk() }
            } else {
                isLoadingNextPage = false
                if (pendingFastScrollIndex >= 0) {
                    val target = pendingFastScrollIndex
                    pendingFastScrollIndex = -1
                    scrollToSongIndex(target)
                }
            }
        }
        appendChunk()
    }

    private fun scrollToSongIndex(index: Int) {
        val visibleSongs = visibleSongSource()
        val totalSongs = visibleSongs.size
        if (totalSongs == 0) return
        val target = index.coerceIn(0, totalSongs - 1)

        if (target >= displayedSongCount && hasMoreSongs) {
            pendingFastScrollIndex = target
            if (!isLoadingNextPage) loadNextSongsPage()
            return
        }
        if (isLoadingNextPage) {
            pendingFastScrollIndex = target
            return
        }

        val row = body.getChildAt(songRowsStartIndex + target) ?: return
        songScrollView?.scrollTo(0, row.top)
    }

    private fun moveSong(song: Song) {
        val destinations = playlists.keys.filter { it != currentPlaylist }.toTypedArray()
        if (destinations.isEmpty()) { toast("اول یک پلی‌لیست دیگر بساز."); return }
        AlertDialog.Builder(this).setTitle("انتقال به پلی‌لیست")
            .setItems(destinations) { _, which ->
                val target = destinations[which]
                if (song.id !in playlists[target]!!) playlists[target]!!.add(song.id)
                playlists[currentPlaylist]?.remove(song.id)
                savePlaylists()
                refreshBody()
                toast("آهنگ منتقل شد.")
            }.show()
    }

    private fun addSong(song: Song) {
        val names = playlists.keys.toTypedArray()
        if (names.isEmpty()) return
        AlertDialog.Builder(this).setTitle("افزودن به پلی‌لیست").setItems(names) { _, i ->
            val list = playlists[names[i]]!!
            if (song.id !in list) list.add(song.id)
            savePlaylists(); toast("به ${names[i]} اضافه شد.")
        }.show()
    }

    private fun showPlaylistChooser() {
        val names = playlists.keys.toMutableList()
        names.add(0, "همه آهنگ‌ها")
        AlertDialog.Builder(this).setTitle("انتخاب پلی‌لیست").setItems(names.toTypedArray()) { _, i ->
            currentPlaylist = names[i]; displayedSongCount = 50; refreshBody()
        }.show()
    }

    private fun createPlaylist() {
        val input = EditText(this).apply { hint = "نام پلی‌لیست" }
        AlertDialog.Builder(this).setTitle("پلی‌لیست جدید").setView(input)
            .setNegativeButton("لغو", null).setPositiveButton("ساختن") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != "همه آهنگ‌ها" && !playlists.containsKey(name)) {
                    playlists[name] = mutableListOf(); savePlaylists()
                    currentPlaylist = name; displayedSongCount = 50; refreshBody()
                } else toast("این نام قابل استفاده نیست.")
            }.show()
    }

    private fun play(song: Song) {
        playbackQueue = if (currentPlaylist == "همه آهنگ‌ها") {
            // Reuse the loaded library instead of copying it on every tap.
            songs
        } else {
            // Use a hash set for membership checks. List.contains() here made
            // playlist startup increasingly slow as both library and playlist grew.
            val playlistIds = (playlists[currentPlaylist] ?: mutableListOf()).toHashSet()
            songs.filter { it.id in playlistIds }
        }
        currentSongIndex = playbackQueue.indexOfFirst { it.id == song.id }
        currentSong = song
        prefetchAdjacentArtwork(currentSongIndex)
        showPlayerScreen()
        startSong(song)
    }

    private fun startSong(song: Song) {
        val controller = player
        if (controller == null) {
            pendingSong = song
            return
        }

        val requestToken = ++playbackRequestToken
        val queueSnapshot = playbackQueue
        val selectedIndex = queueSnapshot.indexOfFirst { it.id == song.id }
            .takeIf { it >= 0 } ?: 0

        fun mediaItem(queuedSong: Song): MediaItem {
            val metadata = MediaMetadata.Builder()
                .setTitle(queuedSong.title)
                .setArtist(queuedSong.artist)
                .build()
            return MediaItem.Builder()
                .setMediaId(queuedSong.id.toString())
                .setUri(queuedSong.uri)
                .setMediaMetadata(metadata)
                .build()
        }

        // Start the selected local file immediately. Never wait for the
        // potentially large playlist to be converted into Media3 items.
        try {
            controller.setMediaItem(mediaItem(song))
            controller.prepare()
            controller.play()
            updatePlayerControls()
        } catch (_: Exception) {
            toast("باز کردن فایل موسیقی ممکن نشد.")
            return
        }

        // Populate the rest of the queue in the background while the selected
        // track is already playing. Inserting before/after the current item
        // preserves the active playback position.
        if (queueSnapshot.size <= 1) return
        Thread {
            val previousItems = queueSnapshot.subList(0, selectedIndex).map(::mediaItem)
            val nextItems = queueSnapshot.subList(selectedIndex + 1, queueSnapshot.size).map(::mediaItem)
            runOnUiThread {
                if (requestToken != playbackRequestToken || player !== controller ||
                    controller.currentMediaItem?.mediaId != song.id.toString()) return@runOnUiThread
                try {
                    if (previousItems.isNotEmpty()) {
                        controller.addMediaItems(0, previousItems)
                    }
                    if (nextItems.isNotEmpty()) {
                        controller.addMediaItems(controller.currentMediaItemIndex + 1, nextItems)
                    }
                } catch (_: Exception) {
                    // The current track is already playing; queue enrichment
                    // must never interrupt it.
                }
            }
        }.start()
    }

    private fun showPlayerScreen() {
        val song = currentSong ?: return
        playerScreenVisible = true

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(22, 28, 22, 24)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF242B22.toInt(), 0xFF505A3F.toInt(), 0xFF242B22.toInt())
            )
        }

        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(0xFF20251F.toInt())
                cornerRadius = 18f
            }
            clipToOutline = true
            setImageResource(android.R.drawable.ic_media_play)
            val size = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            root.addView(this, LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 26
            })
        }

        root.addView(TextView(this).apply {
            text = song.title
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
        })
        root.addView(TextView(this).apply {
            text = song.artist
            textSize = 15f
            setTextColor(0xFFD4D8CA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 7, 0, 24)
        })

        val seek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFD9E9B0.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFD9E9B0.toInt())
        }
        root.addView(seek, LinearLayout.LayoutParams(-1, -2))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val p = player ?: return
                    val duration = p.duration
                    if (duration > 0) p.seekTo(duration * progress / 1000L)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        controls.addView(playerControl(android.R.drawable.ic_media_previous, 90) { playPreviousSong() })
        playPauseButton = playerControl(
            if (player?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            120
        ) { togglePlayback() }.also {
            val lp = LinearLayout.LayoutParams(120, 120).apply {
                marginStart = 18; marginEnd = 18
            }
            controls.addView(it, lp)
        }
        controls.addView(playerControl(android.R.drawable.ic_media_next, 90) { playNextSong() })
        root.addView(controls)

        root.addView(TextView(this).apply {
            text = "برای بازگشت، دکمه برگشت گوشی را بزنید؛ موسیقی ادامه پیدا می‌کند."
            textSize = 11f
            setTextColor(0xFFD4D8CA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })

        setContentView(root)
        loadSongArtwork(song, cover)
        updatePlayerControls()
        updateSeekBar(seek)
    }

    private fun buildMiniPlayerBar(): View {
        val song = currentSong!!
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(14, 16, 14, 16)
            minimumHeight = 200
            background = GradientDrawable().apply {
                setColor(blue)
                cornerRadius = 14f
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showPlayerScreen() }
        }

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        details.addView(TextView(this).apply {
            text = song.title
            textSize = 13f
            setTextColor(white)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        details.addView(TextView(this).apply {
            text = song.artist
            textSize = 11f
            setTextColor(muted)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        bar.addView(details, LinearLayout.LayoutParams(0, -2, 1f))

        val miniControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        miniControls.addView(miniPlayerButton(android.R.drawable.ic_media_previous, "آهنگ قبلی", 90) {
            playPreviousSong()
        })
        miniPlayPauseButton = miniPlayerButton(
            if (player?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            "پخش یا توقف",
            120
        ) { togglePlayback() }
        miniControls.addView(miniPlayPauseButton)
        miniControls.addView(miniPlayerButton(android.R.drawable.ic_media_next, "آهنگ بعدی", 90) {
            playNextSong()
        })
        bar.addView(miniControls)
        return bar
    }

    private fun miniPlayerButton(iconRes: Int, description: String, size: Int, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(size / 6, size / 6, size / 6, size / 6)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF009D98.toInt())
            }
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = 5
                marginEnd = 5
            }
            setOnClickListener { action() }
        }

    private fun artworkUri(song: Song): Uri =
        Uri.withAppendedPath(song.uri, "albumart")

    private fun loadSongArtwork(song: Song, target: ImageView) {
        // INEXACT lets Coil reuse a larger cached bitmap and avoid decoding
        // beyond the source image's native dimensions. ImageView scales it.
        target.scaleType = ImageView.ScaleType.CENTER_CROP
        target.load(artworkUri(song)) {
            precision(Precision.INEXACT)
        }
    }

    private fun prefetchAdjacentArtwork(index: Int) {
        if (index !in playbackQueue.indices) return

        // Match the player cover's requested dimensions so the prefetched
        // result is reusable when the user opens the adjacent track.
        val size = (resources.displayMetrics.widthPixels * 0.78f)
            .toInt()
            .coerceAtLeast(1)
        val imageLoader = SingletonImageLoader.get(applicationContext)

        listOf(index - 1, index + 1)
            .filter { it in playbackQueue.indices }
            .distinct()
            .forEach { adjacentIndex ->
                val request = ImageRequest.Builder(applicationContext)
                    .data(artworkUri(playbackQueue[adjacentIndex]))
                    .size(size, size)
                    .precision(Precision.INEXACT)
                    .build()
                imageLoader.enqueue(request)
            }
    }

    private fun playerControl(iconRes: Int, size: Int, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(size / 6, size / 6, size / 6, size / 6)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF009D98.toInt())
            }
            contentDescription = when (iconRes) {
                android.R.drawable.ic_media_previous -> "آهنگ قبلی"
                android.R.drawable.ic_media_next -> "آهنگ بعدی"
                android.R.drawable.ic_media_pause -> "توقف موقت"
                else -> "پخش"
            }
            setOnClickListener { action() }
        }

    private fun updatePlayerControls() {
        val activePlayer = player
        // Update optimistically from playWhenReady so the icon follows the
        // user's command instead of waiting for decoder startup.
        val shouldShowPause = activePlayer?.playWhenReady == true &&
            activePlayer.playbackState != Player.STATE_ENDED
        val icon = if (shouldShowPause) android.R.drawable.ic_media_pause
                   else android.R.drawable.ic_media_play
        playPauseButton?.setImageResource(icon)
        miniPlayPauseButton?.setImageResource(icon)
    }

    private fun togglePlayback() {
        val p = player ?: return
        try {
            if (p.isPlaying) {
                p.pause()
            } else {
                if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                p.play()
            }
            updatePlayerControls()
        } catch (_: Exception) {}
    }

    private fun updateSeekBar(seek: SeekBar) {
        seekBarTask?.let { uiHandler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (!playerScreenVisible) return
                try {
                    val p = player
                    if (p != null && p.duration > 0) {
                        seek.progress = (p.currentPosition * 1000L / p.duration).toInt()
                    }
                } catch (_: Exception) {}
                uiHandler.postDelayed(this, 500)
            }
        }
        seekBarTask = task
        uiHandler.post(task)
    }

    private fun playPreviousSong() {
        val controller = player ?: return
        if (playbackQueue.isEmpty() || currentSongIndex !in playbackQueue.indices) return
        if (shuffleEnabled && playbackQueue.size > 1) {
            val target = (playbackQueue.indices.filter { it != currentSongIndex }).random()
            controller.seekTo(target, 0L)
        } else {
            controller.seekToPreviousMediaItem()
        }
    }

    private fun playNextSong(manual: Boolean = false) {
        val controller = player ?: return
        if (playbackQueue.size < 2 || currentSongIndex !in playbackQueue.indices) return
        if (shuffleEnabled) {
            val target = (playbackQueue.indices.filter { it != currentSongIndex }).random()
            controller.seekTo(target, 0L)
        } else {
            controller.seekToNextMediaItem()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (playerScreenVisible) {
            playerScreenVisible = false
            seekBarTask?.let { uiHandler.removeCallbacks(it) }
            seekBarTask = null
            playPauseButton = null
            miniPlayPauseButton = null
            // Restore the existing home view instead of rebuilding every song row.
            // This preserves the exact scroll position and avoids a long UI-thread pass.
            homeRoot?.let { setContentView(it) } ?: render()
            miniPlayerHost?.let { host ->
                host.removeAllViews()
                if (currentSong != null) {
                    host.visibility = View.VISIBLE
                    host.addView(buildMiniPlayerBar(), LinearLayout.LayoutParams(-1, -2))
                } else {
                    host.visibility = View.GONE
                }
            }
        } else {
            super.onBackPressed()
        }
    }

    private fun label(text: String, size: Int, color: Int = white) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        setPadding(4, 8, 4, 8)
    }

    private fun button(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(white)
        gravity = Gravity.CENTER
        minHeight = 0
        minWidth = 0
        setPadding(9, 6, 9, 6)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(blue)
            cornerRadius = 7f
        }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.marginStart = 3
        lp.marginEnd = 3
        layoutParams = lp
        setOnClickListener { action() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        pendingSong = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        player = null
        controllerFuture = null
        super.onDestroy()
    }
}
