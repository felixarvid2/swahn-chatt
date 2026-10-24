# R8 rules for the release build (minify + resource shrinking). Library authors' consumer rules (OkHttp, Retrofit, Room,
# Hilt/Dagger, WorkManager, Media3, Coil, Compose, kotlinx.serialization) are applied automatically; what follows covers what
# those cannot know: code that native libraries or reflection reach by name.

# --- libsignal ---------------------------------------------------------------------------------------------------------
# The Rust core calls back into Java by class and method name through JNI (store interfaces, exceptions, the Native
# class, the rustls platform verifier). Renaming or removing any of it breaks decryption at runtime, not at build time.
-keep class org.signal.libsignal.** { *; }
-keep class org.rustls.platformverifier.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.signal.libsignal.**

# --- SQLCipher (JNI) -----------------------------------------------------------------------------------------------------
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# --- Tink (streaming AEAD for attachments) ---------------------------------------------------------------------------------
# Tink's key protos are protobuf-lite messages whose fields are read reflectively.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

# --- kotlinx.serialization -------------------------------------------------------------------------------------------------
# API bodies, WebSocket frames and the encrypted message content are (de)serialised through generated serializers that are
# looked up via the companion object. Keep them for the models in :core:model and the app's own JSON rows.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class se.familychat.core.model.**$$serializer { *; }
-keepclassmembers class se.familychat.core.model.** { *** Companion; }
-keepclasseswithmembers class se.familychat.core.model.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class se.familychat.app.**$$serializer { *; }
-keepclassmembers class se.familychat.app.** { *** Companion; }
-keepclasseswithmembers class se.familychat.app.** { kotlinx.serialization.KSerializer serializer(...); }

# --- Retrofit --------------------------------------------------------------------------------------------------------------
# The API interface is implemented with a dynamic proxy and read through its annotations and generic signatures, including
# the Continuation parameter of suspend functions.
-keep interface se.familychat.core.network.RawApi { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- UnifiedPush -------------------------------------------------------------------------------------------------------------
# The distributor binds to the push service by its class name from the manifest, and the connector parcels these classes.
-keep class org.unifiedpush.android.connector.** { *; }
-keep class se.familychat.app.platform.push.FamilyChatPushService { *; }

# --- crash reports -------------------------------------------------------------------------------------------------------------
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
