# Anki Leda Audio (Android)
Stand-alone Android APKG audio updater based on the previously used `generate_chirp3_audio.py` voice and output workflow.

- Android Storage Access Framework: open an AnkiDroid export `.apkg`, optionally import an existing Leda MP3 ZIP, and save a NEW `.apkg` elsewhere. Does **not** modify AnkiDroid's live collection.
- Source exports in modern `collection.anki21b` (Zstandard) / protobuf media manifest format. Uses Android SQLite for note-only edits, and zstd-jni Android AAR for Zstandard encoding.
- Modes: repair missing/broken audio only, or replace legacy audio with Leda (skip already-labelled Leda recordings). Deduplicates requests by Vietnamese text, caches MP3s for resumable runs.
- User may import a Google ADC `authorized_user` JSON (`gcloud auth application-default login`) or a service-account JSON, and enter a Google Cloud quota/billing project. Credentials remain in RAM, never included in exported packages or stored in application preferences. Uses HTTPS Google Cloud Text-to-Speech API.
- Voice: `vi-VN-Chirp3-HD-Leda`, output `MP3`. API usage may incur costs. Offline existing MP3 cache is used first.
- Note field edits only: Anki note IDs, GUIDs, card records, revlog and deck metadata retained; original media preserved, newly synthesised media appended, source APKG never overwritten.
- Keep the app open during long jobs. It keeps the screen awake; cached clips allow resuming by repeating the export.
- On Android, tap Import credentials to select the local ADC JSON created on your computer. NEVER share that file publicly. You can also import an MP3 ZIP to avoid synthesis for available clips.
