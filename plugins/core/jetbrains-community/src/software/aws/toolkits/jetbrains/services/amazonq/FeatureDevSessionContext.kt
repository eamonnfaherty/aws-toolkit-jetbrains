// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.amazonq

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.isFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.codec.digest.DigestUtils
import software.aws.toolkits.core.utils.outputStream
import software.aws.toolkits.core.utils.putNextEntry
import software.aws.toolkits.jetbrains.core.coroutines.EDT
import software.aws.toolkits.jetbrains.core.coroutines.getCoroutineBgContext
import software.aws.toolkits.jetbrains.services.telemetry.ALLOWED_CODE_EXTENSIONS
import software.aws.toolkits.resources.AwsCoreBundle
import software.aws.toolkits.telemetry.AmazonqTelemetry
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.io.path.Path
import kotlin.io.path.relativeTo

interface RepoSizeError {
    val message: String
}
class RepoSizeLimitError(override val message: String) : RuntimeException(), RepoSizeError

class FeatureDevSessionContext(val project: Project, val maxProjectSizeBytes: Long? = null) {
    // TODO: Need to correct this class location in the modules going further to support both amazonq and codescan.

    private val ignorePatterns = setOf(
        "\\.aws-sam",
        "\\.svn",
        "\\.hg/?",
        "\\.rvm",
        "\\.git/?",
        "\\.gitignore",
        "\\.project",
        "\\.gem",
        "/\\.idea/?",
        "\\.zip$",
        "\\.bin$",
        "\\.png$",
        "\\.jpg$",
        "\\.svg$",
        "\\.pyc$",
        "/license\\.txt$",
        "/License\\.txt$",
        "/LICENSE\\.txt$",
        "/license\\.md$",
        "/License\\.md$",
        "/LICENSE\\.md$",
        "node_modules/?",
        "build/?",
        "dist/?"
    ).map { Regex(it) }

    // well known source files that do not have extensions
    private val wellKnownSourceFiles = setOf(
        "Dockerfile",
        "Dockerfile.build"
    )

    // projectRoot: is the directory where the project is located when selected to open a project.
    val projectRoot = project.guessProjectDir() ?: error("Cannot guess base directory for project ${project.name}")

    // selectedSourceFolder": is the directory selected in replacement of the root, this happens when the project is too big to bundle for uploading.
    private var _selectedSourceFolder = projectRoot
    private var ignorePatternsWithGitIgnore = emptyList<Regex>()
    private val gitIgnoreFile = File(selectedSourceFolder.path, ".gitignore")

    init {
        ignorePatternsWithGitIgnore = try {
            buildList {
                addAll(ignorePatterns)
                parseGitIgnore().mapNotNull { pattern ->
                    runCatching { Regex(pattern) }.getOrNull()
                }.let { addAll(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getProjectZip(): ZipCreationResult {
        val zippedProject = runBlocking {
            withBackgroundProgress(project, AwsCoreBundle.message("amazonqFeatureDev.placeholder.generating_code")) {
                zipFiles(selectedSourceFolder)
            }
        }
        val checkSum256: String = Base64.getEncoder().encodeToString(DigestUtils.sha256(FileInputStream(zippedProject)))
        return ZipCreationResult(zippedProject, checkSum256, zippedProject.length())
    }

    fun isFileExtensionAllowed(file: VirtualFile): Boolean {
        // if it is a directory, it is allowed
        if (file.isDirectory) return true

        val extension = file.extension ?: return false
        return ALLOWED_CODE_EXTENSIONS.contains(extension)
    }

    private fun ignoreFileByExtension(file: VirtualFile) =
        !isFileExtensionAllowed(file)

    suspend fun ignoreFile(file: VirtualFile): Boolean = ignoreFile(file.path)

    suspend fun ignoreFile(path: String): Boolean {
        // this method reads like something a JS dev would write and doesn't do what the author thinks
        val deferredResults = ignorePatternsWithGitIgnore.map { pattern ->
            withContext(coroutineContext) {
                async { pattern.containsMatchIn(path) }
            }
        }

        // this will serially iterate over and block
        // ideally we race the results https://github.com/Kotlin/kotlinx.coroutines/issues/2867
        // i.e. Promise.any(...)
        return deferredResults.any { it.await() }
    }

    private fun wellKnown(file: VirtualFile): Boolean = wellKnownSourceFiles.contains(file.name)

    suspend fun zipFiles(projectRoot: VirtualFile): File = withContext(getCoroutineBgContext()) {
        val files = mutableListOf<VirtualFile>()
        val ignoredExtensionMap = mutableMapOf<String, Long>().withDefault { 0L }
        var totalSize: Long = 0

        VfsUtil.visitChildrenRecursively(
            projectRoot,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    val isWellKnown = runBlocking { wellKnown(file) }
                    val isFileIgnoredByExtension = runBlocking { ignoreFileByExtension(file) }
                    if (!isWellKnown && isFileIgnoredByExtension) {
                        val extension = file.extension.orEmpty()
                        ignoredExtensionMap[extension] = (ignoredExtensionMap[extension] ?: 0) + 1
                        return false
                    }
                    val isFileIgnoredByPattern = runBlocking { ignoreFile(file.name) }
                    if (isFileIgnoredByPattern) {
                        return false
                    }

                    if (file.isFile) {
                        totalSize += file.length
                        files.add(file)

                        if (maxProjectSizeBytes != null && totalSize > maxProjectSizeBytes) {
                            throw RepoSizeLimitError(AwsCoreBundle.message("amazonqFeatureDev.content_length.error_text"))
                        }
                    }
                    return true
                }
            }
        )

        for ((key, value) in ignoredExtensionMap) {
            AmazonqTelemetry.bundleExtensionIgnored(
                count = value,
                filenameExt = key
            )
        }

        // Process files in parallel
        val filesToIncludeFlow = channelFlow {
            // chunk with some reasonable number because we don't actually need a new job for each file
            files.chunked(50).forEach { chunk ->
                launch {
                    for (file in chunk) {
                        send(file)
                    }
                }
            }
        }

        createTemporaryZipFileAsync { zipOutput ->
            filesToIncludeFlow.collect { file ->
                try {
                    val relativePath = Path(file.path).relativeTo(projectRoot.toNioPath())
                    zipOutput.putNextEntry(relativePath.toString(), Path(file.path))
                } catch (e: NoSuchFileException) {
                    // Noop: Skip if file was deleted
                }
            }
        }
    }.toFile()

    private suspend fun createTemporaryZipFileAsync(block: suspend (ZipOutputStream) -> Unit): Path = withContext(EDT) {
        val file = Files.createTempFile(null, ".zip")
        ZipOutputStream(file.outputStream()).use { zipOutput -> block(zipOutput) }
        file
    }

    private fun parseGitIgnore(): Set<String> {
        if (!gitIgnoreFile.exists()) {
            return emptySet()
        }
        return gitIgnoreFile.readLines()
            .filterNot { it.isBlank() || it.startsWith("#") }
            .map { it.trim() }
            .map { convertGitIgnorePatternToRegex(it) }
            .toSet()
    }

    // gitignore patterns are not regex, method update needed.
    private fun convertGitIgnorePatternToRegex(pattern: String): String = pattern
        .replace(".", "\\.")
        .replace("*", ".*")
        .let { if (it.endsWith("/")) "$it?" else it } // Handle directory-specific patterns by optionally matching trailing slash

    var selectedSourceFolder: VirtualFile
        set(newRoot) {
            _selectedSourceFolder = newRoot
        }
        get() = _selectedSourceFolder
}

data class ZipCreationResult(val payload: File, val checksum: String, val contentLength: Long)
