plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

group = "lux.uno.anima"
version = "1.0.0"
application {
    mainClass = "lux.uno.anima.ApplicationKt"
}

dependencies {
    api(project(":core"))
    implementation(libs.logback)
    implementation(libs.kotlinx.serializationJson)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverRateLimit)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serializationJson)
    implementation(libs.sqlite.jdbc)
    implementation(libs.bouncycastle)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}
