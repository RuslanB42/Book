plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.kapt") }
android {
    namespace = "app.polka.nativeapp"
    compileSdk = 35
    defaultConfig { applicationId = "app.polka.nativeapp"; minSdk = 26; targetSdk = 35; versionCode = 405; versionName = "2.0.5"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; ndk { abiFilters += listOf("arm64-v8a","armeabi-v7a") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs { create("localTest") { storeFile = rootProject.file("signing/polka-local.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
    buildTypes { debug { signingConfig = signingConfigs.getByName("localTest"); isDebuggable = false }; release { isMinifyEnabled = false } }
    testOptions { unitTests.isIncludeAndroidResources = true; unitTests.all { it.maxHeapSize = "1g"; it.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }; it.systemProperty("openingReferenceFixture",rootProject.file("test-fixtures/opening-web-reference.json").absolutePath); it.systemProperty("recReferenceFixture",rootProject.file("test-fixtures/recommendations-reference.json").absolutePath); it.systemProperty("webBackupFixture", rootProject.file("test-fixtures/web-fixture.polka").absolutePath); it.systemProperty("qaOutputDir",rootProject.file("qa").absolutePath) } }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.core:core:1.13.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.airbnb.android:lottie:6.4.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
