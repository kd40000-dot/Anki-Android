# Andor's Trail ↔ AnkiDroid integration contract

This combat integration targets the custom AnkiDroid Retry provider:

- package: `com.ichi2.anki.retry`
- provider authority: `com.ichi2.anki.retry.flashcards`
- allowed Andor's Trail package: `com.gpl.rpg.AndorsTrail.dev`

## Permission model

Do **not** depend on Android granting
`com.ichi2.anki.retry.permission.READ_WRITE_DATABASE` to Andor's Trail.

The compatible AnkiDroid Retry build identifies the caller by Binder UID and allows
provider access only when that UID belongs to `com.gpl.rpg.AndorsTrail.dev`.
This is the canonical permission path for all future edits.

Andor's Trail should query the provider directly and fail open to normal combat if
provider access is unavailable. Do not reintroduce runtime permission dialogs or
`requestPermissions()` for the Anki provider permission.

## Build baselines

Use these branches as the maintained pair:

- AnkiDroid provider: `chatgpt/typed-retry-andors-provider`
- Andor's Trail integration: `chatgpt/andors-trail-anki-resilient`

Future integration edits should preserve the package/authority values above.
