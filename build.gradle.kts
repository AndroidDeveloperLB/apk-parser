plugins {
    id("com.android.application") version "9.4.0-alpha01" apply false
    id("com.android.library") version "9.2.1" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
