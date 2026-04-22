plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("com.google.devtools.ksp") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0" apply false
}

// KSP 2.3.0+ 不再绑定 Kotlin 版本，可独立升级
// LiteRT SDK (com.google.ai.edge.litertlm:litertlm-android) 需要 Kotlin 2.3.0+
subprojects {
    configurations.all {
        resolutionStrategy {
            eachDependency {
                if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-")) {
                    useVersion("2.3.0")
                }
            }
        }
    }
}
