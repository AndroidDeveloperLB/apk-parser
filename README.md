# apk-parser

Apk parser for java/Android, forked from here:

https://github.com/hsiafan/apk-parser

# Why use this library, as we can do it using the Android framework instead?

While the Android framework is more official and should work better in most cases, there are multiple reasons to use this library :

1. Can handle APK files that are not on the file system. The Android framework requires you to use a file path, always.
2. Can handle split APK files too. The Android framework can only handle the base ones or stand-alone files.
3. Can find some APK properties that aren't available on older Android versions.
4. While the Android framework is technically open sourced, it has various things that are protected and can't be reached, and also hard to import as your own code.

# Usage in gradle file

https://jitpack.io/#AndroidDeveloperLB/apk-parser/

# Known issues and notes

- The sample app shows that in some rare cases of APK files, you can't get the app-icon. Sometimes it's some invalid "bmp" file. Sometimes it's some XML file. And other weird cases. Same goes for app-names (labels). I hope that some day it could be fixed.
- The entire code is in Java. I personally prefer Kotlin. I hope one day the whole library would be in Kotlin. At the very least, we should have a clear understanding for everything, if it's nullable or not.
- In some cases the library might take a huge amount of memory, causing it not to be able to parse (OOM). I think a better optimization is needed. Maybe some sort of way to tell exactly what we want to get out of it, it would minimize such memory usage.
- Memory issues are more important, of course, but it could be nice to see if it's possible to make it faster, too. The reason is that somehow the built-in APK-parsing seems much faster. That's despite the fact that it seems this library was based on the framework's code.
