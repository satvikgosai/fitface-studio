package dev.fitface.studio.core.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.fitface.studio.core.data.db.ProjectDao
import dev.fitface.studio.core.data.db.ProjectEntity
import dev.fitface.studio.core.format.CONTAINER_HEADER_SIZE
import dev.fitface.studio.core.format.ContainerEntry
import dev.fitface.studio.core.format.FaceEditor
import dev.fitface.studio.core.format.FaceRecordParser
import dev.fitface.studio.core.format.FaceResources
import dev.fitface.studio.core.format.Fit3Apk
import dev.fitface.studio.core.format.Fit3Container
import dev.fitface.studio.core.format.Fit3FormatException
import dev.fitface.studio.core.format.Fit3WidgetIsAnchorException
import dev.fitface.studio.core.format.ImageRecord
import dev.fitface.studio.core.format.ProjectArchive
import dev.fitface.studio.core.format.ProjectArchiveException
import dev.fitface.studio.core.format.ProjectManifest
import dev.fitface.studio.core.format.StructuralEditor
import dev.fitface.studio.core.format.StructuralEdit
import dev.fitface.studio.core.model.AOD_ENTRY_NAME
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.DiagnosticsSection
import dev.fitface.studio.core.model.DuplicatedProject
import dev.fitface.studio.core.model.EditAuditSummary
import dev.fitface.studio.core.model.DirectInstallPayload
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.EditorVariant
import dev.fitface.studio.core.model.ExportedProject
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.format.Fit3NoContainerException
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.ImportedProject
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.ProjectNaming
import dev.fitface.studio.core.model.ProjectSummary
import dev.fitface.studio.core.model.RemovedWidget
import dev.fitface.studio.core.model.ReplacementImage
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.mebibytes
import dev.fitface.studio.core.model.VariantKind
import dev.fitface.studio.core.model.WatchFaceRepository
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WatchFaceException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.editorPreferences by preferencesDataStore(name = "editor_preferences")
private val ImageFitKey = stringPreferencesKey("image_fit")

/**
 * Whether the export and import controls are on screen.
 *
 * In the editor's own preference store rather than a new one: it is a single boolean, and a
 * second `preferencesDataStore` is a second file, a second lock and a second thing to keep in
 * step. The key is deliberately dull — a name that said what it gates would be the one string
 * worth grepping the APK for, and the point of `DeveloperGate` is that nothing advertises it.
 */
private val DeveloperToolsKey = booleanPreferencesKey("advanced_tools")

