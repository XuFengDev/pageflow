plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    js {
        browser()
        nodejs()
        useEsModules()
    }
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
