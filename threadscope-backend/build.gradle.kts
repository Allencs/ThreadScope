plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.threadscope"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

configurations.configureEach {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    exclude(group = "ch.qos.logback", module = "logback-classic")
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // 登录与权限：服务端 Session + 数据库用户
    implementation("org.springframework.boot:spring-boot-starter-security")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // PostgreSQL：JFR 解析结果。原始 .jfr 不入库。
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")

    // Cache (for analysis results)
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // Actuator (health check)
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Log4j2：控制台 + 项目根目录滚动文件。排除了默认的 Logback。
    implementation("org.springframework.boot:spring-boot-starter-log4j2")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
    // 测试日志进构建目录，不写仓库根目录的 logs/
    environment("THREADSCOPE_LOG_DIR", layout.buildDirectory.dir("test-logs").get().asFile.absolutePath)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val logDir = rootProject.projectDir.parentFile.resolve("logs").absolutePath
    systemProperty("threadscope.log.dir", logDir)
    environment("THREADSCOPE_LOG_DIR", logDir)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--enable-preview"))
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview")
}