@Singleton
class WatchFaceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectDao: ProjectDao,
    private val imageSource: AndroidImageSource,
    private val contentResolver: ContentResolver,
    private val diagnostics: DiagnosticsLog,
) : WatchFaceRepository {
    private val mutex = Mutex()
    private var session: Session? = null
    private val removedWidgetIds = AtomicLong()
    private val json = Json { ignoreUnknownKeys = true }

    override fun observeProjects(): Flow<List<ProjectSummary>> =
        projectDao.observeAll()
            .map { projects -> projects.map { it.toSummary(projectPreviewImage(it)) } }
            .flowOn(Dispatchers.IO)

    override fun observeImageFit(): Flow<ImageFit> =
        context.editorPreferences.data.map { preferences ->
            preferences[ImageFitKey]?.let {
                runCatching { ImageFit.valueOf(it) }.getOrNull()
            } ?: ImageFit.COVER
        }

    override suspend fun setImageFit(value: ImageFit) {
        context.editorPreferences.edit { it[ImageFitKey] = value.name }
    }

    override fun observeDeveloperTools(): Flow<Boolean> =
        context.editorPreferences.data.map { it[DeveloperToolsKey] ?: false }

    override suspend fun setDeveloperTools(enabled: Boolean) {
        context.editorPreferences.edit { it[DeveloperToolsKey] = enabled }
    }

    /**
     * Starts a **new** project on a downloaded package, always.
     *
     * It used to look the package's `sourceKey` up first and re-enter the project it found,
     * which is what limited a face to one project and what made "Download & edit" open work
     * that was already in progress without saying so. Continuing an existing project is
     * [openProject]'s job, and the face sheet lists them so there is something to tap.
     */
    override suspend fun openPackage(
        download: FacePackage,
    ): EditorSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val apkBytes = download.copyBytes()
            val desiredStyle = "style${download.selectedStyleId}.bin"
            val loaded = loadSession(
                apkBytes = apkBytes,
                fallbackName = download.displayName,
                projectId = 0,
                editedBinPath = null,
                activeStyleName = desiredStyle,
            )
            if (loaded.apk.faceId != download.expectedFaceId) {
                throw WatchFaceException(
                    "The store returned the wrong watch-face package. Nothing was saved.",
                    "expected=${download.expectedFaceId} actual=${loaded.apk.faceId}",
                )
            }
            val now = System.currentTimeMillis()
            // Named against the face's other projects, so the second one is "Aurora 2" and
            // not a second row reading exactly like the first. The face's own names are
            // identical across every project started on it, which is why the name is stored
            // rather than derived on the way to the screen.
            val siblings = projectDao.findByFaceId(loaded.apk.faceId)
            val project = ProjectEntity(
                id = 0,
                displayName = loaded.sourceName,
                sourceUri = download.sourceKey,
                faceId = loaded.apk.faceId,
                faceName = loaded.apk.faceName,
                importedAtEpochMillis = now,
                localApkPath = null,
                editedBinPath = null,
                selectedStyle = desiredStyle,
                projectName = ProjectNaming.defaultName(
                    base = loaded.apk.faceName?.takeIf(String::isNotBlank) ?: loaded.sourceName,
                    taken = siblings.map(ProjectEntity::resolvedName),
                ),
                productId = download.source?.productId,
                packageVersionCode = download.versionCode,
                styleId = download.selectedStyleId,
                updatedAtEpochMillis = now,
            )
            val projectId = projectDao.insert(project)
            // The row goes in first because the id is what names the directory, so unlike
            // [persistEdited] this cannot be one write — but it can be one commit.
            //
            // `NonCancellable` closes the cancellation window rather than compensating for
            // it: the only suspension point between the two writes is the second `insert`,
            // and backing out of the library while an open finished used to be able to land
            // exactly there. The catch is for a write that genuinely fails — a full disk —
            // because a row naming no package is one `openProject` can only ever refuse,
            // with "This project's package is missing. Download the face again."
            //
            // Leaving it behind used to be survivable: `openPackage` looked the row up by
            // `sourceKey` and reused it, so the next attempt healed it. It always starts a
            // new project now, so a half-written row would never be reused — it would sit
            // in the list unopenable while every retry added a numbered sibling beside it.
            var stylePreviews: Map<Int, String> = emptyMap()
            try {
                withContext(NonCancellable) {
                    val projectDirectory = projectDirectory(projectId).apply { mkdirs() }
                    val localApk = File(projectDirectory, "source.apk")
                    writeAtomically(localApk, apkBytes)
                    // Before the row, always: `observeProjects` maps every DAO emission
                    // through `projectPreviewImage`, so previews written after the last
                    // write to the table are previews no emission has seen. See the note in
                    // [importProject], where the same order left an imported row with no
                    // thumbnail until it was opened.
                    stylePreviews = writeStylePreviews(projectId, loaded.apk)
                    projectDao.insert(
                        project.copy(
                            id = projectId,
                            localApkPath = localApk.absolutePath,
                        ),
                    )
                }
            } catch (error: Throwable) {
                // The DAO directly, never `deleteProject`: that takes `mutex`, which this
                // block is already holding and which is not reentrant.
                withContext(NonCancellable) { projectDao.deleteById(projectId) }
                projectDirectory(projectId).deleteRecursively()
                throw error
            }
            loaded.projectId = projectId
            loaded.projectName = project.projectName ?: loaded.sourceName
            loaded.stylePreviewFiles = stylePreviews
            loaded.also { session = it }.snapshot()
        }
    }

    override suspend fun openProject(projectId: Long): EditorSnapshot =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val project = projectDao.findById(projectId)
                    ?: throw IllegalArgumentException("That recent project no longer exists")
                val apkBytes = project.localApkPath
                    ?.let(::File)
                    ?.takeIf(File::isFile)
                    ?.readBytes()
                    ?: throw WatchFaceException(
                        "This project's package is missing. Download the face again.",
                        "missing local APK for project ${project.id}",
                    )
                val loaded = loadSession(
                    apkBytes = apkBytes,
                    fallbackName = project.displayName,
                    projectId = project.id,
                    editedBinPath = project.editedBinPath,
                    activeStyleName = project.selectedStyle,
                )
                val localApk = project.localApkPath
                    .let(::File)
                    .takeIf(File::isFile)
                    ?: File(projectDirectory(project.id), "source.apk").also {
                        writeAtomically(it, apkBytes)
                    }
                // Before the row, for the reason [importProject] records: the emission
                // this write produces is the one the projects list draws from, and it lists
                // the previews directory as it stands at that moment.
                val stylePreviews = writeStylePreviews(project.id, loaded.apk)
                projectDao.insert(
                    project.copy(
                        faceName = loaded.apk.faceName,
                        importedAtEpochMillis = System.currentTimeMillis(),
                        localApkPath = localApk.absolutePath,
                    ),
                )
                loaded.projectName = project.resolvedName
                loaded.stylePreviewFiles = stylePreviews
                loaded.also { session = it }.snapshot()
            }
        }

    override suspend fun renameProject(projectId: Long, name: String) =
        withContext(Dispatchers.IO) {
            val trimmed = name.trim()
            // Silently ignored rather than refused. The only caller is a dialog, and a
            // dialog that can fail on an empty field is a dialog someone gets stuck in;
            // an empty title would also leave a row with nothing to identify it by.
            if (trimmed.isEmpty()) return@withContext
            mutex.withLock {
                // A targeted UPDATE, not an insert of a whole row: the editor may be
                // holding an older copy of this project, and writing that back would undo
                // the commit it has not seen.
                projectDao.rename(projectId, trimmed)
                // The open session holds its own copy, and every snapshot is built from it.
                // Without this the editor goes on showing the old name until it is closed
                // and reopened — which is most of the time, since the rename is made there.
                session?.takeIf { it.projectId == projectId }?.projectName = trimmed
            }
            Unit
        }

    override suspend fun duplicateProject(projectId: Long): DuplicatedProject =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val source = projectDao.findById(projectId)
                    ?: throw WatchFaceException(
                        "That project no longer exists.",
                        "duplicate: no row for project $projectId",
                    )
                // Refused up front rather than half-done. A project whose package has gone
                // missing cannot be opened, and copying the row would produce a second one
                // that cannot be opened either — with a name suggesting it is a working
                // copy of something.
                val sourceApk = source.localApkPath
                    ?.let(::File)
                    ?.takeIf(File::isFile)
                    ?: throw WatchFaceException(
                        "This project's package is missing, so it cannot be copied. " +
                            "Download the face again.",
                        "duplicate: missing local APK for project $projectId",
                    )
                val now = System.currentTimeMillis()
                val copy = source.copy(
                    id = 0,
                    projectName = ProjectNaming.defaultName(
                        base = source.resolvedName,
                        taken = projectDao.findByFaceId(source.faceId)
                            .map(ProjectEntity::resolvedName),
                    ),
                    importedAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    // Both are absolute paths into the *original's* directory, and copying
                    // them is the one mistake this whole function exists to avoid: the
                    // duplicate would load the original's edit, and deleting the original
                    // would take the duplicate's package with it. They are rewritten below,
                    // once an id of its own names a directory.
                    localApkPath = null,
                    editedBinPath = null,
                )
                val newId = projectDao.insert(copy)
                // The same shape as `openPackage`, and for the same reason: the id is what
                // names the directory, so the row has to exist before the files can be
                // written, and `NonCancellable` is what stops a cancellation landing between
                // the two and leaving a row naming no package.
                try {
                    withContext(NonCancellable) {
                        val target = projectDirectory(newId).apply { mkdirs() }
                        val localApk = File(target, "source.apk")
                        copyFileAtomically(sourceApk, localApk)
                        val editedBin = source.editedBinPath
                            ?.let(::File)
                            ?.takeIf(File::isFile)
                            ?.let { edited ->
                                File(target, "edited.bin")
                                    .also { copyFileAtomically(edited, it) }
                            }
                        // These two are found by convention rather than by a stored path,
                        // so they are copied under the names the reader looks for. The
                        // session file is not optional decoration: it holds the removed
                        // widget records, and without it a duplicate of an edit that cut a
                        // widget out would show it missing with no way to put it back.
                        File(projectDirectory(projectId), "session.json")
                            .takeIf(File::isFile)
                            ?.let { copyFileAtomically(it, File(target, "session.json")) }
                        previewsDirectory(projectId).listFiles().orEmpty()
                            .filter(File::isFile)
                            .forEach {
                                copyFileAtomically(it, File(previewsDirectory(newId), it.name))
                            }
                        projectDao.insert(
                            copy.copy(
                                id = newId,
                                localApkPath = localApk.absolutePath,
                                editedBinPath = editedBin?.absolutePath,
                            ),
                        )
                    }
                } catch (error: Throwable) {
                    // The DAO directly, never `deleteProject`: that takes `mutex`, which
                    // this block is already holding and which is not reentrant.
                    withContext(NonCancellable) { projectDao.deleteById(newId) }
                    projectDirectory(newId).deleteRecursively()
                    throw error
                }
                diagnostics.info(
                    TAG,
                    "Duplicated a project",
                    "from=$projectId to=$newId face=${source.faceId}",
                )
                DuplicatedProject(newId, copy.resolvedName)
            }
        }

    override suspend fun deleteProject(projectId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            projectDao.findById(projectId) ?: return@withLock
            if (session?.projectId == projectId) session = null
            projectDao.deleteById(projectId)
            projectDirectory(projectId).deleteRecursively()
        }
    }

    /**
     * Writes the project out as an archive the app can open again.
     *
     * Streamed from `source.apk` straight into the picked document, so the 32 MiB the
     * package can run to is never held: [ProjectArchive.pack] copies one member at a time
     * and the largest of those is the container. The edit and the session records are read
     * whole because both are bounded by `WATCH_CONTAINER_BYTE_CEILING` and a few hundred
     * records respectively.
     *
     * Takes [mutex] for the reason every other project operation does: a commit rewrites
     * `edited.bin` and `session.json` in that order, and an export landing between the two
     * would pair one project's container with another's removals.
     */
    override suspend fun exportProject(
        projectId: Long,
        destinationUri: String,
    ): ExportedProject = withContext(Dispatchers.IO) {
        mutex.withLock {
            val project = projectDao.findById(projectId)
                ?: throw WatchFaceException(
                    "That project no longer exists.",
                    "export: no row for project $projectId",
                )
            // Refused up front rather than half-written. The pristine container is what
            // makes an archive openable at all — it is what `Fit3Apk.parse` reads and what
            // every resize resamples from — so an archive without it is a file that looks
            // like a project and cannot become one.
            val sourceApk = project.localApkPath
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?: throw WatchFaceException(
                    "This project's package is missing, so it cannot be exported. " +
                        "Download the face again.",
                    "export: missing local APK for project $projectId",
                )
            val directory = projectDirectory(projectId)
            val edited = project.editedBinPath
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?.readBytes()
            val manifest = ProjectManifest(
                projectName = project.resolvedName,
                faceId = project.faceId,
                displayName = project.displayName,
                faceName = project.faceName,
                sourceUri = project.sourceUri,
                productId = project.productId,
                packageVersionCode = project.packageVersionCode,
                styleId = project.styleId,
                selectedStyle = project.selectedStyle,
                exportedAtEpochMillis = System.currentTimeMillis(),
                exportedByVersion = context.installedIdentity()?.label,
            )
            val counted = try {
                // `wt` truncates, which matters when the picker was pointed at a file that
                // already exists: without it a shorter archive would leave the tail of the
                // longer one behind and the result would not be a readable zip. Not every
                // provider implements the mode, so the plain call is the fallback rather
                // than a reason the export fails.
                val stream = runCatching { contentResolver.openOutputStream(Uri.parse(destinationUri), "wt") }
                    .getOrNull()
                    ?: contentResolver.openOutputStream(Uri.parse(destinationUri))
                    ?: throw WatchFaceException(
                        "That location could not be written to.",
                        "export: no output stream for the chosen document",
                    )
                CountingOutputStream(stream).also { output ->
                    sourceApk.inputStream().use { input ->
                        ProjectArchive.pack(
                            source = input,
                            destination = output,
                            manifest = manifest,
                            editedContainer = edited,
                            sessionState = File(directory, "session.json")
                                .takeIf(File::isFile)
                                ?.readBytes(),
                        )
                    }
                }
            } catch (error: WatchFaceException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw WatchFaceException(
                    "That project could not be exported.",
                    "export: ${error.message}",
                    error,
                )
            }
            diagnostics.info(
                TAG,
                "Exported a project",
                "project=$projectId face=${project.faceId} bytes=${counted.count} edited=${edited != null}",
            )
            ExportedProject(project.resolvedName, counted.count)
        }
    }

    /**
     * Reads an archive into a new project.
     *
     * The archive is stored **as the project's `source.apk`**, unaltered. That is the whole
     * reason the format keeps the package's own member names: from here on this project is
     * one `Fit3Apk.parse` away from a downloaded one, and nothing below — opening,
     * duplicating, the pristine container a resize resamples from, the install payload —
     * has a case for it.
     *
     * Everything is checked before a row is written, and the checks are the ones whose
     * failure would otherwise surface as a wrong picture rather than as an error: the
     * package half has to parse and the container has to validate, the edited container
     * has to validate too, and the two have to describe the same face. A container the
     * watch would refuse must not become a project someone spends an evening on.
     */
    override suspend fun importProject(sourceUri: String): ImportedProject =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val bytes = readArchive(sourceUri)
                val raw = try {
                    ProjectArchive.read(bytes)
                } catch (error: ProjectArchiveException) {
                    throw WatchFaceException(
                        "That file is not a FitFace Studio project: ${error.message}",
                        "import: ${error.message}",
                        error,
                    )
                }
                // Every string in the manifest is attacker-shaped: it came out of a JSON file
                // this app did not write, and four of them go straight into a database row and
                // onto a screen. Bounded and shape-checked here, at the boundary, rather than
                // trusted the length of the file.
                val contents = raw.copy(manifest = raw.manifest.sanitised())
                // The same funnel a download goes through, so an archive carrying a
                // container this app cannot open is refused in the same words.
                val loaded = loadSession(
                    apkBytes = bytes,
                    fallbackName = contents.manifest.displayName,
                )
                if (loaded.apk.faceId != contents.manifest.faceId) {
                    throw WatchFaceException(
                        "That project file describes one watch face and carries another.",
                        "import: manifest=${contents.manifest.faceId} " +
                            "container=${loaded.apk.faceId}",
                    )
                }
                val edited = contents.editedContainer?.let { validateEdited(it, loaded) }
                val now = System.currentTimeMillis()
                val siblings = projectDao.findByFaceId(loaded.apk.faceId)
                val project = ProjectEntity(
                    id = 0,
                    displayName = contents.manifest.displayName,
                    // Provenance carried through verbatim so `isOutdated` and the face
                    // sheet answer the same as they did for the project this came from.
                    // The three parsed columns beside it are what anything actually reads.
                    sourceUri = contents.manifest.sourceUri
                        ?: "fit3-archive://${loaded.apk.faceId}",
                    faceId = loaded.apk.faceId,
                    faceName = loaded.apk.faceName ?: contents.manifest.faceName,
                    importedAtEpochMillis = now,
                    localApkPath = null,
                    editedBinPath = null,
                    selectedStyle = contents.manifest.selectedStyle,
                    // Named against this library's projects, not the exporting one's: the
                    // archive's own name may already be taken here, and two rows reading
                    // alike on one face is exactly what `ProjectNaming` exists for.
                    projectName = ProjectNaming.defaultName(
                        base = contents.manifest.projectName.ifBlank {
                            loaded.apk.faceName ?: contents.manifest.displayName
                        },
                        taken = siblings.map(ProjectEntity::resolvedName),
                    ),
                    productId = contents.manifest.productId,
                    packageVersionCode = contents.manifest.packageVersionCode,
                    styleId = contents.manifest.styleId,
                    updatedAtEpochMillis = now,
                )
                val newId = projectDao.insert(project)
                // The row first, then the files, under `NonCancellable` — `openPackage`'s
                // shape and for its reason: the id is what names the directory, and a
                // cancellation landing between the two writes leaves a row naming no
                // package, which is a project that can only ever be refused.
                val name = try {
                    withContext(NonCancellable) {
                        val localApk = File(projectDirectory(newId).apply { mkdirs() }, "source.apk")
                        writeAtomically(localApk, bytes)
                        val editedFile = edited?.let {
                            File(projectDirectory(newId), "edited.bin").also { file ->
                                writeAtomically(file, it)
                            }
                        }
                        contents.sessionState?.takeIf { edited != null }?.let {
                            writeAtomically(File(projectDirectory(newId), "session.json"), it)
                        }
                        // Written here rather than left to the first `openProject`, which is
                        // where a downloaded project gets them — and written **before** the
                        // row below, which is the half that actually matters.
                        //
                        // `observeProjects` maps every DAO emission through
                        // `projectPreviewImage`, which lists this directory. The last write
                        // to the table is what produces the last emission, so previews
                        // written after it are previews no emission has seen: the row sits
                        // in the list with no thumbnail until something else touches the
                        // table, which in practice means opening the project. Room's
                        // invalidation is asynchronous, so with the two the other way round
                        // it is a race — the thumbnail appeared on a fast import and not on
                        // a slow one, which is worse than never appearing at all.
                        writeStylePreviews(newId, loaded.apk)
                        projectDao.insert(
                            project.copy(
                                id = newId,
                                localApkPath = localApk.absolutePath,
                                editedBinPath = editedFile?.absolutePath,
                            ),
                        )
                        project.resolvedName
                    }
                } catch (error: Throwable) {
                    // The DAO directly, never `deleteProject`: that takes `mutex`, which
                    // this block already holds and which is not reentrant.
                    withContext(NonCancellable) { projectDao.deleteById(newId) }
                    projectDirectory(newId).deleteRecursively()
                    throw error
                }
                diagnostics.info(
                    TAG,
                    "Imported a project",
                    "project=$newId face=${loaded.apk.faceId} bytes=${bytes.size} " +
                        "edited=${edited != null} schema=${contents.manifest.schema}",
                )
                ImportedProject(newId, name)
            }
        }

    /**
     * The archive's bytes, refusing to read past [ProjectArchive.MaxArchiveBytes].
     *
     * A `content://` document is whatever the provider says it is, and nothing here chose
     * the file — so the ceiling is checked while reading rather than taken from the
     * provider's reported length, which it is under no obligation to get right.
     */
    private fun readArchive(sourceUri: String): ByteArray {
        val stream = try {
            contentResolver.openInputStream(Uri.parse(sourceUri))
        } catch (error: Exception) {
            throw WatchFaceException(
                "That file could not be opened.",
                "import: ${error.message}",
                error,
            )
        } ?: throw WatchFaceException(
            "That file could not be opened.",
            "import: no input stream for the chosen document",
        )
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = ProjectArchive.MaxArchiveBytes
        stream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                remaining -= count
                if (remaining < 0) {
                    throw WatchFaceException(
                        "That file is too large to be an exported project.",
                        "import: past the ${ProjectArchive.MaxArchiveBytes} byte ceiling",
                    )
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    /**
     * The archive's edited container, checked against the pristine one it will sit beside.
     *
     * Two checks, and each one catches a failure that would otherwise be silent. A container
     * that does not validate is one `validatedBytes()` refuses to send, and finding that out
     * on the Install page — after the edits have been reviewed — is finding it out too late.
     * And the entry paths have to match the pristine container's, which is what an
     * `edited.bin` from a different face fails: no edit in this app adds or removes a
     * container entry, so the two lists are equal for every project the app itself wrote.
     * An edit that ever does change them has to relax this, and would know to.
     */
    private fun validateEdited(bytes: ByteArray, loaded: Session): ByteArray {
        // Refused here rather than left to `validatedBytes()`. A container past the ceiling
        // transfers, is accepted and leaves the old face up — the failure looks exactly like
        // success — and this app's own `rebuild` refuses to grow one past it, so a container
        // over the line is one no export of ours produced. Finding that out on the Install
        // page, after the edits have been reviewed, is finding it out too late.
        if (bytes.size > WATCH_CONTAINER_BYTE_CEILING) {
            throw WatchFaceException(
                "That project's saved edit is larger than the watch will accept " +
                    "(${mebibytes(WATCH_CONTAINER_BYTE_CEILING)}), so it was not imported.",
                "import: edited container ${bytes.size} > $WATCH_CONTAINER_BYTE_CEILING",
            )
        }
        val container = try {
            Fit3Container.parse(bytes)
        } catch (error: Fit3FormatException) {
            throw WatchFaceException(
                "That project's saved edit is not a readable watch-face container.",
                "import: edited container ${error.message}",
                error,
            )
        }
        val report = container.validate()
        if (!report.isValid) {
            throw WatchFaceException(
                "That project's saved edit would not be accepted by the watch, so it was " +
                    "not imported.",
                "import: edited container invalid: ${report.errors.joinToString { it.code }}",
            )
        }
        val expected = loaded.originalContainer.entries.map(ContainerEntry::path)
        val actual = container.entries.map(ContainerEntry::path)
        if (expected != actual) {
            throw WatchFaceException(
                "That project's saved edit does not belong to the watch face beside it.",
                "import: entry mismatch expected=${expected.size} actual=${actual.size}",
            )
        }
        return bytes
    }

    override suspend fun currentSnapshot(styleName: String?): EditorSnapshot =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val current = requireSession()
                current.snapshot(styleName).also {
                    if (styleName != null && current.projectId > 0) {
                        projectDao.findById(current.projectId)?.let { project ->
                            projectDao.insert(project.copy(selectedStyle = current.activeStyleName))
                        }
                    }
                }
            }
        }

    override suspend fun prepareReplacementImage(imageUri: String): ReplacementImage =
        withContext(Dispatchers.IO) {
            ReplacementImage(imageUri, imageSource.preview(imageUri))
        }

    override suspend fun replaceBackground(
        imageUri: String,
        placement: ImagePlacement,
    ): EditorSnapshot {
        val prepared = withContext(Dispatchers.Default) {
            mutex.withLock {
                val current = requireSession()
                val targets = current.backgroundTargetEntries()
                if (targets.isEmpty()) {
                    throw IllegalArgumentException("No editable style entries found")
                }
                // Measured against a style that actually carries a background, and the
                // selected one first. Reading style0 unconditionally made faces whose
                // first style paints onto black — 00011, and 00108 up to style3 —
                // refuse an edit their remaining styles could take.
                val image = current.selectedBackgroundRaster()
                    ?: throw WatchFaceException(
                        "No style of this face has a full-face background image to " +
                            "replace — it draws its widgets straight onto black.",
                        "no styleN.bin carries a panel-sized raster",
                    )
                PreparedBackground(
                    session = current,
                    container = current.currentContainer,
                    width = image.width,
                    height = image.height,
                    styleNames = targets.map { it.basename },
                )
            }
        }
        val pixels = withContext(Dispatchers.IO) {
            imageSource.decode(
                imageUri,
                prepared.width,
                prepared.height,
                placement,
            )
        }
        val snapshot = withContext(Dispatchers.Default) {
            mutex.withLock {
            val current = requireSession()
            if (current !== prepared.session || current.currentContainer !== prepared.container) {
                throw WatchFaceException(
                    "The project changed while the image was being prepared. Try applying it again.",
                )
            }
            val edit = FaceEditor.replaceBackgrounds(
                current.currentContainer,
                prepared.styleNames ?: current.backgroundTargetEntries().map { it.basename },
                prepared.width,
                prepared.height,
                pixels,
            )
            commit(
                current,
                edit.container,
                EditAuditSummary(
                    edit.changedPayloadBytes,
                    edit.changedStyles,
                    operation = "Manual background placement",
                ),
            )
            }
        }
        runCatching { setImageFit(placement.fit) }
        return snapshot
    }

    /**
     * See [WatchFaceRepository.addBackground]. Structured exactly like
     * [replaceBackground] — prepare under the lock, decode off it, commit under it again
     * — so the only difference is which format call runs and that the geometry comes from
     * the declared panel rather than from a raster that does not exist yet.
     */
    override suspend fun addBackground(
        imageUri: String,
        placement: ImagePlacement,
    ): EditorSnapshot {
        val prepared = withContext(Dispatchers.Default) {
            mutex.withLock {
                val current = requireSession()
                val scope = current.backgroundTargetEntries()
                val bare = scope.filter { FaceRecordParser.backgroundImage(it) == null }
                if (bare.size != scope.size) {
                    throw WatchFaceException(
                        "This face already has a background in at least one style, so it " +
                            "takes a same-size replacement instead.",
                        "backgroundless styles: ${bare.map { it.basename }}",
                    )
                }
                val panel = FaceRecordParser.panelSize(bare.first())
                if (panel.width <= 0 || panel.height <= 0) {
                    throw WatchFaceException(
                        "This face declares no panel geometry, so there is no size to " +
                            "add a background at.",
                    )
                }
                // A panel raster costs 205,880 bytes each and the watch ignores a container
                // over the ceiling, so a big face gets one in as many entries as fit — the
                // one on the canvas first, because it is the only one you can see. With AOD
                // selected that list is `aod.bin` alone; a normal style is never joined to
                // it, nor it to them.
                val targets = current.backgroundAddTargets()
                if (targets.isEmpty()) {
                    val cost = StructuralEditor.addedBackgroundBytes(panel.width, panel.height)
                    throw WatchFaceException(
                        "This face is already ${mebibytes(current.currentContainer.fileSize)} " +
                            "and a full-face background adds ${mebibytes(cost)} each, " +
                            "which would take it over the " +
                            "${mebibytes(WATCH_CONTAINER_BYTE_CEILING)} the watch accepts. " +
                            "Everything else on this face still works.",
                        "container=${current.currentContainer.fileSize} bare=${bare.size}",
                    )
                }
                PreparedBackground(
                    session = current,
                    container = current.currentContainer,
                    width = panel.width,
                    height = panel.height,
                    styleNames = targets,
                )
            }
        }
        val pixels = withContext(Dispatchers.IO) {
            imageSource.decode(imageUri, prepared.width, prepared.height, placement)
        }
        val snapshot = withContext(Dispatchers.Default) {
            mutex.withLock {
                val current = requireSession()
                if (current !== prepared.session ||
                    current.currentContainer !== prepared.container
                ) {
                    throw WatchFaceException(
                        "The project changed while the image was being prepared. Try " +
                            "applying it again.",
                    )
                }
                val targets = prepared.styleNames ?: current.styleEntries().map { it.basename }
                val edit = StructuralEditor.addBackgrounds(
                    source = current.currentContainer,
                    entryBasenames = targets,
                    width = prepared.width,
                    height = prepared.height,
                    argb = pixels,
                )
                commit(
                    current,
                    edit.container,
                    EditAuditSummary(
                        edit.changedPayloadBytes,
                        edit.changedStyles,
                        operation = if (targets == listOf(AOD_ENTRY_NAME)) {
                            "Added a full-face background to the always-on display"
                        } else if (targets.size == current.styleEntries().size) {
                            "Added a full-face background"
                        } else {
                            "Added a full-face background to ${targets.size} of " +
                                "${current.styleEntries().size} styles, the rest left on " +
                                "black to stay under the watch's size ceiling"
                        },
                    ),
                )
            }
        }
        runCatching { setImageFit(placement.fit) }
        return snapshot
    }

    override suspend fun tintBackground(
        red: Int,
        green: Int,
        blue: Int,
    ): EditorSnapshot = withContext(Dispatchers.Default) {
        mutex.withLock {
            val current = requireSession()
            if (current.selectedBackgroundRaster() == null) {
                throw WatchFaceException(
                    "No style of this face has a full-face background image to tint — it " +
                        "draws its widgets straight onto black.",
                    "no styleN.bin carries a panel-sized raster",
                )
            }
            val edit = FaceEditor.tintBackgrounds(
                current.currentContainer,
                current.backgroundTargetEntries().map { it.basename },
                red,
                green,
                blue,
            )
            commit(
                current,
                edit.container,
                EditAuditSummary(
                    edit.changedPayloadBytes,
                    edit.changedStyles,
                    operation = "Background tint",
                ),
            )
        }
    }

    override suspend fun editPairWidget(
        styleName: String,
        globalIndex: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        colorArgb: Int,
    ): EditorSnapshot = withContext(Dispatchers.Default) {
        mutex.withLock {
            val current = requireSession()
            val edit = FaceEditor.editPairWidget(
                source = current.currentContainer,
                entryBasename = styleName,
                globalIndex = globalIndex,
                sequenceId = sequenceId,
                x = x,
                y = y,
                colorArgb = colorArgb,
            )
            commit(
                current,
                edit.container,
                EditAuditSummary(
                    edit.changedPayloadBytes,
                    edit.changedStyles,
                    operation = "Pair widget position/color",
                ),
                styleName,
            )
        }
    }

    override suspend fun recolorPairWidget(
        styleName: String,
        globalIndex: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        colorArgb: Int,
        applyToAllStyles: Boolean,
    ): EditorSnapshot = withContext(Dispatchers.Default) {
        mutex.withLock {
            val current = requireSession()
            val styleNames = current.editTargets(styleName, applyToAllStyles)
            val edit = FaceEditor.recolorPairWidgetAcrossStyles(
                source = current.currentContainer,
                entryBasenames = styleNames,
                globalIndex = globalIndex,
                sequenceId = sequenceId,
                x = x,
                y = y,
                colorArgb = colorArgb,
            )
            commit(
                current,
                edit.container,
                EditAuditSummary(
                    edit.changedPayloadBytes,
                    edit.changedStyles,
                    operation = "Pair widget color changed " +
                        editScope(styleName, applyToAllStyles),
                ),
                styleName,
            )
        }
    }

    override suspend fun moveWidget(
        styleName: String,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        applyToAllStyles: Boolean,
    ): EditorSnapshot = withContext(Dispatchers.Default) {
        mutex.withLock {
            val current = requireSession()
            val styleNames = current.editTargets(styleName, applyToAllStyles)
            val edit = FaceEditor.moveWidgetAcrossStyles(
                source = current.currentContainer,
                entryBasenames = styleNames,
                globalIndex = globalIndex,
                widgetType = widgetType,
                sequenceId = sequenceId,
                x = x,
                y = y,
            )
            commit(
                current,
                edit.container,
                EditAuditSummary(
                    edit.changedPayloadBytes,
                    edit.changedStyles,
                    operation = "Widget moved " + editScope(styleName, applyToAllStyles),
                ),
                styleName,
            )
        }
    }

    override suspend fun resizeBackground(width: Int, height: Int): EditorSnapshot =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val current = requireSession()
                val targets = current.backgroundTargetEntries()
                val first = targets.firstOrNull()
                    ?: throw WatchFaceException("No editable style entries found")
                val background = FaceRecordParser.backgroundImage(first)
                    ?: throw WatchFaceException(
                        "This face has no full-face background image to resize.",
                        "${first.basename} carries no panel-sized raster",
                    )
                val frame = FaceRecordParser.decodeImage(first, background)
                val pixels = imageSource.resize(frame, width, height)
                val edit = StructuralEditor.resizeBackgrounds(
                    current.currentContainer,
                    targets.map { it.basename },
                    width,
                    height,
                    pixels,
                )
                commit(
                    current,
                    edit.container,
                    edit.audit("Background resize + pointer relocation"),
                )
            }
        }

    override suspend fun resizeWidget(
        styleName: String,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        applyToAllStyles: Boolean,
    ): EditorSnapshot = withContext(Dispatchers.Default) {
        mutex.withLock {
            val current = requireSession()
            val styleNames = current.editTargets(styleName, applyToAllStyles)
            val edit = StructuralEditor.resizeWidget(
                current.currentContainer,
                styleNames,
                globalIndex,
                widgetType,
                sequenceId,
                x,
                y,
                width,
                height,
                // Resample the vendor's artwork, never the last resize's output — the
                // same reason `reference` is read from the original container. Without
                // it, Smaller-then-Larger hands back a blurred sprite.
                pristine = current.originalContainer,
            )
            commit(
                current,
                edit.container,
                edit.audit("Widget resized " + editScope(styleName, applyToAllStyles)),
                styleName,
            )
        }
    }

    override suspend fun removeWidget(
        styleName: String,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        requireFinal: Boolean,
        applyToAllStyles: Boolean,
    ): EditorSnapshot = withContext(Dispatchers.Default) {
        mutex.withLock {
            val current = requireSession()
            val styleNames = current.editTargets(styleName, applyToAllStyles)
            val guide = FaceRecordParser.widgetGuides(
                current.currentContainer.entryByBasename(styleName),
            ).firstOrNull { it.globalIndex == globalIndex }
            val edit = try {
                StructuralEditor.removeWidget(
                    current.currentContainer,
                    styleNames,
                    globalIndex,
                    widgetType,
                    sequenceId,
                    x,
                    y,
                    requireFinal,
                )
            } catch (error: Fit3WidgetIsAnchorException) {
                // A refusal the reader can trigger by tapping a button they can see, so
                // it gets a sentence rather than the format layer's own wording.
                val others = error.dependentGlobalIndices.size
                throw WatchFaceException(
                    "This widget is what $others other " +
                        (if (others == 1) "widget is" else "widgets are") +
                        " positioned against, so removing it would move " +
                        (if (others == 1) "it" else "them") +
                        ". Move or remove " +
                        (if (others == 1) "that widget" else "those widgets") +
                        " first.",
                    error.message,
                    error,
                )
            }
            val removed = RemovedWidget(
                id = removedWidgetIds.incrementAndGet(),
                globalIndex = globalIndex,
                widgetType = widgetType,
                sequenceId = sequenceId,
                x = x,
                y = y,
                width = guide?.width ?: 0,
                height = guide?.height ?: 0,
                // Copied off the guide because it is about to stop existing: the record is
                // leaving the container, so nothing can look its reading up again.
                sourceLabel = guide?.sourceLabel,
                followsReading = guide?.followsReading ?: false,
                recordsByVariant = edit.removedRecords,
            )
            val previousRemoved = current.removedWidgets.toList()
            current.removedWidgets += removed
            try {
                commit(
                    current,
                    edit.container,
                    edit.audit("Widget removed " + editScope(styleName, applyToAllStyles)),
                    styleName,
                )
            } catch (error: Throwable) {
                current.removedWidgets.clear()
                current.removedWidgets += previousRemoved
                throw error
            }
        }
    }

    override suspend fun restoreWidget(removedId: Long): EditorSnapshot =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val current = requireSession()
                val removed = current.removedWidgets.firstOrNull { it.id == removedId }
                    ?: throw WatchFaceException("That removed widget is no longer available.")
                val edit = StructuralEditor.appendWidget(
                    current.currentContainer,
                    removed.recordsByVariant.keys.toList(),
                    removed.recordsByVariant,
                )
                val previousRemoved = current.removedWidgets.toList()
                current.removedWidgets.remove(removed)
                try {
                    commit(
                        current,
                        edit.container,
                        // No index in the sentence: `appendWidget` puts the record at the
                        // end, so the one it carried when it was cut is not where it lands
                        // and naming it here would be pointing at another widget.
                        edit.audit("Widget restored at the end of the table"),
                    )
                } catch (error: Throwable) {
                    current.removedWidgets.clear()
                    current.removedWidgets += previousRemoved
                    throw error
                }
            }
        }

    override suspend fun refreshThumbnail(): EditorSnapshot = withContext(Dispatchers.Default) {
        mutex.withLock {
            val current = requireSession()
            // `preview.bin` holds one frame per numbered style and none for AOD, and the
            // picture written into it is whatever the canvas is composing. So with AOD on
            // the canvas this would paint the always-on render into the *active style's*
            // frame — the face picker would show the AOD face for a style that looks
            // nothing like it. The button is hidden for AOD (`canRefreshThumbnail`), but
            // the refusal belongs here: a UI that forgets is not what keeps the two apart.
            if (current.selectedVariantName == AOD_ENTRY_NAME) {
                throw WatchFaceException(
                    "The face-picker thumbnail is rendered from a numbered style. Select " +
                        "one to update it.",
                    "selectedVariant=aod.bin",
                )
            }
            val snapshot = current.snapshot()
            val styleIndex = snapshot.styleNames.indexOf(snapshot.activeStyleName)
            if (styleIndex < 0) {
                throw WatchFaceException("The selected style is no longer part of this face.")
            }
            val edit = FaceEditor.replacePreviewThumbnail(
                current.currentContainer,
                styleIndex,
                snapshot.composedPreview,
            )
            if (edit == null) {
                // The stored thumbnail already is this edit, so there is nothing to
                // write. Recording that keeps the Validate page from asking again.
                current.thumbnailContainer = current.currentContainer
                return@withLock current.snapshot()
            }
            val previousThumbnail = current.thumbnailContainer
            current.thumbnailContainer = edit.container
            try {
                commit(
                    current,
                    edit.container,
                    EditAuditSummary(
                        edit.changedPayloadBytes,
                        edit.changedStyles,
                        operation = "Face-picker thumbnail re-rendered for " +
                            snapshot.activeStyleName,
                    ),
                )
            } catch (error: Throwable) {
                current.thumbnailContainer = previousThumbnail
                throw error
            }
        }
    }

    override suspend fun duplicateWidget(
        styleName: String,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        applyToAllStyles: Boolean,
    ): EditorSnapshot = withContext(Dispatchers.Default) {
        mutex.withLock {
            val current = requireSession()
            val styleNames = current.editTargets(styleName, applyToAllStyles)
            val edit = StructuralEditor.duplicateWidget(
                current.currentContainer,
                styleNames,
                globalIndex,
                widgetType,
                sequenceId,
                x,
                y,
            )
            commit(
                current,
                edit.container,
                edit.audit("Widget duplicated " + editScope(styleName, applyToAllStyles)),
                styleName,
            )
        }
    }

    override suspend fun resetEdits(): EditorSnapshot = withContext(Dispatchers.Default) {
        mutex.withLock {
            val current = requireSession()
            val previousContainer = current.currentContainer
            val previousAudit = current.audit
            val previousRemoved = current.removedWidgets.toList()
            val previousThumbnail = current.thumbnailContainer
            current.currentContainer = current.originalContainer
            current.audit = null
            current.removedWidgets.clear()
            current.thumbnailContainer = null
            try {
                val snapshot = current.snapshot()
                persistEdited(current, keepEdited = false)
                current.recordHistory("reset to the original container")
                diagnostics.info(TAG, "Edits reset")
                snapshot
            } catch (error: Throwable) {
                current.currentContainer = previousContainer
                current.audit = previousAudit
                current.removedWidgets.clear()
                current.removedWidgets += previousRemoved
                current.thumbnailContainer = previousThumbnail
                throw error
            }
        }
    }

    override suspend fun diagnosticsSection(): DiagnosticsSection? =
        // Validating a container is real work, and every other method here keeps it off
        // the caller's thread; this one is called straight out of a ViewModel's launch.
        withContext(Dispatchers.Default) {
            mutex.withLock { openSessionSection() }
        }

    private fun openSessionSection(): DiagnosticsSection? {
        val current = session ?: return null
        val report = current.currentContainer.validate()
        return DiagnosticsSection(
            title = "face",
            lines = buildList {
                add(
                    "face=${current.apk.faceId} style=${current.activeStyleName ?: "none"} " +
                        "canvas=${current.selectedVariantName ?: "none"}",
                )
                add(
                    "styles=${current.styleEntries().size} " +
                        "original=${current.originalContainer.fileSize} " +
                        "current=${current.currentContainer.fileSize} " +
                        "ceiling=$WATCH_CONTAINER_BYTE_CEILING",
                )
                add("removed=${current.removedWidgets.size} thumbnail=${current.thumbnailRefreshed}")
                add(
                    "validation=" + report.issues
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString("; ") { "${it.severity}:${it.code}" }
                        .orEmpty()
                        .ifEmpty { "clean" },
                )
                if (current.editHistory.isEmpty()) {
                    add("edits: none committed")
                } else {
                    // Says what it dropped. A trimmed list that looks complete would have
                    // a reader counting edits that are not there.
                    val dropped = current.editHistoryTotal - current.editHistory.size
                    add(
                        if (dropped == 0) {
                            "edits:"
                        } else {
                            "edits (the last ${current.editHistory.size} " +
                                "of ${current.editHistoryTotal}):"
                        },
                    )
                    current.editHistory.forEachIndexed { index, entry ->
                        add("  ${index + 1 + dropped}. $entry")
                    }
                }
            },
        )
    }

    override suspend fun prepareDirectInstall(): DirectInstallPayload =
        withContext(Dispatchers.Default) {
            mutex.withLock { requireSession().directInstallPayload() }
        }

    private suspend fun commit(
        current: Session,
        container: Fit3Container,
        audit: EditAuditSummary,
        styleName: String? = null,
    ): EditorSnapshot {
        val previousContainer = current.currentContainer
        val previousAudit = current.audit
        val previousActiveStyle = current.activeStyleName
        val previousVariant = current.selectedVariantName
        current.currentContainer = container
        current.audit = audit
        return try {
            val snapshot = current.snapshot(styleName)
            persistEdited(current, keepEdited = true)
            // Recorded only once the edit has actually stuck. The worst bugs here leave a
            // container that validates, transfers and is accepted while drawing wrong, so
            // nothing throws and this ordered list is the only account of what was done.
            current.recordHistory(
                "${audit.operation} " +
                    "(styles=${audit.changedStyles.size} bytes=${audit.changedPayloadBytes} " +
                    "delta=${audit.sizeDelta} size=${container.fileSize})",
            )
            diagnostics.info(
                TAG,
                "Edit committed: ${audit.operation}",
                "styles=${audit.changedStyles.joinToString("/")} " +
                    "delta=${audit.sizeDelta} size=${container.fileSize}",
            )
            snapshot
        } catch (error: Throwable) {
            current.currentContainer = previousContainer
            current.audit = previousAudit
            current.activeStyleName = previousActiveStyle
            current.selectedVariantName = previousVariant
            throw error
        }
    }

    /**
     * Writes an edit — or a reset — as one commit across the database and the disk.
     *
     * **The database pointer goes first, and that ordering is the whole fix.** It used to
     * come last: `edited.bin` was replaced, then `session.json`, then the row, and the row
     * is the only one of the three behind a cancellable suspension. So a commit that threw
     * or was cancelled at the DAO left the new container on disk while [commit] rolled
     * only memory back — and because an already-edited project's row names that same
     * pathname, the next open loaded the edit that had just been reported as failed.
     *
     * With the row first, every failure lands consistent instead, because
     * [writeAtomically] leaves the previous file intact when it throws and [loadSession]
     * treats a path that is not a file as no edit at all:
     *
     *  * row written, container write fails — the row names `edited.bin`, which still
     *    holds the previous edit, or does not exist yet on a first edit. Memory rolls
     *    back to exactly that.
     *  * row write fails — nothing on disk has been touched, and memory rolls back.
     *  * resetting, row written, delete fails — the row says there is no edit, so the
     *    file left behind is never read again.
     *
     * It also closes the cancellation window outright rather than compensating for it:
     * once the DAO returns, everything left is blocking I/O with no suspension point for
     * a cancellation to land on.
     */
    private suspend fun persistEdited(current: Session, keepEdited: Boolean) {
        if (current.projectId <= 0) return
        val project = projectDao.findById(current.projectId) ?: return
        val directory = projectDirectory(current.projectId)
        val editedFile = File(directory, "edited.bin")
        projectDao.insert(
            project.copy(
                editedBinPath = editedFile.absolutePath.takeIf { keepEdited },
                selectedStyle = current.activeStyleName,
                // The Projects page sorts on this. `importedAtEpochMillis` is bumped by
                // merely opening a project, so it cannot answer "which did I work on last"
                // — and with two projects on one face, that was the only thing telling
                // otherwise identical rows apart.
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        if (keepEdited) {
            writeAtomically(editedFile, current.currentContainer.toByteArray())
        } else {
            editedFile.delete()
        }
        persistSessionState(directory, current, keepEdited)
    }

    /**
     * Removed widget records live beside the edited BIN so "restore" survives
     * process death, exactly like the edit itself does.
     *
     * Failures propagate. They used to be swallowed by a bare `runCatching`, which broke
     * the guarantee in the line above without saying so: the container and the row could
     * both commit a removal while this file stayed missing or stale, and the widget came
     * back from the next launch with no way to restore it and nothing reported.
     */
    private fun persistSessionState(directory: File, current: Session, keepEdited: Boolean) {
        val file = File(directory, "session.json")
        if (!keepEdited || (current.removedWidgets.isEmpty() && !current.thumbnailRefreshed)) {
            file.delete()
            return
        }
        writeAtomically(
            file,
            json.encodeToString(
                StoredSessionState(
                    thumbnailRefreshed = current.thumbnailRefreshed,
                    removed = current.removedWidgets.map(::StoredRemovedWidget),
                ),
            ).toByteArray(),
        )
    }

    private fun restoreSessionState(directory: File, current: Session) {
        val file = File(directory, "session.json").takeIf(File::isFile) ?: return
        val stored = runCatching {
            json.decodeFromString<StoredSessionState>(file.readText())
        }.getOrNull() ?: return
        // The edited BIN on disk is the container whose preview.bin was rendered, so
        // the restored session's thumbnail is current for exactly that container.
        current.thumbnailContainer = current.currentContainer.takeIf {
            stored.thumbnailRefreshed
        }
        current.removedWidgets.clear()
        stored.removed.forEach { entry ->
            current.removedWidgets += entry.toModel(removedWidgetIds.incrementAndGet())
        }
    }

    private fun requireSession(): Session =
        session ?: throw IllegalStateException("Download a watch face from Marketplace first")

    private fun loadSession(
        apkBytes: ByteArray,
        fallbackName: String,
        projectId: Long = 0,
        editedBinPath: String? = null,
        activeStyleName: String? = null,
    ): Session {
        val apk = try {
            Fit3Apk.parse(apkBytes, retainMembers = false)
        } catch (error: Fit3NoContainerException) {
            throw WatchFaceException(
                if (error.hasFaceMetadata) {
                    "This face is customised on the watch, not shipped as an editable " +
                        "container. Its package has no watch-face binary, so there is " +
                        "nothing for FitFace Studio to open."
                } else {
                    "That package holds no Fit3 watch-face binary."
                },
                error.message,
                error,
                isUneditablePackage = true,
            )
        } catch (error: Fit3FormatException) {
            throw WatchFaceException(
                "That package is not a compatible Fit3 watch face.",
                error.message,
                error,
            )
        }
        val original = Fit3Container.parse(apk.binary)
        val current = editedBinPath
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.readBytes()
            ?.let(Fit3Container::parse)
            ?: original
        val resolvedName = fallbackName.ifBlank {
            "SM-R390_${apk.faceId}.apk"
        }
        return Session(
            projectId = projectId,
            apk = apk,
            originalContainer = original,
            currentContainer = current,
            sourceName = resolvedName,
            activeStyleName = activeStyleName,
        ).also {
            if (projectId > 0 && current !== original) {
                restoreSessionState(projectDirectory(projectId), it)
            }
        }
    }

    private fun projectDirectory(projectId: Long): File =
        File(context.filesDir, "projects/$projectId")

    private fun previewsDirectory(projectId: Long): File =
        File(projectDirectory(projectId), "previews")

    /**
     * Puts the package's own style previews on disk beside the project, so both the
     * Styles page and the projects list can show a face without decoding a container.
     *
     * Cosmetic, so it is best effort: a preview that cannot be written is simply
     * absent, never a reason a project fails to open.
     */
    private fun writeStylePreviews(projectId: Long, apk: Fit3Apk): Map<Int, String> {
        if (projectId <= 0) return emptyMap()
        return apk.stylePreviews.mapNotNull { (styleIndex, png) ->
            val file = File(previewsDirectory(projectId), "style$styleIndex.png")
            runCatching {
                if (!file.isFile || file.length() != png.size.toLong()) {
                    writeAtomically(file, png)
                }
                styleIndex to file.absolutePath
            }.getOrNull()
        }.toMap()
    }

    /**
     * The preview the projects list shows for [project]: the style it was last left
     * on, falling back to the first one the package shipped.
     */
    private fun projectPreviewImage(project: ProjectEntity): String? {
        val previews = previewsDirectory(project.id)
            .listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.mapNotNull { file ->
                PreviewFileNamePattern.matchEntire(file.name)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?.let { it to file }
            }
            ?.sortedBy { it.first }
            ?: return null
        if (previews.isEmpty()) return null
        val selected = project.selectedStyle?.let(::styleIndexOf)
        val chosen = previews.firstOrNull { it.first == selected } ?: previews.first()
        return chosen.second.absolutePath
    }

    /**
     * Copies one file, committing it under its final name only once it is whole.
     *
     * Streamed rather than read into a `ByteArray` and handed to [writeAtomically]: a
     * project's `source.apk` is up to 32 MiB, and a copy that allocates all of it is the
     * same hazard the package parser's inflate bounds exist for.
     */
    private fun copyFileAtomically(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        source.inputStream().use { input ->
            temporary.outputStream().use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Could not commit ${target.name}")
        }
    }

    /**
     * Counts what went through, so an export can say how big it came out.
     *
     * The size cannot be read back off the document afterwards — a provider is not obliged
     * to report one, and the picker may have written somewhere this app has no further
     * access to — and it is the one fact about an export a reader cannot check from inside
     * the app.
     */
    private class CountingOutputStream(private val sink: OutputStream) : FilterOutputStream(sink) {
        var count: Long = 0
            private set

        override fun write(value: Int) {
            sink.write(value)
            count++
        }

        // `FilterOutputStream` implements this as a loop over the single-byte `write`, which
        // would count correctly and write a zip one byte at a time through a content
        // provider. Forwarding the whole array is the reason this override exists.
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            sink.write(bytes, offset, length)
            count += length
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.outputStream().use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Could not commit ${target.name}")
        }
    }

    private data class Session(
        var projectId: Long,
        val apk: Fit3Apk,
        val originalContainer: Fit3Container,
        var currentContainer: Fit3Container,
        val sourceName: String,
        /** Read from the project row, and rewritten in place by a rename. */
        var projectName: String = sourceName,
        var audit: EditAuditSummary? = null,
        /** The style that installs and supplies the sampler id — never `aod.bin`. */
        var activeStyleName: String? = null,
        /**
         * What the canvas currently shows and edits — a numbered style or `aod.bin`.
         *
         * Split from [activeStyleName] so that looking at AOD never changes which style is
         * queued to install: selecting AOD updates this alone, selecting a style updates
         * both together. See [snapshot].
         */
        var selectedVariantName: String? = null,
        /** Style index → the package's preview for it, extracted to app storage. */
        var stylePreviewFiles: Map<Int, String> = emptyMap(),
        val removedWidgets: MutableList<RemovedWidget> = mutableListOf(),
        /**
         * Committed edits and resets, in order, for the bug report. Never persisted, and
         * bounded like [DiagnosticsLog] is — a session left open all afternoon would
         * otherwise grow a report nobody can read. Written through [recordHistory] so the
         * count of what was dropped survives.
         */
        val editHistory: MutableList<String> = mutableListOf(),
        /** How many entries [editHistory] has been given, including any it has dropped. */
        var editHistoryTotal: Int = 0,
        /**
         * The container whose `preview.bin` was rendered from the current edit. Held
         * as an identity rather than a flag so that any later edit — which replaces
         * [currentContainer] — automatically marks the thumbnail stale and lets the
         * Validate page re-render it.
         */
        var thumbnailContainer: Fit3Container? = null,
    ) {
        private val originalReport = originalContainer.validate()

        val thumbnailRefreshed: Boolean
            get() = thumbnailContainer != null && thumbnailContainer === currentContainer

        /**
         * The numbered styles, in numeric order — which is the order `preview.bin`
         * frames and the packaged style pictures are indexed in.
         *
         * Everything that counts styles, picks an install sampler or indexes a preview
         * reads this. [variantEntries] is the other list, and the difference between
         * them is the isolation model.
         */
        fun styleEntries(): List<ContainerEntry> =
            FaceResources.selectableStyles(currentContainer)

        /** The styles plus `aod.bin` — every entry the editor can put on the canvas. */
        fun variantEntries(): List<ContainerEntry> =
            FaceResources.variantEntries(currentContainer)

        /**
         * Adds one line to the report's account of this session, oldest dropped first.
         *
         * A reset goes through here too. Without one the report listed edits the
         * container no longer carried, with nothing saying they had been reverted — which
         * points a reader at the wrong container while they are trying to work out why a
         * face draws wrong.
         */
        fun recordHistory(entry: String) {
            editHistoryTotal++
            editHistory += entry
            while (editHistory.size > MaxEditHistory) editHistory.removeFirst()
        }

        /**
         * The styles an added background would be written to: all of them where the
         * container has room, otherwise the selected style plus as many siblings as fit
         * under [WATCH_CONTAINER_BYTE_CEILING]. Empty when there is no room for one.
         *
         * Only meaningful on a face where no style carries a background — a face that has
         * one anywhere takes the same-size replacement instead, which changes no sizes.
         */
        /**
         * Scoped to [backgroundTargetEntries], so with AOD on the canvas the answer is
         * about `aod.bin` alone and never mentions a style. The size decision itself is
         * [StructuralEditor.backgroundStylesThatFit]'s — it already skips an entry that
         * has a background or no panel geometry and stops at the watch's ceiling, and a
         * second copy of that arithmetic here is a second copy to keep in step.
         */
        fun backgroundAddTargets(): List<String> = StructuralEditor.backgroundStylesThatFit(
            source = currentContainer,
            entryBasenames = backgroundTargetEntries().map { it.basename },
            preferred = selectedVariantName,
        )

        /**
         * The full-panel raster a background edit is measured against: the selected
         * style's when it has one, otherwise the first sibling that does.
         *
         * Null means no style has one. Such a style can still be *given* one —
         * `StructuralEditor.addBackgrounds` is device-proven — but only where the added
         * raster keeps the container under the watch's size ceiling; see
         * [backgroundAddTargets]. [FaceEditor.replaceBackgrounds] writes every style that
         * does carry one, so the size taken from here has to be the size they share.
         */
        fun backgroundRaster(): ImageRecord? {
            val entries = styleEntries()
            val preferred = activeStyleName?.let { name ->
                entries.singleOrNull { it.basename == name }
            }
            return listOfNotNull(preferred).plus(entries)
                .firstNotNullOfOrNull(FaceRecordParser::backgroundImage)
        }

        /** [backgroundRaster], but scoped to whatever [backgroundTargetEntries] resolves to. */
        fun selectedBackgroundRaster(): ImageRecord? =
            if (selectedVariantName == AOD_ENTRY_NAME) {
                backgroundTargetEntries().firstOrNull()?.let(FaceRecordParser::backgroundImage)
            } else {
                backgroundRaster()
            }

        fun targetStyleNames(styleName: String, applyToAllStyles: Boolean): List<String> =
            if (applyToAllStyles) {
                buildList {
                    add(styleName)
                    addAll(styleEntries().map { it.basename }.filterNot { it == styleName })
                }
            } else {
                listOf(styleName)
            }

        /**
         * The entry basenames one widget edit should touch, given what is selected.
         *
         * The one rule every operation shares: AOD is edited alone, always, whatever
         * [applyToAllStyles] says, and normal-style apply-to-all never reaches into it.
         * Deciding this here — once — is what makes it a repository guarantee instead of
         * something each call site has to remember to enforce; the UI hiding the apply-all
         * switch for AOD is a convenience, not the thing that makes isolation hold.
         */
        fun editTargets(styleName: String, applyToAllStyles: Boolean): List<String> =
            if (styleName == AOD_ENTRY_NAME) {
                listOf(AOD_ENTRY_NAME)
            } else {
                targetStyleNames(styleName, applyToAllStyles)
            }

        /**
         * The entries a background edit should touch: `aod.bin` alone when it is what is
         * selected, otherwise the normal styles [styleEntries] already scopes background
         * work to. AOD never joins a normal-style background edit and a normal-style
         * background edit never reaches AOD.
         */
        fun backgroundTargetEntries(): List<ContainerEntry> =
            if (selectedVariantName == AOD_ENTRY_NAME) {
                listOfNotNull(FaceResources.aodOrNull(currentContainer))
            } else {
                styleEntries()
            }

        fun directInstallPayload(): DirectInstallPayload {
            val binary = validatedBytes()
            val faceId = apk.faceId.toIntOrNull()
                ?: throw WatchFaceException(
                    "This face has a non-numeric ID and cannot be installed.",
                    "faceId=${apk.faceId}",
                )
            val fileName = apk.binaryMember.substringAfterLast('/')
            val canonical = "SM-R390_${faceId.toString().padStart(5, '0')}_256x402.bin"
            if (fileName != canonical) {
                throw WatchFaceException(
                    "The container filename does not match its face ID, so it will not " +
                        "be installed.",
                    "fileName=$fileName expected=$canonical",
                )
            }
            val styleCount = styleEntries().size
            val samplerId = activeStyleName
                ?.removePrefix("style")
                ?.removeSuffix(".bin")
                ?.toIntOrNull()
                ?.takeIf { it in 0 until styleCount }
                ?: apk.samplerId?.takeIf { it in 0 until styleCount }
                ?: throw WatchFaceException(
                    "FitFace Studio could not work out which style to activate.",
                    "activeStyleName=$activeStyleName styles=$styleCount",
                )
            return DirectInstallPayload.create(
                faceId = faceId,
                samplerId = samplerId,
                fileName = fileName,
                bytes = binary,
            )
        }

        /**
         * Everything the app can check about a container before it is allowed near
         * the watch. A malformed or half-written BIN is the one failure mode with no
         * in-app recovery, so this is deliberately fail-closed.
         */
        fun validatedBytes(): ByteArray {
            val binary = currentContainer.toByteArray()
            if (binary.size < CONTAINER_HEADER_SIZE) {
                throw WatchFaceException("The edited watch face is truncated.")
            }
            // A container over the ceiling transfers, is acknowledged, and leaves the
            // watch on the old face — the one failure that looks like success. No edit
            // can produce one any more; this is the backstop for a project saved by an
            // older build.
            if (binary.size > WATCH_CONTAINER_BYTE_CEILING) {
                throw WatchFaceException(
                    "The edited watch face is ${mebibytes(binary.size)}, over the " +
                        "${mebibytes(WATCH_CONTAINER_BYTE_CEILING)} the watch accepts. It " +
                        "would install without updating the face, so it is not sent. Reset " +
                        "the project edits and try a smaller change.",
                    "size=${binary.size} ceiling=$WATCH_CONTAINER_BYTE_CEILING",
                )
            }
            val reparsed = try {
                Fit3Container.parse(binary)
            } catch (error: Fit3FormatException) {
                throw WatchFaceException(
                    "The edited watch face no longer parses and will not be installed.",
                    error.message,
                    error,
                )
            }
            if (reparsed.header.magic != "oppo") {
                throw WatchFaceException(
                    "The edited watch face lost its container signature.",
                    "magic=${reparsed.header.magic}",
                )
            }
            val report = reparsed.validate()
            if (!report.isValid) {
                throw WatchFaceException(
                    "The edited watch face failed validation and will not be installed.",
                    report.errors.joinToString { it.code },
                )
            }
            val blocking = report.warnings.map { it.code }.filter { it in BlockingWarnings }
            if (blocking.isNotEmpty()) {
                throw WatchFaceException(
                    "The edited watch face has a damaged layout and will not be installed.",
                    blocking.joinToString(),
                )
            }
            if (!reparsed.toByteArray().contentEquals(binary)) {
                throw WatchFaceException(
                    "The edited watch face did not round-trip byte-identically.",
                )
            }
            // Container CRCs can be perfectly valid over payloads whose internal
            // record tables are broken, so every editable entry is re-walked too.
            val styles = FaceResources.variantEntries(reparsed)
            if (styles.none { it.basename.startsWith("style") }) {
                throw WatchFaceException("The edited watch face has no style entries left.")
            }
            styles.forEach { entry ->
                try {
                    FaceRecordParser.scanWidgets(entry)
                    FaceRecordParser.scanImages(entry)
                } catch (error: Fit3FormatException) {
                    throw WatchFaceException(
                        "${entry.basename} in the edited watch face is malformed and will " +
                            "not be installed.",
                        error.message,
                        error,
                    )
                }
            }
            return binary
        }

        /**
         * The style rendered at panel size: its full-panel background raster when it
         * has one, otherwise the unlit black panel the watch actually shows behind
         * the widgets.
         */
        private fun panelFrame(entry: ContainerEntry): PreviewFrame {
            FaceRecordParser.backgroundImage(entry)?.let {
                return FaceRecordParser.decodeImage(entry, it)
            }
            val panel = FaceRecordParser.panelSize(entry)
            if (panel.width <= 0 || panel.height <= 0) {
                throw IllegalArgumentException(
                    "${entry.basename} declares no panel geometry and holds no rasters",
                )
            }
            return PreviewFrame(
                width = panel.width,
                height = panel.height,
                argb = IntArray(panel.width * panel.height) { OPAQUE_BLACK },
            )
        }

        /** The container and `aod.bin` payload [cachedAodPreview] was rendered from. */
        private class AodRender(
            val container: Fit3Container,
            val payload: ByteArray,
            val preview: WidgetPreview,
            val locale: String,
        )

        private var cachedAodPreview: AodRender? = null

        private fun previewLocale(): String = java.util.Locale.getDefault().let {
            when (it.language) {
                "zh" -> if (it.script == "Hant" || it.country in setOf("TW", "HK", "MO")) "cn2" else "cn0"
                "pt" -> "pt_rPT"
                "fr", "ko", "ja", "it" -> it.language
                else -> "en"
            }
        }

        /**
         * [WidgetPreviewComposer.compose] for the current `aod.bin`. Null when the container
         * carries none.
         *
         * Memoized because every snapshot needs it — the Styles page shows the AOD row a
         * thumbnail whichever variant is selected — and a snapshot is taken on every
         * commit, which on a press-and-hold nudge is many a second. Two steps, cheapest
         * first: the same container object cannot have changed at all, and a rebuild that
         * left `aod.bin` byte-identical (every normal-style edit) reuses the render too.
         *
         * The second step compares the payload rather than hashing it. Both are one pass
         * over the entry, but a 32-bit hash can collide, and a collision here would keep
         * showing the pre-edit picture as though the edit had not landed.
         */
        fun aodPreview(): WidgetPreview? {
            val entry = FaceResources.aodOrNull(currentContainer) ?: return null
            val locale = previewLocale()
            cachedAodPreview?.takeIf { it.locale == locale }?.let { cached ->
                if (cached.container === currentContainer) return cached.preview
                if (cached.payload.contentEquals(entry.data) &&
                    cached.container.entries.filter { it.basename.startsWith("font_") }
                        .map { it.basename to it.data.toList() } ==
                    currentContainer.entries.filter { it.basename.startsWith("font_") }
                        .map { it.basename to it.data.toList() }) {
                    cachedAodPreview = AodRender(currentContainer, entry.data, cached.preview, locale)
                    return cached.preview
                }
            }
            val preview = WidgetPreviewComposer.compose(
                entry, currentContainer.entries, WidgetTextRasterizer::render, locale,
            )
            cachedAodPreview = AodRender(currentContainer, entry.data, preview, locale)
            return preview
        }

        private var cachedEditedVariants: Pair<Fit3Container, Set<String>>? = null

        /**
         * Which of [variants] hold a payload the pristine container does not.
         *
         * Keyed on the container's identity: a rebuild replaces the object, so an
         * unchanged one cannot have changed its entries, and the comparison is not redone
         * for every snapshot taken of the same container.
         */
        fun editedVariantNames(variants: List<ContainerEntry>): Set<String> {
            if (currentContainer === originalContainer) return emptySet()
            cachedEditedVariants?.let { (container, names) ->
                if (container === currentContainer) return names
            }
            val names = variants.mapNotNullTo(mutableSetOf()) { entry ->
                val pristine = originalContainer.entries
                    .singleOrNull { it.basename == entry.basename }
                entry.basename.takeIf {
                    pristine == null || !pristine.data.contentEquals(entry.data)
                }
            }
            cachedEditedVariants = currentContainer to names
            return names
        }

        /**
         * Everything a snapshot needs that is derived from the *unedited* container.
         *
         * None of it can change while the session is open, but all of it used to be
         * recomputed on every commit — and a commit happens on every nudge, so a
         * press-and-hold recomputed it dozens of times.
         */
        private val originalStyleCache = mutableMapOf<String, Map<Int, WidgetGuide>>()

        private fun originalGuidesFor(styleName: String): Map<Int, WidgetGuide> =
            originalStyleCache.getOrPut(styleName) {
                FaceRecordParser.widgetGuides(originalContainer.entryByBasename(styleName))
                    .associateBy { it.globalIndex }
            }

        fun snapshot(requestedStyle: String? = null): EditorSnapshot {
            val styles = styleEntries()
            if (styles.isEmpty()) throw IllegalArgumentException("No editable style entries found")
            // Resolving the *requested/shown* entry has to reach `aod.bin` too, unlike
            // every other use of `styles` below — style counts, background targets, the
            // preview-frame index — which stay scoped to numbered styles on purpose.
            val variants = variantEntries()
            val selected = requestedStyle?.let { name ->
                variants.singleOrNull { it.basename == name }
                    ?: throw IllegalArgumentException("Unknown style: $name")
            } ?: selectedVariantName?.let { name ->
                variants.singleOrNull { it.basename == name }
            } ?: activeStyleName?.let { name ->
                styles.singleOrNull { it.basename == name }
            } ?: styles.first()
            selectedVariantName = selected.basename
            // Looking at AOD must never change which style installs — this is the one
            // conditional the whole isolation model rests on.
            if (selected.basename != AOD_ENTRY_NAME) activeStyleName = selected.basename
            val isAod = selected.basename == AOD_ENTRY_NAME
            val images = FaceRecordParser.scanImages(selected)
            val originalStyle = originalContainer.entryByBasename(selected.basename)
            // The canvas is the panel the watch renders, which is not the same thing
            // as "the style's first raster": faces 00022 and 00108 open with a small
            // icon, and a style with no full-panel raster simply draws onto black.
            val currentBackground = panelFrame(selected)
            val originalWidgets = originalGuidesFor(selected.basename)
            val duplicateSources = FaceRecordParser.duplicateSourceGlobalIndices(
                selected,
                originalStyle,
            )
            // A structural edit renumbers the table, so the original has to be resolved
            // by identity rather than by index — see `originalWidgetSources`.
            val originalSources = FaceRecordParser.originalWidgetSources(selected, originalStyle)
            val widgets = FaceRecordParser.widgetGuides(selected).map { widget ->
                val duplicateSource = duplicateSources[widget.globalIndex]
                val original = originalSources[widget.globalIndex]?.let(originalWidgets::get)
                    ?: duplicateSource?.let(originalWidgets::get)
                widget.copy(
                    originalX = original?.x ?: widget.x,
                    originalY = original?.y ?: widget.y,
                    // The origin has to come from the original too: an alignment target
                    // that has been moved, resized or renumbered by this edit sits
                    // somewhere else now, and the pixels to clear are the ones the old
                    // rectangle covered.
                    originalOriginX = original?.originX ?: widget.originX,
                    originalOriginY = original?.originY ?: widget.originY,
                    // A resize follows the new raster immediately; the reference
                    // render still shows the old one, so the composer needs the
                    // extent it was drawn at to know what to clear.
                    originalWidth = original?.width ?: widget.width,
                    originalHeight = original?.height ?: widget.height,
                    originalColorArgb = original?.colorArgb ?: widget.colorArgb,
                    duplicateSourceGlobalIndex = duplicateSource,
                )
            }
            // Every variant is reconstructed from its current records, in record order.
            // Pristine bytes remain useful for edit identity/resize, never for drawing.
            val aodComposition = aodPreview()
            val composition = if (isAod) requireNotNull(aodComposition) else {
                WidgetPreviewComposer.compose(
                    selected,
                    currentContainer.entries,
                    WidgetTextRasterizer::render,
                    previewLocale(),
                )
            }
            val widgetImageLayers = composition.widgetImageLayers
            val report = if (currentContainer === originalContainer) {
                originalReport
            } else {
                currentContainer.validate()
            }
            val backgrounds = styles.filter {
                FaceRecordParser.backgroundImage(it) != null
            }.map { it.basename }
            val aodEntry = FaceResources.aodOrNull(currentContainer)
            val variantModels = styles.mapIndexed { index, entry ->
                EditorVariant(entry.basename, VariantKind.STYLE, index)
            } + listOfNotNull(aodEntry?.let { EditorVariant(it.basename, VariantKind.AOD) })
            return EditorSnapshot(
                projectId = projectId,
                faceId = apk.faceId,
                faceName = apk.faceName,
                sourceName = sourceName,
                projectName = projectName,
                styleNames = styles.map { it.basename },
                variants = variantModels,
                selectedVariant = variantModels.first { it.basename == selected.basename },
                activeStyleName = activeStyleName ?: styles.first().basename,
                editedVariantNames = editedVariantNames(variants),
                aodThumbnail = aodComposition?.composed,
                selectedVariantApproximate = composition.isApproximate,
                preview = currentBackground,
                composedPreview = composition.composed,
                widgetOverlay = composition.widgetOverlay,
                widgetImageLayers = widgetImageLayers,
                widgets = widgets,
                removedWidgets = removedWidgets.toList(),
                stylePreviewPaths = styles.mapNotNull { entry ->
                    styleIndexOf(entry.basename)
                        ?.let(stylePreviewFiles::get)
                        ?.let { entry.basename to it }
                }.toMap(),
                backgroundStyles = backgrounds,
                aodHasBackground = aodEntry?.let {
                    FaceRecordParser.backgroundImage(it) != null
                } ?: false,
                aodCanTakeBackground = aodEntry != null &&
                    StructuralEditor.backgroundStylesThatFit(
                        source = currentContainer,
                        entryBasenames = listOf(AOD_ENTRY_NAME),
                        preferred = AOD_ENTRY_NAME,
                    ).isNotEmpty(),
                // Only worth costing where there is nothing to replace: a face that has a
                // background anywhere takes the same-size replacement, which grows nothing.
                backgroundAddTargets = if (backgrounds.isEmpty()) {
                    backgroundAddTargets()
                } else {
                    emptyList()
                },
                containerBytes = currentContainer.fileSize,
                imageCount = images.size,
                validationErrors = report.errors.map { it.code },
                validationWarnings = report.warnings.map { it.code },
                isDirty = currentContainer !== originalContainer,
                thumbnailRefreshed = thumbnailRefreshed,
                audit = audit,
            )
        }
    }

    /**
     * Validation warnings that describe a physically inconsistent body. Every one of
     * the 99 live catalogue containers is warning-free, so seeing one here means the
     * app produced something the watch should never receive.
     */
    private companion object {
        const val TAG = "Editor"

        /** How many lines of [Session.editHistory] a report carries. */
        const val MaxEditHistory = 60
        const val OPAQUE_BLACK = 0xFF00_0000.toInt()

        val BlockingWarnings = setOf(
            "overlapping_entry",
            "unreferenced_gap",
            "trailing_bytes",
            "unreferenced_body",
            "unterminated_path",
        )
    }

    private data class PreparedBackground(
        val session: Session,
        val container: Fit3Container,
        val width: Int,
        val height: Int,
        /** Which style entries the edit will write; null means "whichever carry one". */
        val styleNames: List<String>? = null,
    )
}

