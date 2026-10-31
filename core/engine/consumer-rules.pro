# The engine's JNI layer (gomobile) finds these classes and members by name.
-keep class go.** { *; }
-keep class app.proxyflow.engine.go.** { *; }
-keep class * implements app.proxyflow.engine.go.pfengine.Platform { *; }
