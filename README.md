# APK-parser , revived

Apk parser for java/Android, forked from [here]([url](https://github.com/hsiafan/apk-parser)) after fixing some issues in it and collecting some fixes from others.

I personally use it for my own spare time app, "[App Manager]([url](https://play.google.com/store/apps/details?id=com.lb.app_manager))".

Screenshot from the sample:

![image](https://user-images.githubusercontent.com/5357526/175833504-6cba993c-60b5-418c-8407-b6f1688a0348.png)

# Why use this library, as we can do it using the Android framework instead?

While the Android framework is more official and should work better in most cases, there are multiple reasons to use this library :

1. Can handle APK files that are not on the file system. The Android framework requires you to use a file path, always.
2. Can handle split APK files too. The Android framework can only handle the base ones or stand-alone files.
3. Can find some APK properties that aren't available on older Android versions.
4. While the Android framework is technically open sourced, it has various things that are protected and can't be reached, and also hard to import as your own code.

So, what I suggest is to first try to use what Android officially offers, and if it fails, use this library.

# Usage in gradle file

https://jitpack.io/#AndroidDeveloperLB/apk-parser/

# Known issues and notes

- The sample app shows that in some rare cases it fails to parse the label/icon of the app, and even completely (incredibly rare). It seems to occur only for system apps though. I hope that some day it could be fixed. Reported here: https://github.com/AndroidDeveloperLB/apk-parser/issues/3 https://github.com/AndroidDeveloperLB/apk-parser/issues/4 https://github.com/AndroidDeveloperLB/apk-parser/issues/1
- The sample shows how to parse a VectorDrawable, but of course it will work only when it's simple enough. If there are references within (to colors etc...), sadly it won't work. I hope it will be possible one day to parse it properly. Same goes for AdaptiveIcon and what it can have.
- The entire code is in Java. I personally prefer Kotlin. I hope one day the whole library would be in Kotlin. At the very least, we should have a clear understanding for everything, if it's nullable or not. This needs to be carefully done and without ruining the performance and memory usage of the library.
- Could be nice to have better optimization in memory usage and speed, because somehow the framework seems to be more efficient on both. I think a better optimization is needed. Maybe some sort of way to tell exactly what we want to get out of it, it would minimize such memory usage.
