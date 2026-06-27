# SixthSense ProGuard rules (release). Starter project does not minify, but keep
# Gson model classes and the WebSocket library safe if minification is enabled later.
-keep class com.sixthsense.core.** { *; }
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**
