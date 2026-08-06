# Keep nextlib FFmpeg JNI decoder classes referenced from native code.
-keep class io.github.anilbeesetti.nextlib.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# Navigation instantiates fragments by name (from nav_graph.xml) via reflection.
# Keyed on the TYPE, not the class name: a name pattern silently depended on every navigable
# fragment living under com.timbra.ui and ending in "Fragment", and the failure mode is a
# release-only ClassNotFoundException (debug has minification off, so it can never surface there).
-keep class * extends androidx.fragment.app.Fragment { public <init>(...); }
