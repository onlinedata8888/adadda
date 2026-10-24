-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Libraries lean on reflection heavily - keep them whole
-keep class org.jf.dexlib2.**        { *; }
-keep class com.android.apksig.**    { *; }
-keep class org.bouncycastle.**      { *; }
-keep class com.google.common.**    { *; }

-keep class ai.venice.adstripper.MainActivity { *; }

-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn javax.xml.**
-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn sun.misc.**
-dontwarn org.w3c.dom.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn com.google.common.**
-dontwarn org.jf.dexlib2.**
-dontwarn com.android.apksig.**
-dontwarn org.bouncycastle.**
