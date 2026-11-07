package com.movemusic.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)

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
    private var player: MediaPlayer? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPlaylists()
        showPermissionOrLoad()
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
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        loadedSongs.add(
                            Song(
                                id,
                                cursor.getString(titleCol) ?: "بدون عنوان",
                                cursor.getString(artistCol) ?: "هنرمند نامشخص",
                                ContentUris.withAppendedId(collection, id)
                            )
                        )
                    }
                }
                runOnUiThread {
                    songs.clear()
                    songs.addAll(loadedSongs)
                    displayedSongCount = 50
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
        root.addView(nav)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (currentSong != null) {
            root.addView(buildMiniPlayerBar(), LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(label("برای انتقال، آهنگ را انتخاب کن و «انتقال به…» را بزن.", 10, muted))
        setContentView(root)
        refreshBody()
    }

    private fun refreshBody() {
        body.removeAllViews()
        body.addView(label(currentPlaylist, 18, white))
        val visible = if (currentPlaylist == "همه آهنگ‌ها") songs
            else songs.filter { it.id in (playlists[currentPlaylist] ?: mutableListOf()) }
        if (currentPlaylist != "همه آهنگ‌ها") {
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
        if (visible.isEmpty()) body.addView(label("اینجا هنوز آهنگی نیست.", 13, muted))
        body.addView(button(if (shuffleEnabled) "پخش تصادفی: روشن" else "پخش تصادفی: خاموش") {
            shuffleEnabled = !shuffleEnabled
            refreshBody()
            toast(if (shuffleEnabled) "پخش تصادفی فعال شد." else "پخش تصادفی غیرفعال شد.")
        })
        val page = visible.take(displayedSongCount)
        page.forEach { song ->
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
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = 3
            body.addView(row, lp)
        }
        if (visible.size > page.size) {
            body.addView(button("نمایش ۵۰ آهنگ بعدی") {
                displayedSongCount += 50
                refreshBody()
            })
        }
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
            songs.toList()
        } else {
            songs.filter { it.id in (playlists[currentPlaylist] ?: mutableListOf()) }
        }
        currentSongIndex = playbackQueue.indexOfFirst { it.id == song.id }
        currentSong = song
        showPlayerScreen()
        startSong(song)
    }

    private fun startSong(song: Song) {
        try {
            // Detach callbacks before releasing the previous player so a late
            // error from the old track cannot show a misleading message.
            player?.setOnPreparedListener(null)
            player?.setOnCompletionListener(null)
            player?.setOnErrorListener(null)
            player?.release()

            val newPlayer = MediaPlayer()
            player = newPlayer
            newPlayer.setOnPreparedListener { mp ->
                if (player !== mp) return@setOnPreparedListener
                try {
                    mp.start()
                    updatePlayerControls()
                } catch (_: Exception) {
                    if (player === mp) toast("پخش این فایل ممکن نیست.")
                }
            }
            newPlayer.setOnCompletionListener { mp ->
                // Auto-advance only in shuffle mode. In normal mode, leave
                // playback stopped at the end of the current track.
                if (player === mp && shuffleEnabled) playNextSong()
            }
            newPlayer.setOnErrorListener { mp, _, _ ->
                if (player === mp && currentSong?.id == song.id) {
                    toast("پخش این فایل ممکن نیست.")
                }
                true
            }
            newPlayer.setDataSource(this, song.uri)
            newPlayer.prepareAsync()
        } catch (_: Exception) {
            toast("باز کردن فایل موسیقی ممکن نشد.")
        }
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
                    if (p.isPlaying || p.currentPosition >= 0) {
                        val duration = p.duration
                        if (duration > 0) p.seekTo(duration * progress / 1000)
                    }
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

    private fun loadSongArtwork(song: Song, target: ImageView) {
        Thread {
            var bitmap: android.graphics.Bitmap? = null
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, song.uri)
                val bytes = retriever.embeddedPicture
                if (bytes != null) bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                retriever.release()
            } catch (_: Exception) {}
            runOnUiThread {
                if (playerScreenVisible && currentSong?.id == song.id && bitmap != null) {
                    target.setImageBitmap(bitmap)
                } else if (bitmap == null && playerScreenVisible && currentSong?.id == song.id) {
                    target.setImageResource(android.R.drawable.ic_media_play)
                    target.scaleType = ImageView.ScaleType.CENTER
                }
            }
        }.start()
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
        val icon = if (player?.isPlaying == true) android.R.drawable.ic_media_pause
                   else android.R.drawable.ic_media_play
        playPauseButton?.setImageResource(icon)
        miniPlayPauseButton?.setImageResource(icon)
    }

    private fun togglePlayback() {
        val p = player ?: return
        try {
            if (p.isPlaying) p.pause() else p.start()
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
                    if (p != null && p.isPlaying && p.duration > 0) {
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
        if (playbackQueue.isEmpty() || currentSongIndex !in playbackQueue.indices) return
        currentSongIndex = if (currentSongIndex <= 0) playbackQueue.lastIndex else currentSongIndex - 1
        currentSong = playbackQueue[currentSongIndex]
        if (playerScreenVisible) showPlayerScreen() else render()
        startSong(playbackQueue[currentSongIndex])
    }

    private fun playNextSong(manual: Boolean = false) {
        if (playbackQueue.size < 2 || currentSongIndex !in playbackQueue.indices) return
        currentSongIndex = if (shuffleEnabled) {
            (playbackQueue.indices.filter { it != currentSongIndex }).random()
        } else {
            (currentSongIndex + 1) % playbackQueue.size
        }
        currentSong = playbackQueue[currentSongIndex]
        if (playerScreenVisible) showPlayerScreen() else render()
        startSong(playbackQueue[currentSongIndex])
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (playerScreenVisible) {
            playerScreenVisible = false
            seekBarTask?.let { uiHandler.removeCallbacks(it) }
            seekBarTask = null
            playPauseButton = null
            miniPlayPauseButton = null
            render()
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
    override fun onDestroy() { uiHandler.removeCallbacksAndMessages(null); player?.release(); player = null; super.onDestroy() }
}
