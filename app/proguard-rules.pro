# Bundled manifests and SparkPlug imports are decoded with kotlinx.serialization. The
# library ships consumer rules for generated serializers; these keep the model classes
# and their Companion serializer lookups intact for the shared core-model module.
-keepclassmembers class net.metalstrapper.sparkwriter.** {
    *** Companion;
}
-keepclasseswithmembers class net.metalstrapper.sparkwriter.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# AppFunctions are discovered through KSP-generated metadata and invoked by name.
-keep class net.metalstrapper.sparkwriter.agent.** { *; }

# Keep line numbers so Play Console stack traces map back cleanly with the mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