@Serializable
private data class StoredSessionState(
    val thumbnailRefreshed: Boolean = false,
    val removed: List<StoredRemovedWidget> = emptyList(),
)

/**
 * One removed record on disk.
 *
 * Every field added after the first release carries a default, because `session.json` is
 * read back from projects written by older builds and a missing key would otherwise throw
 * — and `loadSession` failing is a project that will not open with its removals in it.
 * [legacyLabel] is the one field that is only ever *read*: releases up to 0.1.1 stored the
 * assembled `"Widget #12"` string instead of the number, so it is where an old session's
 * index comes from and nothing new is written into it.
 */
@Serializable
private data class StoredRemovedWidget(
    val widgetType: Int,
    val sequenceId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** Base64 so the raw record bytes survive a JSON round trip untouched. */
    val recordsByVariant: Map<String, String>,
    val globalIndex: Int = UnknownGlobalIndex,
    val sourceLabel: String? = null,
    val followsReading: Boolean = false,
    @SerialName("label") val legacyLabel: String? = null,
) {
    constructor(widget: RemovedWidget) : this(
        widgetType = widget.widgetType,
        sequenceId = widget.sequenceId,
        x = widget.x,
        y = widget.y,
        width = widget.width,
        height = widget.height,
        recordsByVariant = widget.recordsByVariant.mapValues {
            Base64.getEncoder().encodeToString(it.value)
        },
        globalIndex = widget.globalIndex,
        sourceLabel = widget.sourceLabel,
        followsReading = widget.followsReading,
    )

    fun toModel(id: Long) = RemovedWidget(
        id = id,
        globalIndex = globalIndex.takeIf { it >= 0 } ?: legacyGlobalIndex(),
        widgetType = widgetType,
        sequenceId = sequenceId,
        x = x,
        y = y,
        width = width,
        height = height,
        sourceLabel = sourceLabel,
        followsReading = followsReading,
        recordsByVariant = recordsByVariant.mapValues { Base64.getDecoder().decode(it.value) },
    )

    /**
     * The index out of an older session's stored label, which this app wrote itself and
     * so can read back exactly. Anything else leaves the index unknown, which the row
     * renders as "—" rather than as a number it would be inventing.
     */
    private fun legacyGlobalIndex(): Int =
        legacyLabel?.removePrefix("Widget #")?.toIntOrNull() ?: UnknownGlobalIndex
}

