# Add project specific ProGuard rules here.
# Keep Room Entities and Data Models
-keep class com.jovoc.facecampresentationrecorder.db.** { *; }
-keep class com.jovoc.facecampresentationrecorder.model.** { *; }

-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }

# Sign-in storage: EncryptedSharedPreferences -> Tink -> protobuf-lite reads message fields by reflection.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
# OkHttp 4, Room and WorkManager ship their own consumer rules; Room entities are kept via the db.** rule above.
