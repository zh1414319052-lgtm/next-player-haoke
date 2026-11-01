# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn org.xmlpull.v1.**
-dontnote org.xmlpull.v1.**

# SMBJ ProGuard Rules
# Keep SMBJ classes
-keep class com.hierynomus.smbj.** { *; }
-keep class com.hierynomus.mssmb2.** { *; }
-keep class com.hierynomus.msdtyp.** { *; }
-keep class com.hierynomus.msfscc.** { *; }
-keep class com.hierynomus.protocol.** { *; }
-keep class com.hierynomus.spnego.** { *; }
-keep class com.hierynomus.ntlm.** { *; }
-keep class com.hierynomus.security.** { *; }

# Bouncy Castle registers JCA implementations by class name. SFTP uses an
# app-specific provider name so Android's stripped platform BC is untouched.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# mbassador creates the default handler invocation through Class.getConstructor().
-keepclassmembers,allowobfuscation class net.engio.mbassy.dispatch.ReflectiveHandlerInvocation {
    public <init>(net.engio.mbassy.subscription.SubscriptionContext);
}

# Optional runtime dependencies of the network libraries (smbj, mbassador,
# okhttp/bouncycastle) that are not present on Android. Safe to ignore.
-dontwarn javax.el.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.ietf.jgss.**
-dontwarn org.openjsse.**
-dontwarn java.rmi.UnmarshalException

# EdDSA-Java has an optional Sun JDK key adapter, absent on Android.
# The update validator constructs EdDSAPublicKey directly from the pinned raw key.
-dontwarn sun.security.x509.X509Key

# Public integration surfaces exercised from the separately signed device-test APK.
# Keep their callable shape so tests run against the exact distributable, R8-shrunk APK.
-keep class androidx.tracing.** { public *; }
-keep class dev.anilbeesetti.nextplayer.updates.** { public *; }
-keep class dev.anilbeesetti.nextplayer.feature.player.interpolation.** { public *; }
-keep class dev.anilbeesetti.nextplayer.core.common.PrimaryLanguage { public *; }
-keep class dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository { public *; }
-keep class dev.anilbeesetti.nextplayer.core.model.PlayerPreferences { public *; }
-keep class dev.anilbeesetti.nextplayer.feature.player.service.CustomCommands { public *; }
-keep class org.koin.core.context.GlobalContext { public *; }
-keep class org.koin.core.Koin { public *; }
-keep class androidx.media3.common.util.GlUtil { public *; }
-keep class androidx.media3.common.GlTextureInfo { public *; }
-keep class androidx.media3.effect.DefaultGlObjectsProvider { public *; }
-keep class androidx.media3.effect.GlShaderProgram** { public *; }
# Android instrumentation shares these libraries with the target process. Retain
# their complete public API, including Kotlin facade classes that R8 otherwise
# inlines away; mapping names alone cannot restore a removed runtime entry point.
-keep class kotlin.** { public protected *; }
-keep class kotlinx.coroutines.** { public protected *; }
-keep class androidx.media3.** { public protected *; }
-keep class org.koin.core.** { public protected *; }
-keep class com.google.common.util.concurrent.** { public protected *; }
