# Rules for the release (R8) build.
#
# Everything the app references directly is handled by R8 on its own; these are
# the parts reached *reflectively*, which R8 cannot see and would otherwise
# rename or delete.

# ---------------------------------------------------------------- kotlinx.serialization
#
# The plugin generates a `$$serializer` object per @Serializable class and looks
# it up by name through the class's companion. Renaming either half breaks
# decoding at runtime -- and every DTO here decodes a server reply, so the
# failure would be "the whole app shows no data".
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.vrcx0.android.**$$serializer { *; }
-keepclassmembers class com.vrcx0.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.vrcx0.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------- diagnostics
#
# The app deliberately logs a warning on every swallowed command/image/world
# failure -- that is the only way several of those bugs were ever found. An
# `-assumenosideeffects` block for Log would delete exactly those call sites.
-keepclassmembers class android.util.Log {
    public static *** w(...);
    public static *** e(...);
}
