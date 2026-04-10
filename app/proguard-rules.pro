
# Keep Android entry points to avoid component class-name stripping in release.
-keep class com.whut.autologin.MainActivity { *; }
-keep class com.whut.autologin.service.AutoLoginService { *; }
-keep class com.whut.autologin.service.BootReceiver { *; }

# Keep line numbers for crash readability.
-keepattributes SourceFile,LineNumberTable
