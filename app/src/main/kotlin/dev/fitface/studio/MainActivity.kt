package dev.fitface.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                            )
                        }
                        entry<EditorDestination> { destination ->
                            EditorRoute(
                                projectId = destination.projectId,
                                onBack = { popSafely(1) },
                            )
                        }
                    },
                )
            }
        }
    }
}
