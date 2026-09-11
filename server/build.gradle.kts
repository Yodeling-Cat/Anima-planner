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

// Pinned to the JRE the deploy image carries (server/deploy/Dockerfile). Left to the
// default this follows whatever JDK happens to run Gradle, and the first developer on a
// newer one would ship a jar the host cannot load.
kotlin {
    jvmToolchain(21)
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
    implementation(libs.ktor.serverForwardedHeader)
    implementation(libs.ktor.serializationJson)
    implementation(libs.sqlite.jdbc)
    implementation(libs.bouncycastle)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutinesTest)
}
