
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("D:\\AndroidStudioProjects\\Freedom\\AppKey.jks")
            storePassword = "SalTech#1402"
            keyAlias = "puyakhan"
            keyPassword = "SalTech#1402"
        }
        create("release") {
            storeFile = file("D:\\AndroidStudioProjects\\Freedom\\AppKey.jks")
            storePassword = "SalTech#1402"
            keyAlias = "puyakhan"
            keyPassword = "SalTech#1402"
        }
    }
    namespace = "ir.saltech.freedom"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.saltech.prox"
        minSdk = 21
        targetSdk = 34
        versionCode = 1542
        versionName = "2.14.1925"
        multiDexEnabled = true
        ndk {
            abiFilters += listOf("armeabi-v7a","arm64-v8a","x86","x86_64")
        }
        signingConfig = signingConfigs.getByName("release")
        proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            multiDexEnabled = true
            ndk {
                abiFilters += listOf("armeabi-v7a","arm64-v8a","x86","x86_64")
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            multiDexEnabled = true
            ndk {
                abiFilters += listOf("armeabi-v7a","arm64-v8a","x86","x86_64")
            }
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            include("armeabi-v7a","arm64-v8a","x86","x86_64")
        }
    }


    applicationVariants.all {
        val variant = this
//        val versionCodes =
//            mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4, "all" to 0)

        variant.outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .forEach { output ->
//                val abi = if (output.getFilter(com.android.build.OutputFile.ABI) != null)
//                    output.getFilter(com.android.build.OutputFile.ABI)
//                else
//                    "all"
//                output.outputFileName = "v2rayNG_.apk"
//                output.versionCodeOverride = (1000000 * versionCodes[abi]!!).plus(
//                    variant.versionCode
//                )
                output.outputFileName = "Prox_" + variant.versionName + "_" + output.getFilter(com.android.build.OutputFile.ABI) + ".apk"

                output.versionCodeOverride =
                    defaultConfig.versionCode!! * 1000 + variant.versionCode
            }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    ndkVersion = "26.1.10909125"
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar","*.jar"))))
    testImplementation("junit:junit:4.13.2")

    // Androidx
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0-beta02")
//    implementation("androidx.webkit:webkit:1.10.0")

    // Androidx ktx
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    //kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-RC2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0-RC2")

    implementation("com.tencent:mmkv-static:1.3.3")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("io.reactivex:rxandroid:1.2.1")
    implementation("com.tbruyelle.rxpermissions:rxpermissions:0.9.4@aar")
    implementation("com.github.jorgecastilloprz:fabprogresscircle:1.01@aar")
    implementation("me.drakeet.support:toastcompat:1.1.0")
    implementation("com.blacksquircle.ui:editorkit:2.9.0")
    implementation("com.blacksquircle.ui:language-base:2.9.0")
    implementation("com.blacksquircle.ui:language-json:2.9.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("io.github.g00fy2.quickie:quickie-bundled:1.9.0")
    implementation("com.google.zxing:core:3.5.3")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.work:work-multiprocess:2.9.0")
}