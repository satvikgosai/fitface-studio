package dev.fitface.studio.core.data

import android.content.Context
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
import dev.fitface.studio.core.format.Fit3Apk
import dev.fitface.studio.core.format.Fit3Container
import dev.fitface.studio.core.format.Fit3FormatException
import dev.fitface.studio.core.format.ImageRecord
import dev.fitface.studio.core.format.StructuralEditor
import dev.fitface.studio.core.format.StructuralEdit
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.DiagnosticsSection
import dev.fitface.studio.core.model.EditAuditSummary
import dev.fitface.studio.core.model.DirectInstallPayload
import dev.fitface.studio.core.model.EditorSnapshot
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.format.Fit3NoContainerException
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.ProjectSummary
import dev.fitface.studio.core.model.RemovedWidget
import dev.fitface.studio.core.model.ReplacementImage
import dev.fitface.studio.core.model.WATCH_CONTAINER_BYTE_CEILING
import dev.fitface.studio.core.model.mebibytes
import dev.fitface.studio.core.model.WatchFaceRepository
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WatchFaceException
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.editorPreferences by preferencesDataStore(name = "editor_preferences")
private val ImageFitKey = stringPreferencesKey("image_fit")

@Singleton
class WatchFaceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectDao: ProjectDao,
    private val imageSource: AndroidImageSource,
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

    override suspend fun openPackage(
        download: FacePackage,
    ): EditorSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val apkBytes = download.copyBytes()
            val existing = projectDao.findBySourceUri(download.sourceKey)
            val desiredStyle = "style${download.selectedStyleId}.bin"
            val loaded = loadSession(
                apkBytes = apkBytes,
                fallbackName = download.displayName,
                projectId = existing?.id ?: 0,
                editedBinPath = existing?.editedBinPath,
                selectedStyle = desiredStyle,
            )
            if (loaded.apk.faceId != download.expectedFaceId) {
                throw WatchFaceException(
                    "The store returned the wrong watch-face package. Nothing was saved.",
                    "expected=${download.expectedFaceId} actual=${loaded.apk.faceId}",
                )
            }
            val now = System.currentTimeMillis()
            val project = ProjectEntity(
                id = existing?.id ?: 0,
                displayName = loaded.sourceName,
                sourceUri = download.sourceKey,
                faceId = loaded.apk.faceId,
                faceName = loaded.apk.faceName,
                importedAtEpochMillis = now,
                localApkPath = existing?.localApkPath,
                editedBinPath = existing?.editedBinPath,
                selectedStyle = desiredStyle,
            )
            val projectId = projectDao.insert(project)
            val projectDirectory = projectDirectory(projectId).apply { mkdirs() }
            val localApk = File(projectDirectory, "source.apk")
            writeAtomically(localApk, apkBytes)
            projectDao.insert(
                project.copy(
                    id = projectId,
                    localApkPath = localApk.absolutePath,
                ),
            )
            loaded.projectId = projectId
            loaded.stylePreviewFiles = writeStylePreviews(projectId, loaded.apk)
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
                    selectedStyle = project.selectedStyle,
                )
                val localApk = project.localApkPath
                    .let(::File)
                    .takeIf(File::isFile)
                    ?: File(projectDirectory(project.id), "source.apk").also {
                        writeAtomically(it, apkBytes)
                    }
                projectDao.insert(
                    project.copy(
                        faceName = loaded.apk.faceName,
                        importedAtEpochMillis = System.currentTimeMillis(),
                        localApkPath = localApk.absolutePath,
                    ),
                )
                loaded.stylePreviewFiles = writeStylePreviews(project.id, loaded.apk)
                loaded.also { session = it }.snapshot()
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

    override suspend fun currentSnapshot(styleName: String?): EditorSnapshot =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val current = requireSession()
                current.snapshot(styleName).also {
                    if (styleName != null && current.projectId > 0) {
                        projectDao.findById(current.projectId)?.let { project ->
                            projectDao.insert(project.copy(selectedStyle = current.selectedStyle))
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
                if (current.styleEntries().isEmpty()) {
                    throw IllegalArgumentException("No editable style entries found")
                }
                // Measured against a style that actually carries a background, and the
                // selected one first. Reading style0 unconditionally made faces whose
                // first style paints onto black — 00011, and 00108 up to style3 —
                // refuse an edit their remaining styles could take.
                val image = current.backgroundRaster()
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
                val bare = current.styleEntries().filter {
                    FaceRecordParser.backgroundImage(it) == null
                }
                if (bare.size != current.styleEntries().size) {
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
                // A panel raster costs 205,880 bytes a style and the watch ignores a
                // container over the ceiling, so a big face gets one in as many styles as
                // fit — the selected style first, because that is the one the install
                // activates and the only one the canvas shows.
                val targets = current.backgroundAddTargets()
                if (targets.isEmpty()) {
                    val cost = StructuralEditor.addedBackgroundBytes(panel.width, panel.height)
                    throw WatchFaceException(
                        "This face is already ${mebibytes(current.currentContainer.fileSize)} " +
                            "and a full-face background adds ${mebibytes(cost)} per style, " +
                            "which would take it over the " +
                            "${mebibytes(WATCH_CONTAINER_BYTE_CEILING)} the watch accepts. " +
                            "Everything else on this face still works.",
                        "container=${current.currentContainer.fileSize} styles=${bare.size}",
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
                        operation = if (targets.size == current.styleEntries().size) {
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
            if (current.backgroundRaster() == null) {
                throw WatchFaceException(
                    "No style of this face has a full-face background image to tint — it " +
                        "draws its widgets straight onto black.",
                    "no styleN.bin carries a panel-sized raster",
                )
            }
            val edit = FaceEditor.tintBackgrounds(
                current.currentContainer,
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
            val styleNames = current.targetStyleNames(styleName, applyToAllStyles)
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
                    operation = if (applyToAllStyles) {
                        "Pair widget color changed across all styles"
                    } else {
                        "Pair widget color changed on selected style"
                    },
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
            val styleNames = if (applyToAllStyles) {
                buildList {
                    add(styleName)
                    addAll(
                        current.styleEntries()
                            .map { it.basename }
                            .filterNot { it == styleName },
                    )
                    if (current.currentContainer.entries.any { it.basename == "aod.bin" }) {
                        add("aod.bin")
                    }
                }
            } else {
                listOf(styleName)
            }
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
                    operation = if (applyToAllStyles) {
                        "Widget moved across all style and AOD variants"
                    } else {
                        "Widget moved on selected style"
                    },
                ),
                styleName,
            )
        }
    }

    override suspend fun resizeBackground(width: Int, height: Int): EditorSnapshot =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val current = requireSession()
                val first = current.styleEntries().first()
                val background = FaceRecordParser.backgroundImage(first)
                    ?: throw WatchFaceException(
                        "This face has no full-face background image to resize.",
                        "${first.basename} carries no panel-sized raster",
                    )
                val frame = FaceRecordParser.decodeImage(first, background)
                val pixels = imageSource.resize(frame, width, height)
                val styles = current.styleEntries().map { it.basename }
                val edit = StructuralEditor.resizeBackgrounds(
                    current.currentContainer,
                    styles,
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

    override suspend fun resizeSprite(
        styleName: String,
        sequenceId: Int,
        width: Int,
        height: Int,
        applyToAllStyles: Boolean,
    ): EditorSnapshot = withContext(Dispatchers.Default) {
        mutex.withLock {
            val current = requireSession()
            val styleNames = current.targetStyleNames(styleName, applyToAllStyles)
            val edit = StructuralEditor.resizeSprite(
                current.currentContainer,
                styleNames,
                sequenceId,
                width,
                height,
                // Resample the vendor's frames, never the last resize's output — the
                // same reason `reference` is read from the original container. Without
                // it, Smaller-then-Larger hands back a blurred sprite.
                pristine = current.originalContainer,
            )
            commit(
                current,
                edit.container,
                edit.audit(
                    if (applyToAllStyles) {
                        "Sprite resized across all styles"
                    } else {
                        "Sprite resized on selected style"
                    },
                ),
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
            val styleNames = current.targetStyleNames(styleName, applyToAllStyles)
            val guide = FaceRecordParser.widgetGuides(
                current.currentContainer.entryByBasename(styleName),
            ).firstOrNull { it.globalIndex == globalIndex }
            val edit = StructuralEditor.removeWidget(
                current.currentContainer,
                styleNames,
                globalIndex,
                widgetType,
                sequenceId,
                x,
                y,
                requireFinal,
            )
            val removed = RemovedWidget(
                id = removedWidgetIds.incrementAndGet(),
                label = "Widget #$globalIndex",
                widgetType = widgetType,
                sequenceId = sequenceId,
                x = x,
                y = y,
                width = guide?.width ?: 0,
                height = guide?.height ?: 0,
                recordsByStyle = edit.removedRecords,
            )
            val previousRemoved = current.removedWidgets.toList()
            current.removedWidgets += removed
            try {
                commit(
                    current,
                    edit.container,
                    edit.audit(
                        if (applyToAllStyles) {
                            "Widget removed across all styles"
                        } else {
                            "Widget removed on selected style"
                        },
                    ),
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
                    removed.recordsByStyle.keys.toList(),
                    removed.recordsByStyle,
                )
                val previousRemoved = current.removedWidgets.toList()
                current.removedWidgets.remove(removed)
                try {
                    commit(
                        current,
                        edit.container,
                        edit.audit("${removed.label} restored at the end of the table"),
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
            val snapshot = current.snapshot()
            val styleIndex = snapshot.styleNames.indexOf(snapshot.selectedStyle)
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
                            snapshot.selectedStyle,
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
            val styleNames = current.targetStyleNames(styleName, applyToAllStyles)
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
                edit.audit(
                    if (applyToAllStyles) {
                        "Widget duplicated across all styles"
                    } else {
                        "Widget duplicated on selected style"
                    },
                ),
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
                add("face=${current.apk.faceId} style=${current.selectedStyle ?: "none"}")
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
        val previousStyle = current.selectedStyle
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
            current.selectedStyle = previousStyle
            throw error
        }
    }

    private suspend fun persistEdited(current: Session, keepEdited: Boolean) {
        if (current.projectId <= 0) return
        val project = projectDao.findById(current.projectId) ?: return
        val directory = projectDirectory(current.projectId)
        val editedFile = File(directory, "edited.bin")
        if (keepEdited) {
            writeAtomically(editedFile, current.currentContainer.toByteArray())
        } else {
            editedFile.delete()
        }
        persistSessionState(directory, current, keepEdited)
        projectDao.insert(
            project.copy(
                editedBinPath = editedFile.absolutePath.takeIf { keepEdited },
                selectedStyle = current.selectedStyle,
            ),
        )
    }

    /**
     * Removed widget records live beside the edited BIN so "restore" survives
     * process death, exactly like the edit itself does.
     */
    private fun persistSessionState(directory: File, current: Session, keepEdited: Boolean) {
        val file = File(directory, "session.json")
        if (!keepEdited || (current.removedWidgets.isEmpty() && !current.thumbnailRefreshed)) {
            file.delete()
            return
        }
        runCatching {
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
        selectedStyle: String? = null,
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
            selectedStyle = selectedStyle,
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
        var audit: EditAuditSummary? = null,
        var selectedStyle: String? = null,
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

        fun styleEntries() = currentContainer.entries
            .filter { it.basename.matches(Regex("""style\d+\.bin""")) }

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
        fun backgroundAddTargets(): List<String> = StructuralEditor.backgroundStylesThatFit(
            source = currentContainer,
            entryBasenames = styleEntries().map { it.basename },
            preferred = selectedStyle,
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
            val preferred = selectedStyle?.let { name ->
                entries.singleOrNull { it.basename == name }
            }
            return listOfNotNull(preferred).plus(entries)
                .firstNotNullOfOrNull(FaceRecordParser::backgroundImage)
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
            val samplerId = selectedStyle
                ?.removePrefix("style")
                ?.removeSuffix(".bin")
                ?.toIntOrNull()
                ?.takeIf { it in 0 until styleCount }
                ?: apk.samplerId?.takeIf { it in 0 until styleCount }
                ?: throw WatchFaceException(
                    "FitFace Studio could not work out which style to activate.",
                    "selectedStyle=$selectedStyle styles=$styleCount",
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
            val styles = reparsed.entries.filter {
                it.basename.matches(Regex("""style\d+\.bin""")) || it.basename == "aod.bin"
            }
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

        /**
         * Everything a snapshot needs that is derived from the *unedited* container.
         *
         * None of it can change while the session is open, but all of it used to be
         * recomputed on every commit — and a commit happens on every nudge, so a
         * press-and-hold recomputed it dozens of times.
         */
        private class OriginalStyleState(
            val background: PreviewFrame,
            val reference: PreviewFrame?,
            val widgetsByGlobalIndex: Map<Int, WidgetGuide>,
        )

        private val originalStyleCache = mutableMapOf<String, OriginalStyleState>()

        private fun originalStateFor(
            styleName: String,
            styleIndex: Int,
        ): OriginalStyleState = originalStyleCache.getOrPut(styleName) {
            val originalStyle = originalContainer.entryByBasename(styleName)
            OriginalStyleState(
                background = panelFrame(originalStyle),
                // The vendor's rendered preview of the *unedited* face. It has to come
                // from the original container: re-rendering the face-picker thumbnail
                // rewrites the edited container's preview.bin, and reading that back
                // as the reference would diff each edit against the previous
                // composite instead of against the vendor render — the preview would
                // drift a little further every time the thumbnail was refreshed.
                reference = originalContainer.entries
                    .singleOrNull { it.basename == "preview.bin" }
                    ?.let { previewEntry ->
                        FaceRecordParser.scanImages(previewEntry)
                            .getOrNull(styleIndex)
                            ?.let { FaceRecordParser.decodeImage(previewEntry, it) }
                    },
                widgetsByGlobalIndex = FaceRecordParser.widgetGuides(originalStyle)
                    .associateBy { it.globalIndex },
            )
        }

        fun snapshot(requestedStyle: String? = null): EditorSnapshot {
            val styles = styleEntries()
            if (styles.isEmpty()) throw IllegalArgumentException("No editable style entries found")
            val selected = requestedStyle?.let { name ->
                styles.singleOrNull { it.basename == name }
                    ?: throw IllegalArgumentException("Unknown style: $name")
            } ?: selectedStyle?.let { name ->
                styles.singleOrNull { it.basename == name }
            } ?: styles.first()
            selectedStyle = selected.basename
            val images = FaceRecordParser.scanImages(selected)
            val originalStyle = originalContainer.entryByBasename(selected.basename)
            // The canvas is the panel the watch renders, which is not the same thing
            // as "the style's first raster": faces 00022 and 00108 open with a small
            // icon, and a style with no full-panel raster simply draws onto black.
            val currentBackground = panelFrame(selected)
            val original = originalStateFor(selected.basename, styles.indexOf(selected))
            val originalBackground = original.background
            val referencePreview = original.reference
            val originalWidgets = original.widgetsByGlobalIndex
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
                    // A resize follows the new raster immediately; the reference
                    // render still shows the old one, so the composer needs the
                    // extent it was drawn at to know what to clear.
                    originalWidth = original?.width ?: widget.width,
                    originalHeight = original?.height ?: widget.height,
                    originalColorArgb = original?.colorArgb ?: widget.colorArgb,
                    duplicateSourceGlobalIndex = duplicateSource,
                )
            }
            val widgetImageLayers = referencePreview?.let { reference ->
                FaceRecordParser.widgetImageLayers(
                    entry = selected,
                    originalEntry = originalStyle,
                    reference = reference,
                )
            }.orEmpty()
            val editPreview = EditPreviewComposer.compose(
                currentBackground = currentBackground,
                originalBackground = originalBackground,
                reference = referencePreview,
                widgets = widgets,
                imageLayers = widgetImageLayers,
                // Only the variants the record was actually cut from; a removal
                // applied to one style must not blank the widget on the others.
                removedWidgets = removedWidgets.filter {
                    selected.basename in it.recordsByStyle
                },
            )
            val report = if (currentContainer === originalContainer) {
                originalReport
            } else {
                currentContainer.validate()
            }
            val backgrounds = styles.filter {
                FaceRecordParser.backgroundImage(it) != null
            }.map { it.basename }
            return EditorSnapshot(
                projectId = projectId,
                faceId = apk.faceId,
                faceName = apk.faceName,
                sourceName = sourceName,
                styleNames = styles.map { it.basename },
                selectedStyle = selected.basename,
                preview = currentBackground,
                referencePreview = referencePreview,
                composedPreview = editPreview.composed,
                widgetOverlay = editPreview.widgetOverlay,
                widgetImageLayers = widgetImageLayers,
                widgets = widgets,
                removedWidgets = removedWidgets.toList(),
                stylePreviewPaths = styles.mapNotNull { entry ->
                    styleIndexOf(entry.basename)
                        ?.let(stylePreviewFiles::get)
                        ?.let { entry.basename to it }
                }.toMap(),
                backgroundStyles = backgrounds,
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

@Serializable
private data class StoredRemovedWidget(
    val label: String,
    val widgetType: Int,
    val sequenceId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** Base64 so the raw record bytes survive a JSON round trip untouched. */
    val recordsByStyle: Map<String, String>,
) {
    constructor(widget: RemovedWidget) : this(
        label = widget.label,
        widgetType = widget.widgetType,
        sequenceId = widget.sequenceId,
        x = widget.x,
        y = widget.y,
        width = widget.width,
        height = widget.height,
        recordsByStyle = widget.recordsByStyle.mapValues {
            Base64.getEncoder().encodeToString(it.value)
        },
    )

    fun toModel(id: Long) = RemovedWidget(
        id = id,
        label = label,
        widgetType = widgetType,
        sequenceId = sequenceId,
        x = x,
        y = y,
        width = width,
        height = height,
        recordsByStyle = recordsByStyle.mapValues { Base64.getDecoder().decode(it.value) },
    )
}

private val StyleNamePattern = Regex("""style(\d+)\.bin""")

private val PreviewFileNamePattern = Regex("""style(\d+)\.png""")

/** The index in `styleN.bin`, which is the index the package's previews use too. */
private fun styleIndexOf(basename: String): Int? =
    StyleNamePattern.matchEntire(basename)?.groupValues?.get(1)?.toIntOrNull()

private fun ProjectEntity.toSummary(previewImagePath: String?) = ProjectSummary(
    id = id,
    displayName = displayName,
    sourceUri = sourceUri,
    faceId = faceId,
    faceName = faceName,
    importedAtEpochMillis = importedAtEpochMillis,
    previewImagePath = previewImagePath,
)

private fun StructuralEdit.audit(operation: String) = EditAuditSummary(
    changedPayloadBytes = changedPayloadBytes,
    changedStyles = changedStyles,
    operation = operation,
    sizeDelta = sizeDelta,
)
