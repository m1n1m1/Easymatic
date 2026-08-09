# Not wired into the build yet, on purpose.
#
# `app/build.gradle.kts` sets `release { optimization { enable = false } }`, so
# nothing is shrunk and no keep rule is needed today. This file is what to add a
# `proguardFiles(...)` line for the day that flag flips — written now because the
# failure it prevents shows up only in a release build, long after the change that
# caused it, and names nothing useful when it does.

# JavaMail instantiates IMAPProvider, SMTPProvider and friends by class name, read
# out of META-INF/javamail.providers. R8 sees no reference to any of them and
# strips the lot; the symptom is `NoSuchProviderException: smtp` from inside the
# Mail facade, which reads exactly like a misconfigured account.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keepattributes *Annotation*

# Desktop code paths that never run on Android.
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn com.sun.activation.**
