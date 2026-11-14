package com.movemusic.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
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
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
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

class MainActivity : ComponentActivity() {
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
    private var songRecyclerView: RecyclerView? = null
    private var songAdapter: SongAdapter? = null
    private var fastScrollOverlay: FastScrollOverlay? = null
    private var visibleSongsCache: List<Song> = emptyList()
    private var searchQuery = ""
    private var searchField: EditText? = null
    private var searchContainer: LinearLayout? = null
    private var pendingDeletionSong: Song? = null
    private val deletePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val deletedSong = pendingDeletionSong
        pendingDeletionSong = null
        if (result.resultCode == RESULT_OK && deletedSong != null) {
            onSongDeleted(deletedSong)
        } else if (result.resultCode != RESULT_OK) {
            toast("حذف آهنگ لغو شد.")
        }
    }

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
        if (songChanged) songAdapter?.notifyDataSetChanged()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
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
                    """${MediaStore.Audio.Media.IS_MUSIC} != 0
                        OR ${MediaStore.Audio.Media.MIME_TYPE} IN ('audio/wav', 'audio/x-wav', 'audio/vnd.wave', 'audio/wave')
                        OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.wav'""".trimIndent(),
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
                    songRecyclerView?.scrollToPosition(0)
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
        root.addView(body, LinearLayout.LayoutParams(-1, -2))
        val listContainer = FrameLayout(this)
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            itemAnimator = null
            setHasFixedSize(false)
            // Keep the list content edge 5dp away from the visible scrollbar stroke.
            // The stroke is drawn 7dp from the overlay's right edge with a 2dp half-width.
            setPadding(
                paddingLeft, paddingTop,
                (11 * resources.displayMetrics.density).toInt(), paddingBottom
            )
            clipToPadding = false
        }
        songAdapter = SongAdapter()
        recycler.adapter = songAdapter
        listContainer.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        val fastScroller = FastScrollOverlay(recycler).apply { visibility = View.VISIBLE }
        listContainer.addView(fastScroller, FrameLayout.LayoutParams(
            (35 * resources.displayMetrics.density).toInt(), -1, Gravity.END
        ))
        root.addView(listContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        songRecyclerView = recycler
        fastScrollOverlay = fastScroller
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                fastScroller.invalidate()
            }
        })
        val miniHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (currentSong != null) miniHost.addView(buildMiniPlayerBar(), LinearLayout.LayoutParams(-1, -2))
        else miniHost.visibility = View.GONE
        root.addView(miniHost, LinearLayout.LayoutParams(-1, -2))
        miniPlayerHost = miniHost
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
        private val recyclerView: RecyclerView
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
        private val hideTask = Runnable { indicatorVisible = false; invalidate() }
        private var dragging = false
        private var indicatorVisible = false
        private var dragOffset = 0f
        private val trackInset get() = 8f * density
        private val minimumThumb get() = 38f * density
        private val layoutManager get() = recyclerView.layoutManager as? LinearLayoutManager

        init {
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
        }

        private fun totalCount() = visibleSongsCache.size
        private fun visibleItemCount(): Int = (layoutManager?.childCount ?: 0).coerceAtLeast(1)
        private fun maxFirstVisiblePosition(): Int = (totalCount() - visibleItemCount()).coerceAtLeast(0)

        private fun thumbHeight(): Float {
            if (height <= 0) return minimumThumb
            val trackLength = (height - 2f * trackInset).coerceAtLeast(1f)
            val fraction = (visibleItemCount().toFloat() / totalCount().coerceAtLeast(1)).coerceIn(0f, 1f)
            return (trackLength * fraction).coerceAtLeast(minimumThumb).coerceAtMost(trackLength)
        }

        private fun currentScrollFraction(): Float {
            val maxPosition = maxFirstVisiblePosition()
            if (maxPosition == 0) return 0f
            val first = layoutManager?.findFirstVisibleItemPosition() ?: 0
            return (first.toFloat() / maxPosition).coerceIn(0f, 1f)
        }

        private fun thumbTop(): Float {
            val trackLength = (height - 2f * trackInset).coerceAtLeast(0f)
            val available = (trackLength - thumbHeight()).coerceAtLeast(0f)
            return trackInset + available * currentScrollFraction()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!indicatorVisible || totalCount() <= 0 || height <= 0) return
            val x = width - 7f * density
            val top = trackInset
            val bottom = height - trackInset
            canvas.drawLine(x, top, x, bottom, trackPaint)
            val thumb = thumbHeight()
            val topThumb = thumbTop().coerceIn(top, (bottom - thumb).coerceAtLeast(top))
            canvas.drawLine(x, topThumb, x, topThumb + thumb, thumbPaint)
        }

        private fun moveTo(y: Float) {
            val manager = layoutManager ?: return
            val count = totalCount()
            if (count <= 0) return
            val trackLength = (height - 2f * trackInset).coerceAtLeast(1f)
            val available = (trackLength - thumbHeight()).coerceAtLeast(1f)
            val top = (y - dragOffset).coerceIn(trackInset, trackInset + available)
            val fraction = ((top - trackInset) / available).coerceIn(0f, 1f)
            val maxPosition = maxFirstVisiblePosition()
            val target = fraction * maxPosition
            val position = target.toInt().coerceIn(0, count - 1)
            val fractionWithinItem = target - position
            val itemHeight = recyclerView.getChildAt(0)?.height?.takeIf { it > 0 } ?: 1
            val offset = -(fractionWithinItem * itemHeight).toInt()
            // Seek by adapter position: pixel scroll metrics fluctuate during layout.
            manager.scrollToPositionWithOffset(position, offset)
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // The list reserves 39dp (35dp bar + 4dp gap), so the full width is safe to touch.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    uiHandler.removeCallbacks(hideTask)
                    indicatorVisible = true
                    val top = thumbTop()
                    val bottom = top + thumbHeight()
                    dragging = true
                    dragOffset = if (event.y in top..bottom) event.y - top else thumbHeight() / 2f
                    moveTo(event.y)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> { if (dragging) moveTo(event.y); return true }
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
        visibleSongsCache = allVisibleSongs
        if (currentPlaylist != "همه آهنگ‌ها" && searchQuery.isEmpty()) {
            val playlistActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            playlistActions.addView(button("افزودن همه آهنگ‌ها") {
                val target = playlists[currentPlaylist] ?: return@button
                val existingIds = target.toHashSet()
                val newSongs = songs.filter { existingIds.add(it.id) }
                target.addAll(newSongs.map { it.id })
                savePlaylists()
                refreshBody()
                toast(if (newSongs.isEmpty()) "همه آهنگ‌ها از قبل در این پلی‌لیست هستند."
                      else "${newSongs.size} آهنگ به پلی‌لیست اضافه شد.")
            }, LinearLayout.LayoutParams(0, -2, 1f))
            playlistActions.addView(button("کپی کامل به…") {
                copyCurrentPlaylistToAnother()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(playlistActions)
        }
        if (searchQuery.isEmpty()) {
            body.addView(button(if (shuffleEnabled) "پخش تصادفی: روشن" else "پخش تصادفی: خاموش") {
                shuffleEnabled = !shuffleEnabled
                startService(
                    Intent(this, PlaybackService::class.java)
                        .setAction(PlaybackService.ACTION_SET_SHUFFLE)
                        .putExtra(PlaybackService.EXTRA_SHUFFLE_ENABLED, shuffleEnabled)
                )
                refreshBody()
                toast(if (shuffleEnabled) "پخش تصادفی فعال شد." else "پخش تصادفی غیرفعال شد.")
            })
        }
        songAdapter?.setSongs(allVisibleSongs)
        songRecyclerView?.scrollToPosition(0)
        fastScrollOverlay?.invalidate()
    }

    private inner class SongAdapter : RecyclerView.Adapter<SongAdapter.SongHolder>() {
        private var items: List<Song> = emptyList()

        inner class SongHolder(
            val row: LinearLayout,
            val titleView: TextView,
            val artistView: TextView,
            val addButton: TextView,
            val transferButton: TextView,
            val removeButton: TextView,
            val deleteFileButton: TextView
        ) : RecyclerView.ViewHolder(row) {
            var boundSong: Song? = null
        }

        fun setSongs(songs: List<Song>) {
            items = songs
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SongHolder {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 5, 6, 5)
                setBackgroundColor(panel)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(white)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val artist = TextView(this@MainActivity).apply {
                textSize = 11f
                setTextColor(muted)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            details.addView(title)
            details.addView(artist)
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))

            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            lateinit var holder: SongHolder
            val add = songActionButton("+ لیست") {
                holder.boundSong?.let { addSong(it) }
            }
            val transfer = songActionButton("انتقال") {
                holder.boundSong?.let { moveSong(it) }
            }
            val remove = songActionButton("حذف") {
                holder.boundSong?.let { song ->
                    playlists[currentPlaylist]?.remove(song.id)
                    savePlaylists()
                    refreshBody()
                }
            }
            val deleteFile = songActionButton("حذف") {
                holder.boundSong?.let { confirmDeleteSong(it) }
            }
            actions.addView(add)
            actions.addView(transfer)
            actions.addView(remove)
            actions.addView(deleteFile)
            row.addView(actions)

            holder = SongHolder(row, title, artist, add, transfer, remove, deleteFile)
            row.setOnClickListener { holder.boundSong?.let { play(it) } }
            return holder
        }

        override fun onBindViewHolder(holder: SongHolder, position: Int) {
            val song = items[position]
            holder.boundSong = song
            holder.titleView.text = song.title
            holder.artistView.text = song.artist
            holder.row.setBackgroundColor(
                if (song.id == currentSong?.id) 0xFF29313D.toInt() else panel
            )
            val inAllSongs = currentPlaylist == "همه آهنگ‌ها"
            holder.addButton.visibility = if (inAllSongs) View.VISIBLE else View.GONE
            holder.deleteFileButton.visibility = if (inAllSongs) View.VISIBLE else View.GONE
            holder.transferButton.visibility = if (inAllSongs) View.GONE else View.VISIBLE
            holder.removeButton.visibility = if (inAllSongs) View.GONE else View.VISIBLE
        }
    }

    private fun songActionButton(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(white)
        gravity = Gravity.CENTER
        minHeight = (36 * resources.displayMetrics.density).toInt()
        minWidth = 0
        setPadding(
            (11 * resources.displayMetrics.density).toInt(),
            (7 * resources.displayMetrics.density).toInt(),
            (11 * resources.displayMetrics.density).toInt(),
            (7 * resources.displayMetrics.density).toInt()
        )
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(blue)
            cornerRadius = 7f * resources.displayMetrics.density
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = (2 * resources.displayMetrics.density).toInt()
            marginEnd = (2 * resources.displayMetrics.density).toInt()
        }
        setOnClickListener { action() }
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

    private fun confirmDeleteSong(song: Song) {
        AlertDialog.Builder(this)
            .setTitle("حذف آهنگ")
            .setMessage("آیا مطمئنی می‌خواهی «" + song.title + "» را برای همیشه از گوشی حذف کنی؟")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("حذف") { _, _ -> requestSongDeletion(song) }
            .show()
    }

    private fun requestSongDeletion(song: Song) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                pendingDeletionSong = song
                val request = MediaStore.createDeleteRequest(contentResolver, listOf(song.uri))
                deletePermissionLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build()
                )
            } else {
                val deleted = contentResolver.delete(song.uri, null, null)
                if (deleted > 0) onSongDeleted(song) else toast("حذف آهنگ انجام نشد.")
            }
        } catch (e: RecoverableSecurityException) {
            if (Build.VERSION.SDK_INT == 29) {
                try {
                    pendingDeletionSong = song
                    deletePermissionLauncher.launch(
                        IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                    )
                } catch (_: Exception) { toast("درخواست مجوز حذف باز نشد.") }
            } else toast("حذف آهنگ ممکن نشد.")
        } catch (_: Exception) {
            toast("حذف آهنگ ممکن نشد.")
        }
    }

    private fun onSongDeleted(song: Song) {
        songs.removeAll { it.id == song.id }
        playlists.values.forEach { it.remove(song.id) }
        savePlaylists()
        if (currentSong?.id == song.id) {
            try { player?.stop() } catch (_: Exception) {}
            currentSong = null
            playbackQueue = emptyList()
            currentSongIndex = -1
            miniPlayerHost?.removeAllViews()
            miniPlayerHost?.visibility = View.GONE
        } else playbackQueue = playbackQueue.filterNot { it.id == song.id }
        refreshBody()
        toast("آهنگ از گوشی حذف شد.")
    }

    private fun copyCurrentPlaylistToAnother() {
        val sourceName = currentPlaylist
        val source = playlists[sourceName] ?: return
        val destinations = playlists.keys.filter { it != sourceName }.toTypedArray()
        if (destinations.isEmpty()) {
            toast("برای کپی، ابتدا یک پلی‌لیست دیگر بساز.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("کپی پلی‌لیست «$sourceName» به")
            .setItems(destinations) { _, which ->
                val target = playlists[destinations[which]] ?: return@setItems
                val existingIds = target.toHashSet()
                val newIds = source.filter { existingIds.add(it) }
                target.addAll(newIds)
                savePlaylists()
                toast(
                    if (newIds.isEmpty()) "تمام آهنگ‌های این پلی‌لیست از قبل در مقصد هستند."
                    else "${newIds.size} آهنگ به «${destinations[which]}» کپی شد."
                )
            }
            .show()
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

    private fun play(song: Song, openPlayerScreen: Boolean = true) {
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
        songAdapter?.notifyDataSetChanged()
        prefetchAdjacentArtwork(currentSongIndex)
        if (openPlayerScreen || playerScreenVisible) showPlayerScreen()
        startSong(song)
    }

    private fun startSong(song: Song) {
        val controller = player
        if (controller == null) {
            pendingSong = song
            return
        }

        val requestToken = ++playbackRequestToken
        val queueSnapshot = playbackQueue.ifEmpty { listOf(song) }
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

        // Start playback with only the selected track and its immediate
        // neighbours. This exposes previous/next to MediaSession straight
        // away without building the entire library on the UI thread.
        val firstIndex = (selectedIndex - 1).coerceAtLeast(0)
        val lastIndex = (selectedIndex + 1).coerceAtMost(queueSnapshot.lastIndex)
        val initialItems = (firstIndex..lastIndex).map { mediaItem(queueSnapshot[it]) }
        val initialCurrentIndex = selectedIndex - firstIndex

        try {
            if (requestToken != playbackRequestToken || player !== controller) return
            controller.setMediaItems(initialItems, initialCurrentIndex, 0L)
            controller.prepare()
            controller.play()
            updatePlayerControls()
        } catch (_: Exception) {
            toast("باز کردن فایل موسیقی ممکن نشد.")
            return
        }

        // Build the remaining queue off the main thread. Insert it around
        // the currently playing item so playback position and lock-screen
        // controls remain intact.
        if (firstIndex == 0 && lastIndex == queueSnapshot.lastIndex) return
        Thread {
            try {
                val previousItems = (0 until firstIndex)
                    .map { mediaItem(queueSnapshot[it]) }
                val nextItems = ((lastIndex + 1)..queueSnapshot.lastIndex)
                    .map { mediaItem(queueSnapshot[it]) }

                // Add the rest of the queue in small UI-thread batches.
                // A single large addMediaItems() call can block input handling
                // (including the system Back button) while the first tracks load.
                val batchSize = 10
                fun addNextBatch(start: Int) {
                    if (requestToken != playbackRequestToken || player !== controller ||
                        controller.currentMediaItem?.mediaId != song.id.toString()) return
                    try {
                        if (start >= nextItems.size) return
                        val end = (start + batchSize).coerceAtMost(nextItems.size)
                        val insertionIndex = controller.currentMediaItemIndex + 1 +
                            (start)
                        controller.addMediaItems(insertionIndex, nextItems.subList(start, end))
                        uiHandler.postDelayed({ addNextBatch(end) }, 40L)
                    } catch (_: Exception) {
                        // Keep current playback running if queue completion fails.
                    }
                }
                fun addPreviousBatch(endExclusive: Int) {
                    if (requestToken != playbackRequestToken || player !== controller ||
                        controller.currentMediaItem?.mediaId != song.id.toString()) return
                    try {
                        if (endExclusive <= 0) {
                            addNextBatch(0)
                            return
                        }
                        val start = (endExclusive - batchSize).coerceAtLeast(0)
                        controller.addMediaItems(0, previousItems.subList(start, endExclusive))
                        uiHandler.postDelayed({ addPreviousBatch(start) }, 40L)
                    } catch (_: Exception) {
                        // Keep current playback running if queue completion fails.
                    }
                }
                runOnUiThread {
                    addPreviousBatch(previousItems.size)
                }
            } catch (_: Exception) {
                // Keep current playback running if queue preparation fails.
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
            // The player timeline is populated in batches, so a library index
            // is not necessarily a valid player timeline index. Start the
            // selected song through the normal queue setup instead.
            play(playbackQueue[target], openPlayerScreen = false)
        } else {
            controller.seekToPreviousMediaItem()
        }
    }

    private fun playNextSong(manual: Boolean = false) {
        val controller = player ?: return
        if (playbackQueue.size < 2 || currentSongIndex !in playbackQueue.indices) return
        if (shuffleEnabled) {
            val target = (playbackQueue.indices.filter { it != currentSongIndex }).random()
            // Do not seek by library index: the player timeline is assembled
            // asynchronously and may currently contain only nearby tracks.
            play(playbackQueue[target], openPlayerScreen = false)
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
