# Add project specific ProGuard rules here.

# Keep TTLock API model classes used with Gson — R8 must not rename or remove fields
-keep class com.mylock.app.ttlock.TtlockTokenResponse { *; }
-keep class com.mylock.app.ttlock.TtlockLock { *; }
-keep class com.mylock.app.ttlock.TtlockLockListResponse { *; }
-keep class com.mylock.app.ttlock.TtlockCommandResponse { *; }
-keepattributes Signature
-keepattributes *Annotation*
