# PocketCode Studio ProGuard 规则

# 保留 JNI 方法与对应 native 声明
-keepclasseswithmembernames class com.pocketcode.studio.core.terminal.TerminalService {
    native <methods>;
}

# QuickJS 通过反射调用宿主对象方法，需保留宿主类成员名
-keepclassmembers class com.pocketcode.studio.core.plugin.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.pocketcode.studio.core.build.BuildRunService$RunConfig { *; }
-keepclassmembers class com.pocketcode.studio.core.plugin.PluginManager$Manifest { *; }
-keepclassmembers class com.pocketcode.studio.core.plugin.PluginManager$Contributes { *; }

# libsu
-keep class com.topjohnwu.superuser.** { *; }
