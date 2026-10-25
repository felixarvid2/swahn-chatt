# FamilyChat on Android: build, install, connect

This is for the person who builds the app and installs it on the family's phones. The server side is in
[RASPBERRY_PI_SETUP.md](RASPBERRY_PI_SETUP.md); how the encryption works is in [SECURITY.md](SECURITY.md).

The app needs Android 8.0 (API 26) or newer. The user interface is Swedish by default and English when the phone is
set to English (Android 13+ can also set the language per app).

## 1. Build the APK

### What you need

| | |
|---|---|
| JDK | 17 or newer (JDK 21 works). `java -version` must work in the shell that runs Gradle |
| Android SDK | Platform **android-35** and build-tools **35.0.0**. Easiest: install Android Studio, or only the command-line tools and `sdkmanager "platforms;android-35" "build-tools;35.0.0"` |
| Android NDK | `sdkmanager "ndk;27.0.12077973"`. Not needed to compile, but **without it the Android Gradle plugin cannot strip the Signal library** (`libsignal_jni.so` is 74 MB per CPU architecture with debug symbols and 7 MB without), so the APK becomes ~185 MB instead of ~30 MB. Gradle prints "Unable to strip the following libraries" when it is missing |
| Internet | The first build downloads about 1 GB of dependencies |

Tell Gradle where the SDK is (the file is git-ignored):

```bash
cd familychat/android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties        # adjust the path
```

### Debug build (for your own phone and for testing)

```bash
./gradlew assembleDebug
```

The APK is `familychat/android/app/build/outputs/apk/debug/app-debug.apk`. The debug build has the application id
`se.familychat.app.debug`, so it installs next to a release build, and it can talk to a development server over plain
`http://` on your own network (section 6). Both only exist in debug builds.

### Release build (for the family)

Release builds only talk HTTPS, are shrunk and obfuscated with R8 (rules in `app/proguard-rules.pro`), and must be
signed with **your own key**. Create it once and keep it somewhere safe and **outside the repository**. Whoever has
the key can publish updates that install over your app, and you need the same key for every future update:

```bash
keytool -genkeypair -v -keystore ~/familychat-release.jks -alias familychat \
        -keyalg RSA -keysize 4096 -validity 10000
./gradlew assembleRelease \
  -Pfamilychat.keystore=$HOME/familychat-release.jks -Pfamilychat.keystorePassword=... \
  -Pfamilychat.keyAlias=familychat -Pfamilychat.keyPassword=...
```

The result is `app/build/outputs/apk/release/app-release.apk` (about 30 MB, arm64 and x86_64 only). Without the
`-Pfamilychat.*` properties the release APK is signed with the debug key: fine to check that minification works, not
something to hand to other people. You can also put the properties in `~/.gradle/gradle.properties`.

`make apk` and `make apk-release` (in `familychat/`) wrap these commands; `make apk-release` signs when you pass
`KEYSTORE=... KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=...`.

### Releases on GitHub (signed APK from CI)

Pushing a tag that starts with `familychat-v` runs `.github/workflows/familychat-release.yml`. It builds the release
APK, signs it with your key, and creates a GitHub release with `familychat-<version>.apk` and a `.sha256` file:

```bash
git tag familychat-v1.0.0 && git push origin familychat-v1.0.0
```

One-time setup. Create the keystore as above (once; keep it and its passwords outside the repository and in a password
manager, because every future update must be signed with the same key), then store it as repository secrets
(*Settings, Secrets and variables, Actions*; or with the `gh` CLI):

```bash
base64 -w0 ~/familychat-release.jks | gh secret set FAMILYCHAT_KEYSTORE_BASE64     # macOS: base64 -i file | tr -d '\n'
gh secret set FAMILYCHAT_KEYSTORE_PASSWORD
gh secret set FAMILYCHAT_KEY_ALIAS            # familychat, the -alias used with keytool
gh secret set FAMILYCHAT_KEY_PASSWORD
```

