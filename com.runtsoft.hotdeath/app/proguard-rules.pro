# Add project specific ProGuard rules here.
# The legacy rules that used to live in ../proguard.cfg have been folded in;
# see that file if you need to compare.

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

-keepclasseswithmembers class * {
    native <methods>;
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Card, Hand, Player, Game, Penalty and friends are deserialized from JSON by
# key rather than reflection, but the classes are reached from JNI-free code
# only through their declared types -- keep the model intact for readability of
# stack traces in release builds.
-keep class com.runtsoft.hotdeath.** { *; }
