plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "au.gondwanasoftware.ontrack"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.gondwanasoftware.ontrack"
        minSdk = 30
        targetSdk = 34
        versionCode = 7
        versionName = "1.3.1"
        vectorDrawables {
            useSupportLibrary = true
        }

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    //implementation("androidx.datastore.preferences.core.Preferences")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-splashscreen:1.2.0-alpha02")
    implementation("androidx.wear.tiles:tiles:1.4.1")
    //implementation("androidx.wear.tiles:tiles-material:1.3.0")
    implementation("com.google.android.horologist:horologist-compose-tools:0.6.20")
    implementation("com.google.android.horologist:horologist-tiles:0.6.20")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("androidx.health:health-services-client:1.1.0-alpha04")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("com.google.guava:guava:33.3.1-jre")
    implementation("com.google.android.gms:play-services-fitness:21.2.0")
    implementation("androidx.wear:wear-input:1.2.0-alpha02")
    //implementation("androidx.wear:wear-input:1.1.0")       // IAW https://issuetracker.google.com/issues/300760566
    implementation("androidx.wear:wear-input:1.2.0-alpha02")
    implementation("androidx.wear:wear-tooling-preview:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui-graphics")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.11.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.5")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")
    //val navVersion = "2.7.6"
    //implementation("androidx.navigation:navigation-compose:$navVersion")
    implementation("com.google.android.horologist:horologist-composables:0.6.20")
    implementation("androidx.core:core-splashscreen:1.2.0-alpha02")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-expression:1.2.1")
    implementation("androidx.wear.tiles:tiles:1.4.1")

}