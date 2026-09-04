plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
}

group = "dev.booking"
version = "0.1.0"

repositories { mavenCentral() }

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Treat JSR-305 annotations as strict so Spring's @Nullable/@NonNull
        // participate in Kotlin's null checks rather than producing platform types.
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    // The Spring Boot BOM is the version pin for everything it manages —
    // see docs/backend-design/stack-selection.md section 6.  Only components
    // absent from the BOM carry an explicit version below.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Jackson 3 under Spring Boot 4.  The group is tools.jackson, NOT
    // com.fasterxml.jackson — the old coordinate still resolves and would
    // silently pull Jackson 2 (stack-selection.md section 5.3).
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Boot 4 moved Kafka auto-configuration into its own starter.  Depending on
    // org.springframework.kafka:spring-kafka alone compiles fine but ships no
    // KafkaProperties, no container factory and no template — spring.kafka.*
    // would be inert and @KafkaListener would never start.
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    // AQ8: the OpenAPI document is generated from the serving endpoints, never
    // hand-kept.  Not managed by the Boot BOM, so the version is pinned here —
    // the 3.x line targets Boot 4 (2.x targets Boot 3).
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // Testcontainers is the deliverable's integration-test infrastructure.
    // AbstractPostgresTest falls back to an already-running database when
    // BOOKING_TEST_JDBC_URL is set, which is how these run where Docker
    // cannot publish ports.
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    // Testcontainers 2.x prefixes every module artifact with "testcontainers-";
    // the 1.x names (junit-jupiter, postgresql) still resolve at 1.21.4 and would
    // silently downgrade.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

// The DDL under docs/backend-design/ddl is the single source of truth for the
// schema (data-design.md).  Flyway migrations are generated from it at build
// time rather than copied by hand, so the two can never drift.
val generatedMigrations = layout.buildDirectory.dir("generated-migrations")

val ddlToMigrations by tasks.registering(Copy::class) {
    description = "Generates Flyway migrations from the authoritative DDL."
    from(layout.projectDirectory.dir("docs/backend-design/ddl")) { include("*.sql") }
    into(generatedMigrations.map { it.dir("db/migration") })
    rename("01-create-tables.sql", "V1__create_tables.sql")
    rename("02-create-indexes.sql", "V2__create_indexes.sql")
    rename("03-functions.sql", "V3__functions.sql")
    rename("04-seed-lookups.sql", "V4__seed_lookups.sql")
}

sourceSets.main { resources.srcDir(generatedMigrations) }
tasks.processResources { dependsOn(ddlToMigrations) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }

    // Integration tests use Testcontainers by default.  Supplying these lets the
    // suite run against an already-running PostgreSQL instead — needed wherever
    // Docker cannot publish ports.  Forwarded explicitly rather than inherited,
    // because a reused Gradle daemon does not pick up the invoking shell's
    // environment.
    listOf("BOOKING_TEST_JDBC_URL", "BOOKING_TEST_DB_USER", "BOOKING_TEST_DB_PASSWORD")
        .forEach { key ->
            (project.findProperty(key) as String? ?: System.getenv(key))
                ?.let { environment(key, it) }
        }
}
