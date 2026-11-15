import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystorePropsFile = rootProject.file("release.properties")
val keystoreProps = Properties()

if (keystorePropsFile.exists()) {
    FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
}

// CI passes credentials through environment variables, never through tracked files.
// A partial CI configuration is an error rather than silently producing an unsigned APK.
val signingNames = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val signingEnvNames = listOf("TUNELYST_KEYSTORE_FILE", "TUNELYST_STORE_PASSWORD", "TUNELYST_KEY_ALIAS", "TUNELYST_KEY_PASSWORD")
val signingEnv = signingEnvNames.map { System.getenv(it) }
if (signingEnv.any { !it.isNullOrBlank() } && signingEnv.any { it.isNullOrBlank() }) {
    throw GradleException("Incomplete release signing environment; set all four TUNELYST signing variables.")
}
val signingValues: Map<String, String> = if (signingEnv.all { !it.isNullOrBlank() }) {
    signingNames.zip(signingEnv.map { it!! }).toMap()
} else {
    signingNames.mapNotNull { name -> keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }?.let { name to it } }.toMap()
}
val hasValidSigningProps = signingNames.all { signingValues[it] != null }

android {
    namespace = "com.tunelyst.player"
    compileSdk = 36

    lint {
        checkReleaseBuilds = false
    }

    signingConfigs {
        if (hasValidSigningProps) {
            create("release") {
                storeFile = rootProject.file(signingValues.getValue("storeFile"))
                storePassword = signingValues.getValue("storePassword")
                keyAlias = signingValues.getValue("keyAlias")
                keyPassword = signingValues.getValue("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.tunelyst.player"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            if (hasValidSigningProps) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            resources.excludes.add("META-INF/kotlinx_coroutines_core.version")

            resources.pickFirsts.add("nonJvmMain/default/linkdata/package_androidx/0_androidx.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/module")

            resources.pickFirsts.add("nativeMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nativeMain/default/linkdata/module")

            resources.pickFirsts.add("commonMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("commonMain/default/linkdata/module")
            resources.pickFirsts.add("commonMain/default/linkdata/package_androidx/0_androidx.knm")

            resources.pickFirsts.add("META-INF/kotlin-project-structure-metadata.json")

            resources.merges.add("commonMain/default/manifest")
            resources.merges.add("nonJvmMain/default/manifest")
            resources.merges.add("nativeMain/default/manifest")
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}

// Restore the version used by the original CoGo template.
// This is a targeted workaround for the missing core-ktx 1.2.0 artifact.
configurations.configureEach {
    resolutionStrategy.force("androidx.core:core-ktx:1.8.0")
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.android.material)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.interpolator)
}

// Local JVM regression tests; no device or hard-coded SDK paths are required.
android { testOptions { unitTests.isIncludeAndroidResources = true } }
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    forkEvery = 1
    maxHeapSize = "280m"
    jvmArgs("-XX:ActiveProcessorCount=2", "-XX:+UseSerialGC")
    providers.gradleProperty("robolectricDependencyDir").orNull?.let { systemProperty("robolectric.dependency.dir", it) }
}
