plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core-model"))
    api(project(":manifest-runtime"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml.engine)
}
