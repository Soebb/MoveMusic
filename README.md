# MoveMusic (prototype)

A free, offline Android music-player starter project with a one-action **Move to playlist** operation.

## Included
- Reads local audio using Android MediaStore.
- Creates and persists app-managed playlists.
- In a playlist, `انتقال به…` adds the selected track to the destination playlist and removes it from the source in the same operation.
- Keeps the original audio file untouched.
- Electric-blue on deep-black visual theme.
- Background playback managed by an AndroidX Media3 `MediaSessionService` and ExoPlayer.
- A media-session notification while playback is active, with system-managed playback controls.
- Audio-focus handling and automatic pause when headphones or Bluetooth audio devices are disconnected.

## Build
Open this folder in Android Studio (Giraffe or newer), allow Gradle sync, then choose **Build > Build APK(s)**.

Minimum Android: 8.0 (API 26). The project targets API 35.

## Important prototype limitation
Playlists are managed inside MoveMusic and do not modify playlists created by other music apps. Playback now runs in a foreground media service, so it can continue while the app is in the background or the screen is off. The media session provides the system media notification. Advanced queue editing, richer notification actions (such as app-managed next/previous controls), and comprehensive recovery from all playback errors remain future work.
