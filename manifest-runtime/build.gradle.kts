plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core-model"))
    api(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml.engine)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(project(":shared-test-fixtures"))
    testImplementation(libs.json.schema.validator)
}
