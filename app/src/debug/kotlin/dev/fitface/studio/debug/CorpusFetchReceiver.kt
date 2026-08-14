package dev.fitface.studio.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.fitface.studio.core.model.FaceCatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only hook that downloads catalogue packages into the app's own cache.
 *
 * It exists so the test corpus can be populated without driving the download sheet a
 * hundred times: a UI dump on this device costs about twenty seconds, which makes
 * coordinate-driven automation both slow and fragile. This runs the same
 * [FaceCatalogRepository.downloadPackage] path the sheet does, so the packages are
 * fetched and cached exactly as they would be in normal use.
 *
 * Lives in the `debug` source set only and is absent from any release build. See
 * `tools/fetch_corpus.py`, which drives it.
 *
 *   adb shell am broadcast -a dev.fitface.studio.debug.FETCH \
 *       -n dev.fitface.studio/dev.fitface.studio.debug.CorpusFetchReceiver \
 *       --es faceId 00046
 */
class CorpusFetchReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun faceCatalogRepository(): FaceCatalogRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        val faceId = intent.getStringExtra("faceId")
        if (faceId.isNullOrBlank()) {
            Log.w(TAG, "$RESULT no faceId extra")
            return
        }
        val pending = goAsync()
        val repository = EntryPointAccessors
            .fromApplication(context.applicationContext, Dependencies::class.java)
            .faceCatalogRepository()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val face = repository.loadCatalog().faces.firstOrNull { it.faceId == faceId }
                if (face == null) {
                    Log.w(TAG, "$RESULT $faceId missing-from-catalogue")
                    return@launch
                }
                val package_ = repository.downloadPackage(face, face.styles.first().id)
                Log.i(TAG, "$RESULT $faceId ok ${package_.size} ${face.appId}@${face.versionCode}")
            } catch (error: Throwable) {
                Log.w(TAG, "$RESULT $faceId failed ${error.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "CorpusFetch"

        /** Grep marker the fetch script waits on. */
        const val RESULT = "FETCH_RESULT"
    }
}
