package com.movemusic.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
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
    private var player: MediaPlayer? = null
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
            loadSongs()
            render()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 10 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            loadSongs(); render()
        } else {
            setContentView(label("برای نمایش آهنگ‌های گوشی، مجوز دسترسی به فایل‌های صوتی لازم است.", 18))
        }
    }

    private fun loadSongs() {
        songs.clear()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.IS_MUSIC)
        contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                songs.add(Song(id, c.getString(titleCol) ?: "بدون عنوان",
                    c.getString(artistCol) ?: "هنرمند نامشخص",
                    ContentUris.withAppendedId(collection, id)))
            }
        }
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
            setPadding(18, 24, 18, 12)
        }
        header = label("MOVEMUSIC  ✦", 24, blue)
        root.addView(header)
        root.addView(label("موسیقی آفلاین، مدیریت ساده", 13, muted))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(button("همه آهنگ‌ها") { currentPlaylist = "همه آهنگ‌ها"; refreshBody() })
        nav.addView(button("پلی‌لیست‌ها") { showPlaylistChooser() })
        nav.addView(button("+ جدید") { createPlaylist() })
        root.addView(nav)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("برای انتقال، آهنگ را انتخاب کن و «انتقال به…» را بزن.", 12, muted))
        setContentView(root)
        refreshBody()
    }

    private fun refreshBody() {
        body.removeAllViews()
        body.addView(label(currentPlaylist, 20, white))
        val visible = if (currentPlaylist == "همه آهنگ‌ها") songs
            else songs.filter { it.id in (playlists[currentPlaylist] ?: mutableListOf()) }
        if (visible.isEmpty()) body.addView(label("اینجا هنوز آهنگی نیست.", 14, muted))
        visible.forEach { song ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(12, 10, 12, 10)
                setBackgroundColor(panel)
            }
            card.addView(label(song.title, 16, white))
            card.addView(label(song.artist, 12, muted))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button("▶ پخش") { play(song) })
            if (currentPlaylist != "همه آهنگ‌ها") {
                actions.addView(button("انتقال به…") { moveSong(song) })
                actions.addView(button("حذف از لیست") {
                    playlists[currentPlaylist]?.remove(song.id); savePlaylists(); refreshBody()
                })
            } else {
                actions.addView(button("افزودن به…") { addSong(song) })
            }
            card.addView(actions)
            val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 8
            body.addView(card, lp)
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
            currentPlaylist = names[i]; refreshBody()
        }.show()
    }

    private fun createPlaylist() {
        val input = EditText(this).apply { hint = "نام پلی‌لیست" }
        AlertDialog.Builder(this).setTitle("پلی‌لیست جدید").setView(input)
            .setNegativeButton("لغو", null).setPositiveButton("ساختن") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != "همه آهنگ‌ها" && !playlists.containsKey(name)) {
                    playlists[name] = mutableListOf(); savePlaylists()
                    currentPlaylist = name; refreshBody()
                } else toast("این نام قابل استفاده نیست.")
            }.show()
    }

    private fun play(song: Song) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, song.uri)
                setOnPreparedListener { it.start(); toast("در حال پخش: ${song.title}") }
                setOnErrorListener { _, _, _ -> toast("پخش این فایل ممکن نیست."); true }
                prepareAsync()
            }
        } catch (_: Exception) { toast("خطا در پخش آهنگ.") }
    }

    private fun label(text: String, size: Int, color: Int = white) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        setPadding(4, 8, 4, 8)
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 12f; setTextColor(white)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(blue))
        setOnClickListener { action() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { player?.release(); super.onDestroy() }
}