/** [RemovedWidget.globalIndex] for a session that predates the field. */
private const val UnknownGlobalIndex = -1

/**
 * A manifest read back from a file, cut down to what a project row can hold.
 *
 * Nothing here is a security boundary on its own — the container checks are — but every one
 * of these fields is written to the database and most are drawn on a screen, and a manifest
 * is JSON this app did not write. A megabyte-long `projectName` is a row that bloats every
 * query and a title that no `maxLines` saves; a `selectedStyle` that is not a style name is a
 * variant the session cannot resolve. Clamped and shape-checked rather than refused: none of
 * it makes an archive unusable, and refusing a project over a long name would be a worse
 * answer than shortening it.
 *
 * [ProjectManifest.faceId] is deliberately absent: it is not trimmed, it is *compared* to the
 * container's own, which is a stronger check than any shape rule.
 */
private fun ProjectManifest.sanitised() = copy(
    projectName = projectName.trim().take(MaxManifestTextLength),
    displayName = displayName.trim().take(MaxManifestTextLength),
    faceName = faceName?.trim()?.take(MaxManifestTextLength)?.takeIf(String::isNotEmpty),
    sourceUri = sourceUri?.trim()?.take(MaxManifestTextLength)?.takeIf(String::isNotEmpty),
    productId = productId?.trim()?.take(MaxManifestTextLength)?.takeIf(String::isNotEmpty),
    // A style the container does not carry is one `loadSession` cannot select, so an
    // unrecognisable name reads as "no style was recorded" and the first one is used.
    selectedStyle = selectedStyle?.takeIf { StyleNamePattern.matches(it) },
    styleId = styleId?.takeIf { it in 0..MaxStyleId },
)

