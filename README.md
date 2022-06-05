# apk-parser

Apk parser for java/Android, forked from here:

https://github.com/hsiafan/apk-parser

To use in gradle file:

https://jitpack.io/#AndroidDeveloperLB/apk-parser/

Known issues and notes:

- The sample app shows that you can't get the app-icon of some APK files. Sometimes it's some invalid "bmp" file. Sometimes it's some XML file. And other weird cases. I hope that some day it could be fixed.
- The entire code is in Java. I personally prefer Kotlin. I hope one day the whole library would be in Kotlin. At the very least, we should have a clear understanding for everything, if it's nullable or not.
- In some cases the library might take a huge amount of memory, causing it not to be able to parse (OOM). I think a better optimization is needed. Maybe some sort of way to tell exactly what we want to get out of it, it would minimize such memory usage.
