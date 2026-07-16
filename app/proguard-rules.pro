# Keep nextlib FFmpeg JNI decoder classes referenced from native code.
-keep class io.github.anilbeesetti.nextlib.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# Navigation instantiates fragments by name (from nav_graph.xml) via reflection.
-keep class com.timbra.ui.**Fragment { public <init>(...); }
