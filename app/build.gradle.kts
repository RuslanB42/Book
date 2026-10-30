plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.kapt") }
android {
    namespace = "app.polka.nativeapp"
    compileSdk = 35
    defaultConfig { applicationId = "app.polka.nativeapp"; minSdk = 26; targetSdk = 35; versionCode = 37; versionName = "0.6.31" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs { create("localTest") { storeFile = rootProject.file("signing/polka-local.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
    buildTypes { debug { signingConfig = signingConfigs.getByName("localTest") }; release { isMinifyEnabled = false } }
    testOptions { unitTests.isIncludeAndroidResources = true; unitTests.all { it.maxHeapSize = "1g"; it.systemProperty("openingReferenceFixture",rootProject.file("test-fixtures/opening-web-reference.json").absolutePath); it.systemProperty("recReferenceFixture",rootProject.file("test-fixtures/recommendations-reference.json").absolutePath); it.systemProperty("webBackupFixture", rootProject.file("test-fixtures/web-fixture.polka").absolutePath); it.systemProperty("qaOutputDir",rootProject.file("qa").absolutePath) } }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.core:core:1.13.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.caverock:androidsvg-aar:1.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
