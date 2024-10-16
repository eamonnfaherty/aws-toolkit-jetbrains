// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import com.jetbrains.plugin.structure.intellij.utils.JDOMUtil
import org.jdom2.Document
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import org.jetbrains.intellij.platform.gradle.tasks.PatchPluginXmlTask
import software.aws.toolkits.gradle.buildMetadata
import software.aws.toolkits.gradle.changelog.tasks.GeneratePluginChangeLog
import software.aws.toolkits.gradle.intellij.IdeFlavor
import software.aws.toolkits.gradle.intellij.IdeVersions
import software.aws.toolkits.gradle.isCi
import java.io.StringWriter
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

val toolkitVersion: String by project
val ideProfile = IdeVersions.ideProfile(project)

plugins {
    id("java-library")
    id("toolkit-kotlin-conventions")
    id("toolkit-testing")
    id("toolkit-intellij-subplugin")
    id("toolkit-integration-testing")
}

intellijToolkit {
    ideFlavor.set(IdeFlavor.IC)
}

dependencies {
    intellijPlatform {
        localPlugin(project(":plugin-core"))

        when (providers.gradleProperty("ideProfileName").get()) {
            "2023.3", "2024.1" -> {}
            else -> {
                bundledModule("intellij.platform.vcs.dvcs.impl")
                bundledModule("intellij.libraries.microba")
            }
        }
    }
}

val changelog = tasks.register<GeneratePluginChangeLog>("pluginChangeLog") {
    includeUnreleased.set(true)
    changeLogFile.set(project.file("$buildDir/changelog/change-notes.xml"))
}

tasks.compileJava {
    // https://github.com/gradle/gradle/issues/26006
    // consistently saves 6+ minutes in CI. we do not need incremental compilation for 2 java files
    options.isIncremental = false
}

// toolkit depends on :plugin-toolkit:jetbrains-core setting the version instead of being defined on the root project
PatchPluginXmlTask.register(project)
val patchPluginXml = tasks.named<PatchPluginXmlTask>("patchPluginXml")
patchPluginXml.configure {
    val buildSuffix = if (!project.isCi()) "+${buildMetadata()}" else ""
    pluginVersion.set("$toolkitVersion-${ideProfile.shortName}$buildSuffix")
}

tasks.jar {
    dependsOn(patchPluginXml, changelog)
    from(changelog) {
        into("META-INF")
    }

    from(patchPluginXml) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        into("META-INF")
    }
}

tasks.integrationTest {
    // cant run tests under authorization_grant with PKCE yet
    systemProperty("aws.dev.useDAG", true)
}

val gatewayPluginXml = tasks.register<PatchPluginXmlTask>("pluginXmlForGateway") {
    val buildSuffix = if (!project.isCi()) "+${buildMetadata()}" else ""
    pluginVersion.set("GW-$toolkitVersion-${ideProfile.shortName}$buildSuffix")
}

val patchGatewayPluginXml by tasks.registering {
    dependsOn(gatewayPluginXml)

    val output = temporaryDir.resolve("plugin.xml")
    outputs.file(output)

    // jetbrains expects gateway plugin to be dynamic
    doLast {
        gatewayPluginXml.get().outputFile.asFile
            .map(File::toPath)
            .get()
            .let { path ->
                val document = path.inputStream().use { inputStream ->
                    JDOMUtil.loadDocument(inputStream)
                }

                document.rootElement
                    .getAttribute("require-restart")
                    .setValue("false")

                transformXml(document, output.toPath())
            }
    }
}

val gatewayArtifacts by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    // share same dependencies as default configuration
    extendsFrom(configurations["implementation"], configurations["runtimeOnly"])
}

val gatewayJar = tasks.create<Jar>("gatewayJar") {
    // META-INF/plugin.xml is a duplicate?
    // unclear why the exclude() statement didn't work
    duplicatesStrategy = DuplicatesStrategy.WARN

    dependsOn(tasks.instrumentedJar, patchGatewayPluginXml)

    archiveBaseName.set("aws-toolkit-jetbrains-IC-GW")
    from(tasks.instrumentedJar.get().outputs.files.map { zipTree(it) }) {
        exclude("**/plugin.xml")
        exclude("**/plugin-intellij.xml")
        exclude("**/inactive")
    }

    from(patchGatewayPluginXml) {
        into("META-INF")
    }

    val pluginGateway = sourceSets.main.get().resources.first { it.name == "plugin-gateway.xml" }
    from(pluginGateway) {
        into("META-INF")
    }
}

artifacts {
    add("gatewayArtifacts", gatewayJar)
}

tasks.prepareSandbox {
    // you probably do not want to modify this.
    // this affects the IDE sandbox / build for `:jetbrains-core`, but will not propogate to the build generated by `:intellij`
    // (which is what is ultimately published to the marketplace)
    // without additional effort
}

tasks.testJar {
    // classpath.index is a duplicate
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    // not sure why this is getting pulled in
    exclude("**/plugin.xml")
}

tasks.processTestResources {
    // TODO how can we remove this. Fails due to:
    // "customerUploadedEventSchemaMultipleTypes.json.txt is a duplicate but no duplicate handling strategy has been set"
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

dependencies {
    listOf(
        libs.aws.apprunner,
        libs.aws.cloudcontrol,
        libs.aws.cloudformation,
        libs.aws.cloudwatchlogs,
        libs.aws.codecatalyst,
        libs.aws.dynamodb,
        libs.aws.ec2,
//        libs.aws.ecr,
//        libs.aws.ecs,
//        libs.aws.iam,
//        libs.aws.lambda,
        libs.aws.rds,
        libs.aws.redshift,
//        libs.aws.s3,
        libs.aws.schemas,
        libs.aws.secretsmanager,
        libs.aws.sns,
        libs.aws.sqs,
        libs.aws.services,
    ).forEach { api(it) { isTransitive = false } }

    compileOnlyApi(project(":plugin-core:core"))
    compileOnlyApi(project(":plugin-core:jetbrains-community"))

    implementation(libs.zjsonpatch)

    testFixturesApi(testFixtures(project(":plugin-core:jetbrains-community")))
}

fun transformXml(document: Document, path: Path) {
    val xmlOutput = XMLOutputter()
    xmlOutput.format.apply {
        indent = "  "
        omitDeclaration = true
        textMode = Format.TextMode.TRIM
    }

    StringWriter().use {
        xmlOutput.output(document, it)
        path.writeText(text = it.toString())
    }
}
