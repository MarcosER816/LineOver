package dev.mark.lineover.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import dev.mark.lineover.AppContainer
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LocalReducedMotion
import dev.mark.lineover.feature.settings.SettingsSection
import dev.mark.lineover.feature.settings.SettingsSectionScreen
import dev.mark.lineover.feature.editor.EditorRoute as EditorScreenRoute
import dev.mark.lineover.feature.export.ExportRoute as ExportScreenRoute
import dev.mark.lineover.feature.home.AlbumRoute as AlbumScreenRoute
import dev.mark.lineover.feature.home.HomeRoute as HomeScreenRoute
import dev.mark.lineover.feature.settings.SettingsRoute as SettingsScreenRoute

/**
 * The app's navigation: Home → Editor(projectId) → Export sheet, Home → Album → Editor,
 * and Settings.
 * Navigation 3 keeps the back stack as a saveable list; Back (including the predictive
 * back gesture, with its preview animation) pops it. Each entry has its own ViewModelStore.
 */
@Composable
fun LineOverNavigation(
    container: AppContainer,
    versionName: String,
    gallery: (@Composable (onBack: () -> Unit) -> Unit)?,
) {
    val backStack = rememberNavBackStack(HomeRoute)
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    val openGallery: (() -> Unit)? = gallery?.let { { backStack.add(GalleryRoute) } }
    val reducedMotion = LocalReducedMotion.current
    val noMotion = EnterTransition.None togetherWith ExitTransition.None
    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize().background(LoColors.Bg),
        onBack = back,
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        sceneStrategies = listOf(DialogSceneStrategy(), SinglePaneSceneStrategy()),
        transitionSpec = if (reducedMotion) ({ noMotion }) else defaultTransitionSpec(),
        popTransitionSpec = if (reducedMotion) ({ noMotion }) else defaultPopTransitionSpec(),
        predictivePopTransitionSpec = if (reducedMotion) ({ _ -> noMotion }) else defaultPredictivePopTransitionSpec(),
        entryProvider =
            entryProvider<NavKey> {
                entry<HomeRoute> {
                    HomeScreenRoute(
                        library = container.projects,
                        gallery = container.gallery,
                        cleanupScope = container.appScope,
                        onOpenEditor = { id -> backStack.add(EditorRoute(id)) },
                        onOpenAlbum = { album -> backStack.add(AlbumRoute(album.id, album.name)) },
                        onOpenSettings = { backStack.add(SettingsRoute) },
                    )
                }
                entry<AlbumRoute> { key ->
                    AlbumScreenRoute(
                        albumId = key.albumId,
                        name = key.name,
                        gallery = container.gallery,
                        library = container.projects,
                        onBack = back,
                        onOpenEditor = { id -> backStack.add(EditorRoute(id)) },
                    )
                }
                entry<EditorRoute> { key ->
                    EditorScreenRoute(
                        projectId = key.projectId,
                        library = container.projects,
                        preferences = container.preferences,
                        canvases = container.canvases,
                        onBack = back,
                        onExport = { backStack.add(ExportRoute(key.projectId)) },
                        saveScope = container.appScope,
                        lensProfiles = container.lensProfiles,
                        looks = container.looks,
                        layers = container.layerSources,
                        maskRasters = container.maskRasters,
                    )
                }
                entry<ExportRoute>(
                    metadata = DialogSceneStrategy.dialog(DialogProperties(usePlatformDefaultWidth = false)),
                ) { key ->
                    // Full-window dialog: tapping above the sheet closes it.
                    Box(
                        Modifier.fillMaxSize().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = back,
                        ),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        ExportScreenRoute(
                            projectId = key.projectId,
                            library = container.projects,
                            onClose = back,
                            preferences = container.preferences,
                        )
                    }
                }
                entry<SettingsRoute> {
                    SettingsScreenRoute(
                        versionName = versionName,
                        preferences = container.preferences,
                        onBack = back,
                        onOpenSection = { section ->
                            if (section == SettingsSection.Debug && openGallery != null) {
                                openGallery()
                            } else {
                                backStack.add(SettingsSectionRoute(section.id))
                            }
                        },
                        onOpenGallery = openGallery,
                        lensProfiles = container.lensProfiles,
                    )
                }
                entry<SettingsSectionRoute> { key ->
                    SettingsSectionScreen(
                        section = SettingsSection.fromId(key.sectionId) ?: SettingsSection.About,
                        versionName = versionName,
                        preferences = container.preferences,
                        onBack = back,
                        onOpenGallery = openGallery,
                        lensProfiles = container.lensProfiles,
                    )
                }
                entry<GalleryRoute> {
                    if (gallery != null) gallery(back)
                }
            },
    )
}