The workflow writes the keystore to a temporary file on the runner and passes it as `-Pfamilychat.keystore`,
`-Pfamilychat.keystorePassword`, `-Pfamilychat.keyAlias` and `-Pfamilychat.keyPassword` (the properties
`app/build.gradle.kts` reads). If any of the four secrets is missing, the workflow still runs but signs with the public
debug key, names the file `familychat-<version>-DEBUG-SIGNED.apk` and marks the release as a pre-release: that file is
only for checking the pipeline. Check a download with `sha256sum -c familychat-<version>.apk.sha256`, and the signing
key with `apksigner verify --print-certs`. The GitHub release is public if the repository is public: see the note in
section 2 about the AGPL and who may get the APK. Use a private repository, or delete the release and hand out the
artifact yourself, if the family's app should not be downloadable by everybody.

The tag does not set the app's version: raise `versionCode` and `versionName` in `app/build.gradle.kts` before tagging.

Continuous integration (`familychat-android.yml`) builds, tests and lints on every push or pull request touching
`familychat/android/` or `familychat/backend/`, runs `scripts/run-e2e.sh`, and uploads the debug APK and the test
reports as artifacts. It needs no `local.properties`: the Android SDK location comes from `ANDROID_HOME`, which
`android-actions/setup-android` sets. Locally, `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) works the same instead of `local.properties`.

### Run the tests

```bash
./gradlew build                 # compiles everything, runs all unit tests and lint (about 5 minutes the first time)
./scripts/run-e2e.sh            # two real users, end-to-end encrypted, against a real backend (needs backend/.venv)
```

`:app` tests run on the JVM with Robolectric (no phone or emulator needed). The first run downloads Robolectric's
Android jar. The screenshot tests write PNGs to `app/screenshots/` (light and dark, Swedish and English; also the
conversation with the unread divider and jump-to-bottom button, the connection banner, drafts in the list, the
verified header, the list with pinned, muted and archived conversations, the privacy and security section of the settings,
the search and the lock screen). They are a review aid: look at them after a UI change. The paging test fills a database with 10,000
messages and checks pages, order and timing of the message list.
SQLCipher itself cannot be loaded on a PC (its native library is built for Android), so the database tests use plain
SQLite through the same Room code; the encryption layer has to be checked on a phone (section 8).

#### Instrumented tests (emulator or phone) and the R8 build

The JVM tests cannot load SQLCipher, use the Android Keystore or run R8, so `app/src/androidTest` holds tests that do, on a
real Android runtime: the encrypted database through `DatabaseProvider`/`PassphraseVault` (file is not plain SQLite, wrong
passphrase fails, lost Keystore key starts over), the Keystore wrapper (round trip, non-exportable, lost key), the Room
migration chain 1 -> 2 -> 3 on SQLCipher with the search index, libsignal and Tink, kotlinx.serialization and the Retrofit
API proxy, and app start (`ActivityScenario`) with the login screen (Compose).

```bash
./gradlew assembleDebugAndroidTest                         # compile only, no device needed
./gradlew connectedDebugAndroidTest                        # debug build on a connected device / running emulator
./gradlew connectedQaAndroidTest -PtestBuildType=qa        # the same tests against the R8-minified `qa` build
```

`qa` is the release build (same R8 rules, minify and resource shrinking on) with the application id suffix `.qa`, signed
with the debug key, so it can sit next to a real install. `-PtestBuildType=qa` is needed because the tests target one build
type at a time (the task `connectedQaAndroidTest` only exists with it). The qa run skips the Compose UI test (package
`se.familychat.app.ui`). The tests use their own database file and Keystore alias, never `familychat.db`, but app start
opens the real one of the `.debug` / `.qa` app, so use a device without data you care about. If a qa test fails with
`NoSuchMethodError`, `ClassNotFoundException` or a serialization error, a keep rule is missing: fix `app/proguard-rules.pro`
if the app needs it, `app/proguard-test-rules.pro` only if the test APK alone calls it (that file is only used for `qa`).
CI runs both on API 30 and 34 emulators (job `instrumented`).

#### Static analysis

```bash
./gradlew detekt                # detekt + detekt-formatting (ktlint rules), all modules; part of ./gradlew build
./gradlew detektBaseline        # re-create the baselines (only after deliberately accepting existing findings)
```

The configuration is `config/detekt/detekt.yml` (Compose conventions: `@Composable` functions may be PascalCase and are
exempt from the length limits). Findings that existed when detekt was introduced are in each module's `detekt-baseline.xml`;
new code must not add findings. Remove an entry from the baseline when you fix it.

## 2. Install on a phone

**With a cable (adb):** enable *Developer options* and *USB debugging* on the phone, connect it, and run

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` updates an existing install and keeps its data.

