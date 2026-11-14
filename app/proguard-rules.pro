# GeckoView ships its own consumer rules; keep our delegates intact.
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.**
