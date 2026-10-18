plugins {
    // AGP 9 compiles Kotlin itself ("built-in Kotlin"), so there's no separate Kotlin plugin.
    id("com.android.application") version "9.4.0" apply false
    // Code generator for the Gemini / AppFunctions integration
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