/** Longer than any real name and short enough that a hostile one costs nothing. */
private const val MaxManifestTextLength = 256

/** The protocol carries a style as one byte, so nothing above this can name one. */
private const val MaxStyleId = 255

private val StyleNamePattern = Regex("""style(\d+)\.bin""")

private val PreviewFileNamePattern = Regex("""style(\d+)\.png""")

/** The index in `styleN.bin`, which is the index the package's previews use too. */
private fun styleIndexOf(basename: String): Int? =
    StyleNamePattern.matchEntire(basename)?.groupValues?.get(1)?.toIntOrNull()

/**
 * The name to show for a project.
 *
 * [ProjectEntity.projectName] is null only on a row that predates schema 5 and that its
 * backfill could not name, so the fallbacks are the face's own names — identical across
 * every project on the face, but better than a blank row.
 */
internal val ProjectEntity.resolvedName: String
    get() = projectName?.takeIf(String::isNotBlank)
        ?: faceName?.takeIf(String::isNotBlank)
        ?: displayName

private fun ProjectEntity.toSummary(previewImagePath: String?) = ProjectSummary(
    id = id,
    displayName = displayName,
    sourceUri = sourceUri,
    faceId = faceId,
    faceName = faceName,
    importedAtEpochMillis = importedAtEpochMillis,
    previewImagePath = previewImagePath,
    name = resolvedName,
    styleId = styleId ?: selectedStyle?.let(::styleIndexOf),
    packageVersionCode = packageVersionCode,
    // Zero for a row written before schema 5 whose migration could not recover one; falling
    // back to the import time keeps it out of the bottom of a "recently edited" sort.
    updatedAtEpochMillis = updatedAtEpochMillis.takeIf { it > 0 } ?: importedAtEpochMillis,
)

/**
 * How an edit's audit line says what it reached — one phrase, five callers.
 *
 * The edit history this feeds is what a bug report is read from, and it is the only
 * account of an edit that drew wrong without throwing. Five call sites each spelling out
 * their own three-way `if` is five places for the next scope to be added to four of them:
 * the same shape as a widget type added to one `when` and missed in another.
 */
private fun editScope(styleName: String, applyToAllStyles: Boolean): String = when {
    styleName == AOD_ENTRY_NAME -> "on the always-on display"
    applyToAllStyles -> "across all styles"
    else -> "on selected style"
}

private fun StructuralEdit.audit(operation: String) = EditAuditSummary(
    changedPayloadBytes = changedPayloadBytes,
    changedStyles = changedStyles,
    operation = operation,
    sizeDelta = sizeDelta,
)
