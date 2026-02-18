package eu.kanade.tachiyomi.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.zIndex
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.presentation.library.LibrarySettingsDialog
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.presentation.manga.components.LibraryBottomActionMenu
import eu.kanade.presentation.more.onboarding.GETTING_STARTED_URL
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.active
import tachiyomi.source.local.isLocal

data object LibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestOpenSettingsSheet()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        val screenModel = rememberScreenModel { LibraryScreenModel() }
        val settingsScreenModel = rememberScreenModel { LibrarySettingsScreenModel() }
        val state by screenModel.state.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }
        var showComicVineDialog by remember { mutableStateOf(false) }

        val tabTitles = listOf("Library", "Upcoming")
        val pagerState = rememberPagerState { tabTitles.size }

        val onClickRefresh: (Category?) -> Boolean = { category ->
            val started = LibraryUpdateJob.startNow(context, category)
            scope.launch {
                val msgRes = when {
                    !started -> MR.strings.update_already_running
                    category != null -> MR.strings.updating_category
                    else -> MR.strings.updating_library
                }
                snackbarHostState.showSnackbar(context.stringResource(msgRes))
            }
            started
        }

        if (showComicVineDialog) {
            ComicVineApiDialog(onDismissRequest = { showComicVineDialog = false })
        }

        Scaffold(
            topBar = {
                if (state.selection.isNotEmpty()) {
                    AppBar(
                        titleContent = { Text(text = "${state.selection.size}") },
                        actions = {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_select_all),
                                        icon = Icons.Outlined.SelectAll,
                                        onClick = screenModel::selectAll,
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_select_inverse),
                                        icon = Icons.Outlined.FlipToBack,
                                        onClick = screenModel::invertSelection,
                                    ),
                                ),
                            )
                        },
                        isActionMode = true,
                        onCancelActionMode = screenModel::clearSelection,
                    )
                } else {
                    SearchToolbar(
                        titleContent = { AppBarTitle(stringResource(MR.strings.label_library)) },
                        searchEnabled = true,
                        searchQuery = state.searchQuery,
                        onChangeSearchQuery = screenModel::search,
                        actions = {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_filter),
                                        icon = Icons.Outlined.FilterList,
                                        iconTint = if (state.hasActiveFilters) {
                                            MaterialTheme.colorScheme.active
                                        } else {
                                            null
                                        },
                                        onClick = screenModel::showSettingsDialog,
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_update_library),
                                        onClick = { onClickRefresh(null) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_update_category),
                                        onClick = { onClickRefresh(state.activeCategory) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_open_random_manga),
                                        onClick = {
                                            scope.launch {
                                                val randomItem = screenModel.getRandomLibraryItemForCurrentCategory()
                                                if (randomItem != null) {
                                                    navigator.push(MangaScreen(randomItem.libraryManga.manga.id))
                                                } else {
                                                    snackbarHostState.showSnackbar(
                                                        context.stringResource(MR.strings.information_no_entries_found),
                                                    )
                                                }
                                            }
                                        },
                                    ),
                                    AppBar.OverflowAction(
                                        title = "ComicVine API",
                                        onClick = { showComicVineDialog = true },
                                    ),
                                ),
                            )
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            Column(
                modifier = Modifier.padding(
                    top = contentPadding.calculateTopPadding(),
                    start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
                ),
            ) {
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    modifier = Modifier.zIndex(1f),
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { TabText(text = title) },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                HorizontalPager(
                    modifier = Modifier.fillMaxSize(),
                    state = pagerState,
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    when (page) {

                        // ---- PAGE 0: Library ----
                        0 -> {
                            Scaffold(
                                bottomBar = {
                                    LibraryBottomActionMenu(
                                        visible = state.selectionMode,
                                        onChangeCategoryClicked = screenModel::openChangeCategoryDialog,
                                        onMarkAsReadClicked = { screenModel.markReadSelection(true) },
                                        onMarkAsUnreadClicked = { screenModel.markReadSelection(false) },
                                        onDownloadClicked = screenModel::performDownloadAction
                                            .takeIf { state.selectedManga.fastAll { !it.isLocal() } },
                                        onDeleteClicked = screenModel::openDeleteMangaDialog,
                                        onMigrateClicked = {
                                            val selection = state.selection
                                            screenModel.clearSelection()
                                            navigator.push(MigrationConfigScreen(selection))
                                        },
                                    )
                                },
                            ) { innerPadding ->
                                val combinedPadding = PaddingValues(
                                    bottom = contentPadding.calculateBottomPadding(),
                                )
                                when {
                                    state.isLoading -> {
                                        LoadingScreen(Modifier.padding(innerPadding))
                                    }
                                    state.searchQuery.isNullOrEmpty() && !state.hasActiveFilters && state.isLibraryEmpty -> {
                                        val handler = LocalUriHandler.current
                                        EmptyScreen(
                                            stringRes = MR.strings.information_empty_library,
                                            modifier = Modifier.padding(innerPadding),
                                            actions = persistentListOf(
                                                EmptyScreenAction(
                                                    stringRes = MR.strings.getting_started_guide,
                                                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                                                    onClick = { handler.openUri(GETTING_STARTED_URL) },
                                                ),
                                            ),
                                        )
                                    }
                                    else -> {
                                        LibraryContent(
                                            categories = state.displayedCategories,
                                            searchQuery = state.searchQuery,
                                            selection = state.selection,
                                            contentPadding = combinedPadding,
                                            currentPage = state.coercedActiveCategoryIndex,
                                            hasActiveFilters = state.hasActiveFilters,
                                            showPageTabs = state.showCategoryTabs || !state.searchQuery.isNullOrEmpty(),
                                            onChangeCurrentPage = screenModel::updateActiveCategoryIndex,
                                            onClickManga = { navigator.push(MangaScreen(it)) },
                                            onContinueReadingClicked = { it: LibraryManga ->
                                                scope.launchIO {
                                                    val chapter = screenModel.getNextUnreadChapter(it.manga)
                                                    if (chapter != null) {
                                                        context.startActivity(
                                                            ReaderActivity.newIntent(context, chapter.mangaId, chapter.id),
                                                        )
                                                    } else {
                                                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                                                    }
                                                }
                                                Unit
                                            }.takeIf { state.showMangaContinueButton },
                                            onToggleSelection = screenModel::toggleSelection,
                                            onToggleRangeSelection = { category, manga ->
                                                screenModel.toggleRangeSelection(category, manga)
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onRefresh = { onClickRefresh(state.activeCategory) },
                                            onGlobalSearchClicked = {
                                                navigator.push(GlobalSearchScreen(screenModel.state.value.searchQuery ?: ""))
                                            },
                                            getItemCountForCategory = { state.getItemCountForCategory(it) },
                                            getDisplayMode = { screenModel.getDisplayMode() },
                                            getColumnsForOrientation = { screenModel.getColumnsForOrientation(it) },
                                            getItemsForCategory = { state.getItemsForCategory(it) },
                                        )
                                    }
                                }
                            }
                        }

                        // ---- PAGE 1: Upcoming ----
                        1 -> {
                            UpcomingTab()
                        }
                    }
                }
            }
        }

        // ---- Dialogs ----
        val onDismissRequest = screenModel::closeDialog
        when (val dialog = state.dialog) {
            is LibraryScreenModel.Dialog.SettingsSheet -> run {
                LibrarySettingsDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    category = state.activeCategory,
                )
            }
            is LibraryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        screenModel.clearSelection()
                        navigator.push(CategoryScreen())
                    },
                    onConfirm = { include, exclude ->
                        screenModel.clearSelection()
                        screenModel.setMangaCategories(dialog.manga, include, exclude)
                    },
                )
            }
            is LibraryScreenModel.Dialog.DeleteManga -> {
                DeleteLibraryMangaDialog(
                    containsLocalManga = dialog.manga.any(Manga::isLocal),
                    onDismissRequest = onDismissRequest,
                    onConfirm = { deleteManga, deleteChapter ->
                        screenModel.removeMangas(dialog.manga, deleteManga, deleteChapter)
                        screenModel.clearSelection()
                    },
                )
            }
            null -> {}
        }

        BackHandler(enabled = state.selectionMode || state.searchQuery != null) {
            when {
                state.selectionMode -> screenModel.clearSelection()
                state.searchQuery != null -> screenModel.search(null)
            }
        }

        LaunchedEffect(state.selectionMode, state.dialog) {
            HomeScreen.showBottomNav(!state.selectionMode)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            launch { queryEvent.receiveAsFlow().collect(screenModel::search) }
            launch { requestSettingsSheetEvent.receiveAsFlow().collectLatest { screenModel.showSettingsDialog() } }
        }
    }

    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    private val requestSettingsSheetEvent = Channel<Unit>()
    private suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}
