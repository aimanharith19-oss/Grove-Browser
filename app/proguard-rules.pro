# GeckoView ships its own consumer rules; keep our delegates intact.
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.**
# ViewModels are created by reflection: keep their constructors.
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class app.grove.browser.GroveApp { *; }
# Gecko reads its privacy prefs file with SnakeYAML (uses reflection).
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
