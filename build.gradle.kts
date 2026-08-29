buildscript {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
    dependencies {
        // 覆盖 AGP 8.9.3 自带的 R8 8.9.x：Kotlin 2.2 官方要求 R8 >= 8.10.21。
        // 版本偏低时 D8 读不懂新版 Kotlin metadata，开启核心库脱糖后会逐类重写 metadata，
        // 实测刷出 2000+ 条 WARNING（日志达 40 MB）。
        // 必须放在根项目 buildscript：放 settings.gradle.kts 的 buildscript 里只影响 settings 脚本自身
        // 的 classpath，AGP 仍用它捆绑的版本，实测无效。
        classpath("com.android.tools:r8:8.10.21")
    }
}

plugins {
    id("com.android.application") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.google.dagger.hilt.android") version "2.56.1" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}
