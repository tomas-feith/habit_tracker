import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Local signing credentials, absent on CI and on a fresh clone.
 *
 * The file is gitignored and points at a keystore stored outside the repository, because
 * this key cannot be regenerated: Android identifies an installed app by applicationId plus
 * signing certificate, so losing it means the app can never be updated in place again and
 * the only way forward destroys the database.
 *
 * See docs/INSTALLING.md.
 */
val keystoreProperties =
    rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }

plugins {
    // AGP 9 applies the Kotlin Android plugin itself; applying it here as well fails.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.chainhabits.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.chainhabits.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "Habits")
    }

    signingConfigs {
        // Only declared when the credentials are present. On CI and on a fresh clone the
        // release build simply comes out unsigned, which is correct: an unsigned artifact
        // is obviously unusable, whereas one silently signed with the debug key looks fine
        // and then cannot be updated by a real release later.
        keystoreProperties?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            /*
             * A separate application id, so a debug build can never collide with the
             * release install.
             *
             * They are signed with different keys - debug with the stock Android key,
             * release from keystore.properties - so sharing an id means Android refuses
             * the install with INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the only way past
             * is to uninstall. That deletes the database, and the habit history in it
             * cannot be reconstructed from anywhere: there is no server and, for now, no
             * export. It has already happened once.
             *
             * This matters most for `connectedAndroidTest`, which CI runs on every push to
             * main and which installs the debug build on whatever device is attached.
             */
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            // Distinct on the launcher too. Two identical icons labelled "Habit Tracker"
            // is how you end up logging a week of habits into the wrong install.
            resValue("string", "app_name", "Habits (debug)")
        }
        release {
            signingConfig = signingConfigs.findByName("release")

            // Left off for now: an unminified release is one fewer variable if a
            // release-only failure ever appears. Turning it on later needs the Room and
            // Compose keep rules verified on device, not just a green build.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // AGP 9 turns custom resource values off by default; app_name is declared per
        // build type so the debug install is labelled distinctly.
        resValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        // This is a personal app with no release train, so a warning that never gets
        // triaged is just noise. Fail the build instead.
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        disable +=
            setOf(
                // Version bumps are Dependabot's job, not a build failure's.
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
                // Fires on lint jars shipped inside androidx.navigation, which we can
                // neither fix nor usefully act on.
                "ObsoleteLintCustomCheck",
            )
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// AGP 9 dropped the `kotlinOptions` block in favour of the Kotlin plugin's own DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The migration test replays the committed schema JSONs, so they have to be on the
// instrumentation test classpath as assets.
android.sourceSets
    .getByName("androidTest")
    .assets
    .srcDir("$projectDir/schemas")

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Pinned on the main classpath, not just androidTest: AGP's consistent resolution
    // forces the test classpath to match whatever the app runtime resolved, so pinning
    // only the test side is silently overridden back down.
    implementation(platform(libs.kotlinx.serialization.bom))

    // Home-screen widget. Glance renders to RemoteViews, so it is a separate Compose-like
    // runtime rather than the app's own Compose UI - nothing is shared but the domain layer.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Replays committed schema JSONs so migrations are verified, not assumed.
    androidTestImplementation(libs.androidx.room.testing)
    // Room's MigrationTestHelper parses those JSONs with kotlinx-serialization, and a
    // core/json version mismatch surfaces only at runtime as AbstractMethodError.
    androidTestImplementation(platform(libs.kotlinx.serialization.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
