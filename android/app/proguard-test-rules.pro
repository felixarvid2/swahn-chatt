# Extra R8 rules for the qa build only (the build type the instrumented tests run against; never part of release).
# The test APK calls into the minified app by name, and R8 removes everything the app itself does not use. This file keeps
# just the API surface the tests touch. It deliberately does NOT repeat anything from proguard-rules.pro: a missing rule for
# libsignal, Tink, SQLCipher, Room, Retrofit or kotlinx.serialization has to fail a test. (Names may still be obfuscated:
# AGP applies the app's mapping to the test APK.)

# :core:crypto API (the app uses most of it; the tests also use the in-memory storage and a few more members)
-keep,allowobfuscation class se.swahnchat.core.crypto.** { public *; }

# :core:network
-keep,allowobfuscation class se.swahnchat.core.network.InMemoryTokenProvider { *; }
-keep,allowobfuscation class se.swahnchat.core.network.ApiClient { public *; }
-keep,allowobfuscation class se.swahnchat.core.network.ApiClient$Companion { *; }
-keep,allowobfuscation interface se.swahnchat.core.network.SwahnChatApi { *; }

# :core:model: construction and property access only. Companion / serializer() are NOT listed: the app rules must keep them.
-keep,allowobfuscation class se.swahnchat.core.model.** { <init>(...); }
-keepclassmembers,allowobfuscation class se.swahnchat.core.model.** { *** get*(); *** component*(); *** copy(...); boolean is*(); }

# :core:client types the tests build and read through the app's Room store (MigrationInstrumentedTest). RoomLocalStore also needs its
# constructor with default arguments (the synthetic <init>(..., int, DefaultConstructorMarker) the test's `RoomLocalStore(db)` calls).
-keep,allowobfuscation interface se.swahnchat.core.client.LocalStore { *; }
-keep,allowobfuscation class se.swahnchat.core.client.StoredMessage { *; }
-keep,allowobfuscation class se.swahnchat.core.client.Reaction { *; }
-keep,allowobfuscation class se.swahnchat.core.client.ConversationTimer { *; }
-keep,allowobfuscation class se.swahnchat.app.data.db.RoomLocalStore { *; }

# App code the tests drive directly
-keep,allowobfuscation class se.swahnchat.app.data.security.** { *; }
-keep,allowobfuscation class se.swahnchat.app.data.db.AppDatabase { *; }
-keep,allowobfuscation class se.swahnchat.app.data.db.AppDatabase$Companion { *; }
-keep,allowobfuscation class se.swahnchat.app.data.db.MessageEntity { *; }
-keep,allowobfuscation class se.swahnchat.app.data.db.DraftEntity { *; }
-keep,allowobfuscation class se.swahnchat.app.data.db.MessageReceiptEntity { *; }
-keep,allowobfuscation class se.swahnchat.app.data.db.LocalDeletionEntity { *; }
-keep,allowobfuscation class se.swahnchat.app.data.db.StoreDao { *; }
-keep,allowobfuscation class se.swahnchat.app.data.SearchRepository { *; }
-keep,allowobfuscation class se.swahnchat.app.data.SearchHit { *; }

# QR helpers: the test decodes the bitmaps it generates (the app only generates them and reads the camera)
-keep,allowobfuscation class se.swahnchat.app.qr.** { *; }

# Libraries the test APK shares with the app. AGP leaves a library out of the test APK when the app has it, so the tests call the
# app's (shrunk) copy: without these rules a test fails with NoSuchMethodError on e.g. listOf() or runBlocking().
-keep,allowobfuscation class kotlin.** { *; }
-keep,allowobfuscation class kotlinx.coroutines.** { *; }
-keep,allowobfuscation class kotlinx.serialization.* { *; }
-keep,allowobfuscation class okhttp3.** { *; }
-keep,allowobfuscation class okio.** { *; }

# The androidx.test runner itself and room-testing (MigrationTestHelper) also use libraries the app ships: without these
# R8 drops androidx.tracing.Trace, the runner dies with NoClassDefFoundError before the first test and the run hangs.
-keep class androidx.tracing.** { *; }
-keep class androidx.arch.core.** { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.core.os.** { *; }
-keep class androidx.room.** { *; }
-keep class androidx.sqlite.** { *; }
-keep,allowobfuscation class kotlinx.serialization.** { *; }

# History file format and the encrypted content codec (HistoryAndContentInstrumentedTest)
-keep,allowobfuscation class se.swahnchat.app.data.history.HistoryFormat { *; }
-keep,allowobfuscation class se.swahnchat.app.data.history.HistoryFormat$Header { *; }
-keep,allowobfuscation class se.swahnchat.app.data.history.KdfParams { *; }
-keep,allowobfuscation class se.swahnchat.app.data.history.HistoryException** { *; }
-keep,allowobfuscation class se.swahnchat.core.model.ContentCodec { *; }
-keep,allowobfuscation class se.swahnchat.core.model.Padding { *; }
-keep,allowobfuscation class se.swahnchat.core.model.ContentType { *; }
-keep,allowobfuscation class se.swahnchat.core.model.SwahnChatJson { *; }

# Calls: WebRtcLoopbackTest builds the real factory and drives the media session interface from :core:client. org.webrtc itself is
# kept by the app rules (WebRTC section of proguard-rules.pro), so a missing rule there fails the test.
-keep,allowobfuscation class se.swahnchat.app.call.WebRtcMediaSessionFactory { <init>(...); }
-keep,allowobfuscation class se.swahnchat.core.client.call.** { *; }
