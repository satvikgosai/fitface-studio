package dev.fitface.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import dev.fitface.studio.core.ui.FitFaceTheme
import dev.fitface.studio.feature.editor.EditorRoute
import dev.fitface.studio.feature.library.LibraryRoute
import kotlinx.serialization.Serializable

@Serializable
private data object LibraryDestination : NavKey

@Serializable
private data class EditorDestination(val projectId: Long) : NavKey

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FitFaceTheme {
                val backStack = rememberNavBackStack(LibraryDestination)
                val popSafely: (Int) -> Unit = { requested ->
                    repeat(requested.coerceAtMost((backStack.size - 1).coerceAtLeast(0))) {
                        backStack.removeLastOrNull()
                    }
                }
                // The app menu's two dialogs are hosted here rather than in either screen.
                // Both top bars carry the menu, so hosting them below would mean two
                // copies of the same state; and a dialog composed outside `NavDisplay` is
                // not owned by a nav entry, so opening a face while a 36 MiB update
                // downloads leaves it alone. `remember` is enough — a dialog need not
                // survive process death, and the download it is watching lives in a
                // process-wide singleton either way.
                var menuRequest by remember { mutableStateOf<AppMenuRequest?>(null) }
                AppMenuDialogs(
                    request = menuRequest,
                    onDismiss = { menuRequest = null },
                )
                NavDisplay(
                    backStack = backStack,
                    onBack = { popSafely(1) },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    entryProvider = entryProvider {
                        entry<LibraryDestination> {
                            LibraryRoute(
                                onOpenEditor = { projectId ->
                                    backStack.add(EditorDestination(projectId))
                                },
                                onAbout = { menuRequest = AppMenuRequest.About },
                                onCheckForUpdate = { menuRequest = AppMenuRequest.Update },
                            )
                        }
                        entry<EditorDestination> { destination ->
                            EditorRoute(
                                projectId = destination.projectId,
                                onBack = { popSafely(1) },
                                onAbout = { menuRequest = AppMenuRequest.About },
                                onCheckForUpdate = { menuRequest = AppMenuRequest.Update },
                            )
                        }
                    },
                )
            }
        }
    }
}
