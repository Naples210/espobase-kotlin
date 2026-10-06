plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    `maven-publish`
    signing
}

group = "com.espobase"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Android (minSdk 26+) and any JVM 11+.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        allWarningsAsErrors.set(true)
        explicitApi()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    // Maven Central requires a sources jar and a javadoc jar next to the library.
    withSourcesJar()
    withJavadocJar()
}

tasks.test {
    useJUnitPlatform()
}

// ---- Publishing to Maven Central (docs/OWNER_TODO.md) ----------------------------------------------------------------
// `./gradlew centralBundle` writes build/central/espobase-kotlin-<version>-bundle.zip: the library, sources and javadoc jars, the
// POM and checksums, each signed with the owner's GPG key when ESPOBASE_SIGN=true (the key stays in the owner's gpg keyring and
// its passphrase is typed into gpg's own prompt, never into a file here). The owner uploads the zip at central.sonatype.com
// (Publish > Publish Component); nothing in this build talks to the network or holds a credential.
publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "espobase-kotlin"
            pom {
                name.set("Espobase for Kotlin")
                description.set(
                    "The Espobase client for Kotlin (Android and JVM): database, App Authentication, Storage and edge functions.",
                )
                url.set("https://espobase.com")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                developers {
                    developer {
                        id.set("matterone")
                        name.set("MatterOne LLC")
                        url.set("https://espobase.com")
                    }
                }
                scm {
                    url.set("https://github.com/Naples210/espobase-kotlin")
                    connection.set("scm:git:https://github.com/Naples210/espobase-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Naples210/espobase-kotlin.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "central"
            url = uri(layout.buildDirectory.dir("central/repo"))
        }
    }
}

if (System.getenv("ESPOBASE_SIGN") == "true") {
    signing {
        useGpgCmd()
        sign(publishing.publications["library"])
    }
}

tasks.named<Jar>("javadocJar") {
    from("README.md") // the library is Kotlin: the javadoc jar carries the README so it isn't empty
}

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Zip for the Maven Central Portal (set ESPOBASE_SIGN=true to sign it)."
    dependsOn("publishAllPublicationsToCentralRepository")
    from(layout.buildDirectory.dir("central/repo")) {
        exclude("**/maven-metadata.xml*") // the portal makes its own
    }
    destinationDirectory.set(layout.buildDirectory.dir("central"))
    archiveFileName.set("espobase-kotlin-${project.version}-bundle.zip")
}
