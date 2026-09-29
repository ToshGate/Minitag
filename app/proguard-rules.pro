# O jaudiotagger instancia corpos de frames ID3 por reflexão (Class.forName).
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
# Classes de desktop referenciadas pelo jaudiotagger que não existem no Android.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
