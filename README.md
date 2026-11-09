# MoveMusic

A free, offline Android music-player with a one-action **Move to playlist** operation.

## Included
- Reads local audio using Android MediaStore.
- Creates and persists app-managed playlists.
- In a playlist, it adds the selected track to the destination playlist and removes it from the source in the same operation.
- Keeps the original audio file untouched.
- Electric-blue on deep-black visual theme.
- Background playback managed by an AndroidX Media3 `MediaSessionService` and ExoPlayer.
- A media-session notification while playback is active, with system-managed playback controls.
- Audio-focus handling and automatic pause when headphones or Bluetooth audio devices are disconnected.

## Build
Open this folder in Android Studio (Giraffe or newer), allow Gradle sync, then choose **Build > Build APK(s)**.

Minimum Android: 8.0 (API 26). The project targets API 35.
