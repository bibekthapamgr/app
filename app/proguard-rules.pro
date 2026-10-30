# ProxyFlow release shrinking rules.
#
# AndroidX, Compose, Navigation and kotlinx.serialization ship their own
# consumer rules (including the serializers of the @Serializable navigation
# routes), so nothing extra is needed in M2. Keep this file short: every rule
# here must say why it exists.

# Readable crash traces from Play Console (with the uploaded mapping file).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
