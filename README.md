# MoveMusic (prototype)

A free, offline Android music-player starter project with a one-action **Move to playlist** operation.

## Included
- Reads local audio using Android MediaStore.
- Creates and persists app-managed playlists.
- In a playlist, `انتقال به…` adds the selected track to the destination playlist and removes it from the source in the same operation.
- Keeps the original audio file untouched.
- Electric-blue on deep-black visual theme.

## Build
Open this folder in Android Studio (Giraffe or newer), allow Gradle sync, then choose **Build > Build APK(s)**.

Minimum Android: 8.0 (API 26). The project targets API 35.

## Important prototype limitation
Playlists are managed inside MoveMusic and do not modify playlists created by other music apps. This first prototype has basic playback and playlist management; background playback, notification/media controls, artwork, queue management, and robust error handling are not included yet.