**Updating a phone that already has the app:** install the new APK over the old one (same signing key) and do not
uninstall first: uninstalling deletes the keys and the whole local history (see section 7). The app's local database
carries a schema version and upgrades itself on the first start of the new build, with a tested migration, without
losing messages, keys or sessions. Version 2 added the table for unsent drafts; version 3 (app 0.2.0) added the full-text search index of the messages (built from the existing messages during the upgrade) and the local pin/mute/archive settings; version 4 added the receipt times (message info) and the "delete for me" tombstones, two new tables; version 5 (app 0.4.0) added the reactions and per-conversation timer tables and the columns for expiry and "forwarded" (all additive, defaults for old rows); the history is in
`AppDatabase.MIGRATIONS` and `app/schemas/`. Whoever raises `AppDatabase.VERSION` must add a `Migration` and a case in
`MigrationTest` in the same change (a build that cannot open the old file would otherwise leave the phone without its
data).

**Without a computer (sideloading):** send `app-release.apk` to the phone by a way you trust (a cable and file copy,
your own Nextcloud, ...). Open it in the file manager. Android asks to allow *Install unknown apps* for that file
manager or browser: allow it once, install, and switch the permission off again. Updates must be signed with the same
key, otherwise Android refuses them.

Do not publish the APK anywhere public: the source licence (AGPL-3.0) requires that anybody you give the app to can
get the source, which for a family is this repository.

## 3. Connect the phone to the Pi

