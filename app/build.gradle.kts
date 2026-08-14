import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 applies the Kotlin Android plugin itself; applying it here as well fails.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tsfeith.habits"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tsfeith.habits"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Replays committed schema JSONs so migrations are verified, not assumed.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
