import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp") version "2.3.6"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}

extensions.configure<ApplicationExtension> {
    namespace = "com.lb.apkparserdemo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lb.apkparserdemo"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        // https://developer.android.com/studio/write/java8-support#library-desugaring
        isCoreLibraryDesugaringEnabled = true
        // https://developer.android.com/build/jdks#target-compat
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation(project(":mylibrary"))
    // https://mvnrepository.com/artifact/org.apache.commons/commons-compress
    implementation("org.apache.commons:commons-compress:1.28.0")
// https://mvnrepository.com/artifact/com.android.tools/desugar_jdk_libs_nio https://github.com/google/desugar_jdk_libs/blob/master/CHANGELOG.md  https://developer.android.com/studio/write/java8-support https://android-developers.googleblog.com/2023/02/api-desugaring-supporting-android-13-and-java-nio.html
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    //  https://github.com/AndroidDeveloperLB/CommonUtils  https://jitpack.io/#AndroidDeveloperLB/CommonUtils/
    implementation("com.github.AndroidDeveloperLB:CommonUtils:42")
    //    https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    //    https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-android
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    //    https://developer.android.com/jetpack/androidx/releases/lifecycle#declaring_dependencies
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")

    //  https://developer.android.com/jetpack/androidx/releases/fragment  https://mvnrepository.com/artifact/androidx.fragment/fragment
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    // Compose minimal dependencies, to be able to get VectorDrawable better
//    https://developer.android.com/develop/ui/compose/bom
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    // Room
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Coil
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
