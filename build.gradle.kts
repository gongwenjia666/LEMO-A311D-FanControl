plugins {
    id("com.android.application") version "8.1.4" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