The app needs the server's `https://` address. There are two options, described in
[RASPBERRY_PI_SETUP.md, section 5](RASPBERRY_PI_SETUP.md#5-make-it-reachable-over-https).

**Tailscale (recommended):**

1. Install the **Tailscale** app from the Play Store or F-Droid on every phone and sign in to the same tailnet as
   the Pi. Keep it connected (Settings, *Always-on VPN* helps).
2. The address is `https://<pi-name>.<tailnet-name>.ts.net` (`tailscale serve status` on the Pi prints it).
3. In FamilyChat open the login screen, type the address in *Serveradress*, then tap *Anslutningsinställningar* and
   *Testa anslutningen*. A green "Servern svarar" with "Krypterad anslutning (TLS)" means it works. If it does not,
   the message says whether the server is unreachable (is Tailscale connected?), has no valid certificate, or
   answers with an error.

**Public domain:** use `https://chat.example.org` (whatever you set as `FAMILYCHAT_DOMAIN`). No extra app is needed.

The app refuses `http://` addresses (release builds) and servers with an invalid certificate. It never offers to "continue
anyway".

## 4. Notifications: ntfy as the UnifiedPush distributor

FamilyChat has no Google services. Without push it only fetches messages while it is open and every 15 minutes in the
background. With push the Pi sends an empty "something new" signal through ntfy and the phone fetches and decrypts the
messages itself, then shows the notification. The signal contains no sender and no text.

1. On the Pi, set up ntfy ([RASPBERRY_PI_SETUP.md, section 7](RASPBERRY_PI_SETUP.md#7-push-notifications-with-ntfy-unifiedpush)).
2. On each phone install the **ntfy** app (F-Droid or Play Store). Open it once, open *Settings* and set *Default
   server* to your `NTFY_BASE_URL`.
3. Open FamilyChat, *Inställningar*, *Pushnotiser (UnifiedPush)* and choose **ntfy**. The line then changes from
   "ansluter …" to "Aktiv via ntfy" within a few seconds. (If ntfy is the only distributor the app picks it by itself
   at login.)
4. **Battery optimisation.** Android stops background apps to save power, and a stopped ntfy means no push. Exempt both
   apps: *Settings, Apps, ntfy, Battery, Unrestricted* (the name differs by maker), and the same for FamilyChat.
   Some makers add their own "autostart" or "protected apps" switch (Xiaomi, Huawei, OnePlus, Samsung's *Sleeping
   apps*): turn the apps on there too. https://dontkillmyapp.com lists the steps for every brand.
5. Android 13 and newer asks for permission to show notifications the first time FamilyChat starts; answer *Allow*.
   It can be changed later under *Inställningar*, *Notiser för meddelanden*.

If push does not work the app still delivers within 15 minutes, and immediately while it is open.

## 5. Log in with an invitation code

Accounts exist only through invitations; there is no sign-up form on the server.

1. The first administrator is created on the Pi with
   `docker compose exec backend python -m app.cli create-invite --admin`.
2. Administrators create further invitations in the app: *Inställningar*, *Skapa inbjudan*. Choose how long it is valid
   and whether the person becomes an administrator; the code is shown **once**, with buttons to copy it or share it.
   Send it to the person in a way you trust. Every code works once.
3. The new person installs the app, enters the server address, chooses the tab *Ny med inbjudan* and fills in the code,
   a display name, a username (a to z, 0 to 9, dot, underscore, hyphen) and a password of at least 10 characters. The
   device name is pre-filled with the phone's model; it is what the others see in "Anna lade till en ny enhet".
   Instead of typing the server address and the code, tap *Skanna inbjudan* and point the camera at the QR code that
   *Skapa inbjudan* shows next to the code. The scan only **pre-fills** the invitation form (server and code); the server's
   host is shown in large type and nothing is sent until you press *Skapa konto*. Release builds accept only `https://`
   servers in the QR code (debug builds also local `http://` addresses); any code with other parameters, another version,
   or an oversized payload is refused with a message. The login tab is never pre-filled. The camera permission is asked for
   when you tap the scan button, and not otherwise.
4. Later logins use the *Logga in* tab.

## 6. Compare safety numbers (optional, but this is what makes it secure)

Open a conversation, tap the name at the top (or the menu, *Säkerhetsnummer*). Each device of the other person has a
60-digit number. Read it to each other in person or on a call; if the numbers match, nobody sits in the middle. Tap
*Markera som verifierad*. If somebody's key later changes (new phone, reinstall) the app shows a red warning in the
conversation, blocks messages to and from that device, and only continues after you press *Godkänn ny nyckel* and
confirm. Do that after comparing the new number.

Instead of reading digits you can compare QR codes: on each device card, *Visa QR-kod* shows your code and *Skanna QR-kod*
scans the other person's. A match offers *Markera som verifierad*; a mismatch is a clear warning (do not trust the device
until you have compared again). Only the camera permission is needed, no Google services.

## 7. New phone, reinstall, lost phone

- **New phone or reinstall:** install the app, log in again. This creates a *new device* with new keys. The others see
  "Anna lade till en ny enhet". Old messages are **not** transferred to the new phone: the server only holds ciphertext
  that the old device could read. This is how Signal works too. Keep the old phone until you do not need its history
  any more, then log out there (*Logga ut*) so the old device is removed from the account.
- **Lost or stolen phone:** from another device open *Inställningar*, *Enheter*, and tap the red bin next to the lost
  device. It is logged out at once and receives nothing more. Then change the password (*Byt lösenord*), which logs out
  every other device. If you have no other device, ask an administrator to revoke the device or remove the account on
  the Pi (`remove-user`). Full details: [SECURITY.md, "Device replacement and loss"](SECURITY.md#device-replacement-and-loss).
- **Wiped or restored phone:** the database key lives in the phone's Keystore and does not survive a backup restore or a
  factory reset, so the app starts empty and asks for a login. This is intended; backups are switched off
  (`allowBackup=false`) so that keys never reach cloud storage.

## 8. Development builds: plain http to your own network

Debug builds (and only debug builds) accept `http://` servers on the local network, for developing against a backend
that is not behind HTTPS, for example `uvicorn` on your PC:

- `http://192.168.1.20:8000`, `http://10.0.0.5:8000` (private networks; 172.16.x.x is deliberately not accepted),
- `http://localhost:8000`,
- `http://10.0.2.2:8000` from the Android emulator (the emulator's name for the host PC),
- `http://100.x.y.z:8000` (Tailscale addresses).

Other `http://` addresses are refused. While such a server is in use a red banner **"Osäker anslutning (endast utveckling)"**
stays on top of every screen. Android's network security configuration cannot express address ranges, so
`src/debug/res/xml/network_security_config.xml` permits cleartext for all hosts in debug builds; the address check is
done by the app itself (`BaseUrl` in `:core:network`) for everything it connects to. The release build contains neither
the permission nor the setting (`cleartextTrafficPermitted="false"`).

A first check on a real phone is worth the time, because the unit tests cannot run SQLCipher or the Keystore: log in,
send a message to a second account, force-stop the app, send another message from the second account and confirm that
the notification arrives (ntfy) and that the conversation opens with the message after a restart.

## Troubleshooting

| Symptom | Check |
|---|---|
| `SDK location not found` | `local.properties` with `sdk.dir=...` in `familychat/android` |
| `Unable to strip the following libraries` and a 185 MB APK | Install the NDK (section 1) |
| "Servern svarar inte" | Tailscale connected on the phone? `https://<address>/healthz` in the phone's browser |
| "Servern har inget giltigt certifikat" | `tailscale serve` running and HTTPS certificates enabled? Phone clock correct? |
| Login says wrong password right after a password change | Other devices are logged out by a password change; log in again |
| Messages appear only when the app is opened | Section 4: distributor chosen? battery optimisation off for ntfy and FamilyChat? |
| "Det gick inte att avkryptera meddelandet" | The message was sent to a device that did not exist yet, or its key changed. Tap *Granska* if shown; otherwise ask the sender to send it again |
| Red warning "Säkerhetsnumret … har ändrats" | Compare the number with the person (section 6) before accepting |

## App 0.2.0: search, privacy, lock, pin/mute/archive (what to know when testing on a phone)

- **Search** (magnifier in the conversation list: all conversations, results grouped by conversation; magnifier in a
  conversation: only that one). The index is an FTS4 table inside the encrypted database; nothing leaves the phone.
  Words match by their beginning, all words must occur, `å`/`ä`/`ö` are not folded to `a`/`o`.
- **Settings > Integritet och säkerhet**: notification content (*Namn och meddelande* / *Endast namn* / *Ingen information*),
  block screenshots (FLAG_SECURE, default off, immediate), app lock with a timeout (immediately, 1, 5, 30 minutes).
  The app lock needs a screen lock or biometrics on the phone (BIOMETRIC_STRONG or screen lock on Android 11+; on Android
  9 and 10 a weak-or-better biometric or the screen lock; on Android 8 the screen lock). Turning it on or off asks for the
  authentication first. While the app is locked the window is secure and nothing of the app is drawn behind the lock
  screen. Pickers and share sheets the app starts itself do not count as leaving the app (2 minutes of grace).
- **Inline reply** in the notification goes through the normal outbox. It is not offered when notification content is
  *Ingen information* (nobody is named, so a reply could go to the wrong person) or while the app lock is on (a reply would
  send without the unlock). Replying always needs the phone to be unlocked.
- **Pin / mute / archive**: long-press a conversation. Up to 5 pinned; mute for 1 hour, 8 hours, 1 week or always (no
  notification, unread count kept); archived conversations sit under *Arkiverade* and come back on a new message unless muted.
  All local to the phone, not synced between devices.

## QR codes, swipe to reply, message info, storage (what to know when testing on a phone)

- **Safety number QR / invitation QR**: see sections 5 and 6. The QR formats are `fcsn1:<base64url of libsignal's scannable
  fingerprint>` and `familychat://invite?v=1&server=<https url>&code=<code>`. Nothing registers `familychat://invite` with
  the system, so another app or a web page cannot open the app on an invitation; scanning happens inside the app.
- **Swipe to reply**: swipe a message to the right (haptic tick at the threshold); long-press menu and TalkBack's actions
  offer *Svara* as well. Vertical scrolling of the list is not affected.
- **Message info**: long-press one of your own messages, *Meddelandeinfo*. Per recipient: delivered and read, with the time
  of the receipt where this phone has seen one (messages from before version 4 show *Tid okänd*; a silent reader shows a dash).
- **Radera för mig / Radera för alla**: *för mig* removes the message (and downloaded files) from this phone only and sends
  nothing; a tombstone keeps later syncs, edits and receipts from bringing it back. *För alla* is the encrypted delete
  everyone's phone verifies (own messages only). Both ask first.
- **Inställningar > Data och lagring**: *Lagring* shows space used by downloaded media per conversation, the database and
  the cache, and clears media (one conversation or all; the messages stay and the files can be fetched again while the
  server has them) or the cache. *Automatisk nedladdning* sets Alltid / Endast Wi-Fi / Aldrig for photos, voice messages,
  video and files (defaults: photos and voice always, video and files Wi-Fi only; "Wi-Fi only" means "not a metered
  network"). Tapping an attachment always downloads it; very large files are never fetched automatically.

## App 0.4.0: reactions, disappearing messages, several images, sharing, forwarding, history file (what to know when testing)

- **Reactions**: long-press a message for a bar of six emojis and a *+* picker; one reaction per person and message, tap the same
  one again to remove it. Chips under the bubble show counts; tapping one lists who reacted. Reactions are encrypted content,
  do not count as unread and make no notification. An older app (0.1 to 0.3) shows a reaction as *could not decrypt*.
- **Försvinnande meddelanden**: *Av*, 5 min, 1 timme, 1 dag, 1 vecka or 4 veckor (conversation header; in groups only
  administrators). The change travels as an encrypted message from a member, and the server is told as well. Each new message
  carries its time; the clock starts when the receiver first sees it, then the text, files and search entries are removed from
  that phone (also after a restart). The server's own timer never deletes anything on the phone without that encrypted message.
- **Several images**: up to 10 in one message with a caption; preview strip with remove; grid in the chat; swipe in the viewer.
- **Dela till FamilyChat**: other apps can share text, photos, video, audio, PDF and files (`content://` only) to FamilyChat. Pick
  the conversation, the composer is filled in and nothing is sent until you press send. With the app lock on, unlock first.
- **Vidarebefordra**: up to 5 conversations; the copy is encrypted again with new keys and marked *Vidarebefordrat*, and uses the
  target conversation's timer.
- **Historik (Inställningar)**: *Exportera* writes a `.fchx` file, *Importera* merges one back (messages marked *Importerad*,
  never re-sent, only into conversations that already exist on this account). Format: header `FCHX`, version 1, Argon2id
  (64 MiB, 3 passes, 1 lane, random 16-byte salt) from a passphrase of at least 12 characters that is never stored, then Tink
  AES256_GCM_HKDF_1MB streaming encryption of a ZIP with the full header as associated data. No keys, tokens or sessions are
  included. Messages that are about to expire are left out. Losing the passphrase means losing the file.
  What an import accepts from a file (it is authentic, but not trusted): a header asks for at most 128 MiB and 6 passes (an
  export uses 64 MiB and 3; a phone that cannot give the memory refuses the file); what the archive *inflates* to is counted,
  per entry and in total (a ZIP bomb is stopped at the limit, not by its stored size), with at most 200,000 message lines and
  1 GiB of validated messages. A message is taken only if its sender is a member of that conversation on this phone, its
  sequence number is clamped to the conversation's last known one (so it can never mark later real messages as read or sort
  after them), and only this account's own reactions come along. Imported messages are left out of unread counts, read marks
  and notifications, and always show *Importerad*, also when they say they were forwarded. *Rensa all nedladdad media* skips
  imported media (it cannot be downloaded again); the storage screen lists it as *Importerad media*.
- **Security rules added after review**: a disappearing-messages change in a *group* is only applied when its sender is an
  administrator in the member list on this phone; an inline reply from a notification is dropped when the app lock or
  "no information" notifications were switched on after the notification was posted, and changing either setting reposts or
  removes the notifications that are up; the share target refuses `content://<user>@<own authority>/...` too; after
  disappearing messages are removed the search index is merged (`optimize`) and the database overwrites deleted pages
  (`secure_delete`), so the removed words do not stay in the file.
- Instrumented tests: `HistoryAndContentInstrumentedTest` runs the production Argon2id/Tink parameters on the device; its R8
  keep rules are in `app/proguard-test-rules.pro`.
