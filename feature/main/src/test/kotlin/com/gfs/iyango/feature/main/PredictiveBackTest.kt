package com.gfs.iyango.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.*
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import com.gfs.iyango.core.domain.AppAppearanceSnapshot
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.BingWallpaperPort
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.domain.WallpaperStore
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.DisplaySettingsBundle
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.feature.main.editor.CourseEditorScreen
import com.gfs.iyango.feature.main.editor.CourseEditorViewModel
import com.gfs.iyango.feature.main.liquidglass.LiquidBottomTabs
import com.gfs.iyango.feature.main.liquidglass.LiquidBottomTab
import com.gfs.iyango.feature.main.mine.MineCategory
import com.gfs.iyango.feature.main.school.SchoolAccountContent
import com.gfs.iyango.feature.main.school.SchoolAccountUiState
import com.gfs.iyango.feature.main.settings.WallpaperViewModel
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PredictiveBackTest {
    @get:Rule val compose = createComposeRule()
    private val input = DirectNavigationEventInput()
    private fun event(progress: Float, swipeEdge: Int = NavigationEvent.EDGE_LEFT) =
        NavigationEvent(swipeEdge = swipeEdge, progress = progress)

    @Composable private fun CachedPage(index: Int, compositions: IntArray, measures: IntArray) {
        SideEffect { compositions[index]++ }
        Box(Modifier.fillMaxSize().layout { measurable, constraints ->
            measures[index]++
            val placed = measurable.measure(constraints)
            layout(placed.width, placed.height) { placed.place(0, 0) }
        }) { Text("cached page $index") }
    }

    @Composable private fun NativeEvents(content: @Composable () -> Unit) {
        val owner = rememberNavigationEventDispatcherOwner(parent = null)
        DisposableEffect(owner) {
            owner.navigationEventDispatcher.addInput(input)
            onDispose { owner.navigationEventDispatcher.removeInput(input) }
        }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner, content = content)
    }

    @Test fun backGestureState_cancelRestoresProgress_andCompletionFiresExactlyOnce() {
        var enabled by mutableStateOf(true)
        var completed = 0
        lateinit var preview: BackPreviewState
        compose.setContent {
            NativeEvents {
                val state = rememberBackPreviewState()
                SideEffect { preview = state }
                NavigationBackHandler(
                    state.eventState,
                    isBackEnabled = enabled,
                    onBackCompleted = { state.complete(onCompleted = { completed++; enabled = false }) },
                )
                Text("page")
            }
        }
        compose.runOnIdle { input.backStarted(event(0f)); input.backProgressed(event(0.55f)) }
        compose.runOnIdle { assertTrue(preview.isPreviewing); assertTrue(preview.progress > 0.4f) }
        compose.runOnIdle { input.backCancelled() }
        compose.runOnIdle { assertFalse(preview.isPreviewing); assertEquals(0f, preview.progress, 0.001f); assertEquals(0, completed) }
        compose.runOnIdle { input.backStarted(event(0f)); input.backProgressed(event(0.6f)) }
        compose.runOnIdle { input.backCompleted() }
        compose.runOnIdle { assertEquals(1, completed) }
        compose.runOnIdle {
            // 完成后回调自行停用，同一手势路径上的重复完成事件不得再次触发。
            input.backCompleted()
            assertEquals("A second completion must not re-fire the callback", 1, completed)
        }
    }

    @Test fun dirtyEditor_cancelDoesNotDiscard_andCompletionRequestsConfirmationAfterRestoring() {
        var confirmation = 0
        val draft = "unsaved draft"
        lateinit var preview: BackPreviewState
        compose.setContent {
            NativeEvents {
                val state = rememberBackPreviewState()
                SideEffect { preview = state }
                EditorDiscardBackHandler(state, true) { confirmation++ }
                Text(draft)
            }
        }
        compose.runOnIdle { input.backStarted(event(0f)); input.backProgressed(event(0.7f)); input.backCancelled() }
        compose.runOnIdle { assertEquals(0, confirmation); assertFalse(preview.isPreviewing) }
        compose.runOnIdle { input.backStarted(event(0f)); input.backProgressed(event(0.65f)) }
        compose.runOnIdle { assertEquals(0, confirmation) }
        compose.runOnIdle { input.backCompleted() }
        compose.runOnIdle { assertEquals(1, confirmation); assertEquals(0f, preview.progress, 0.001f) }
        compose.onNodeWithText(draft).assertExists()
    }

    @Test fun disablingDuringEditorReleaseRestoresProgressBeforeShowingConfirmationOnce() {
        var enabled by mutableStateOf(true)
        var count = 0
        lateinit var back: BackPreviewState
        compose.setContent {
            NativeEvents {
                back = rememberBackPreviewState(enabled)
                EditorDiscardBackHandler(back, true) { assertEquals(0f, back.progress, .001f); count++ }
            }
        }
        compose.runOnIdle { input.backStarted(event(0f)); input.backProgressed(event(.75f)) }
        compose.runOnIdle { assertEquals(.75f, back.progress, .001f) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { input.backCompleted() }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle {
            assertEquals(0, count)
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { enabled = false }
        }
        compose.mainClock.advanceTimeUntil(96) { count == 1 }
        compose.runOnIdle { assertEquals(0f, back.progress, .001f) }
    }

    @Test fun hardwareBackCompletesImmediately_andDisabledHandlerLeavesBackToTheParent() {
        var selected by mutableIntStateOf(1)
        var discarded = 0
        var parentBack = 0
        var dirty by mutableStateOf(false)
        compose.setContent {
            NativeEvents {
                val dispatcher = LocalNavigationEventDispatcherOwner.current!!.navigationEventDispatcher
                DisposableEffect(dispatcher) {
                    val parent = object : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, true) {
                        override fun onBackCompleted() { parentBack++ }
                    }
                    dispatcher.addHandler(parent)
                    onDispose { parent.remove() }
                }
                val tabs = rememberBackPreviewState()
                val editor = rememberBackPreviewState()
                NavigationBackHandler(
                    tabs.eventState,
                    isBackEnabled = selected != 0 && !dirty,
                    onBackCompleted = { tabs.complete(onCompleted = { selected = 0 }) },
                )
                EditorDiscardBackHandler(editor, dirty) { discarded++ }
            }
        }
        compose.runOnIdle { input.backCompleted(); assertEquals("No gesture means no delayed settle", 0, selected) }
        compose.runOnIdle { input.backCompleted(); assertEquals(1, parentBack); dirty = true }
        compose.runOnIdle { input.backCompleted(); assertEquals(1, discarded); assertEquals(1, parentBack) }
    }

    @Test fun hiddenTabsCannotHandleBack_evenWhenTheirHandlerIsEnabled() {
        var selected by mutableIntStateOf(0)
        val received = mutableListOf<Int>()
        compose.setContent {
            NativeEvents {
                RetainedMainTabs(selected) { index ->
                    val state = rememberNavigationEventState<NavigationEventInfo>(NavigationEventInfo.None)
                    NavigationBackHandler(state, onBackCompleted = { received += index })
                    Text("page $index")
                }
            }
        }
        compose.runOnIdle { selected = 1 }
        compose.runOnIdle { selected = 0 }
        compose.runOnIdle { input.backCompleted(); assertEquals(listOf(0), received) }
    }

    @Test fun placedPreviewBlocksTouchAndSemantics_andOnlyStartsItsDataSubscriptionWhilePreviewing() {
        var preview by mutableStateOf(false)
        var visible by mutableStateOf(false)
        var clicks = 0
        var activeSubscriptions = 0
        var lastData = ""
        var lifecycle: Lifecycle? = null
        val data = flow {
            activeSubscriptions++
            try { emit("actual course"); awaitCancellation() } finally { activeSubscriptions-- }
        }
        compose.setContent {
            NativeEvents {
                Box(Modifier.fillMaxSize().testTag("scene")) {
                    RetainedMainTabs(1, visible, previewIndex = if (preview) 1 else null) {
                        val text by data.collectAsStateWithLifecycle(initialValue = "loading")
                        val owner = LocalLifecycleOwner.current
                        SideEffect { lastData = text; lifecycle = owner.lifecycle }
                        Box(Modifier.fillMaxSize().clickable { clicks++ }) { Text(text) }
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals("loading", lastData); assertEquals(0, activeSubscriptions); preview = true }
        compose.runOnIdle { assertEquals("actual course", lastData); assertEquals(1, activeSubscriptions); assertEquals(Lifecycle.State.STARTED, lifecycle!!.currentState) }
        compose.onNodeWithText("actual course").assertDoesNotExist()
        compose.onNodeWithTag("scene").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0, clicks); preview = false }
        compose.runOnIdle { assertEquals(0, activeSubscriptions); assertEquals(Lifecycle.State.CREATED, lifecycle!!.currentState); visible = true }
        compose.onNodeWithTag("scene").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun nativeProgressOnlyChangesDraw_withoutRecomposingOrMeasuringCachedPages() {
        var selected by mutableIntStateOf(0)
        val compositions = IntArray(4)
        val measures = IntArray(4)
        compose.setContent {
            NativeEvents {
                val back = rememberBackPreviewState()
                NavigationBackHandler(
                    back.eventState,
                    isBackEnabled = selected != 0,
                    onBackCompleted = { back.complete(onCompleted = { selected = 0 }) },
                )
                RetainedMainTabs(
                    selectedIndex = selected,
                    visible = !back.isPreviewing,
                    previewIndex = if (back.isPreviewing) 0 else null,
                    foregroundModifier = Modifier.backPreviewLayer(back),
                    backgroundModifier = Modifier.backPreviewEnterLayer(back),
                ) { CachedPage(it, compositions, measures) }
            }
        }
        compose.runOnIdle { selected = 1 }
        compose.runOnIdle { input.backStarted(event(0f)); input.backProgressed(event(0.2f)) }
        lateinit var compositionBaseline: IntArray
        lateinit var measureBaseline: IntArray
        compose.runOnIdle { compositionBaseline = compositions.clone(); measureBaseline = measures.clone() }
        for (progress in listOf(0.3f, 0.45f, 0.65f, 0.8f)) {
            compose.runOnIdle { input.backProgressed(event(progress)) }
            compose.runOnIdle {
                assertArrayEquals("Progress must not recompose page bodies", compositionBaseline, compositions)
                assertArrayEquals("Progress must not measure page bodies", measureBaseline, measures)
            }
        }
        compose.runOnIdle { input.backCancelled() }
        compose.runOnIdle { assertEquals(1, selected) }
    }

    @Test @Config(sdk = [28])
    fun previewDockRejectsBothOrdinaryTapAndLongPress() {
        val selected = mutableListOf<Int>()
        compose.setContent {
            Box(Modifier.testTag("dock scene")) {
                LiquidBottomTabs(
                    selectedTabIndex = { 0 }, onTabSelected = { selected += it },
                    backdrop = rememberLayerBackdrop(), tabsCount = 4,
                    interactive = false, modifier = Modifier.clearAndSetSemantics {},
                ) {
                    repeat(4) { index -> LiquidBottomTab(index, index == 0) { Text("preview dock $index") } }
                }
            }
        }
        val dock = compose.onNodeWithTag("dock scene")
        compose.onNodeWithText("preview dock 3").assertDoesNotExist()
        dock.performTouchInput { click(Offset(width * 0.875f, height / 2f)) }
        dock.performTouchInput { down(Offset(width * 0.625f, height / 2f)) }
        compose.mainClock.advanceTimeBy(450)
        dock.performTouchInput { up() }
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
    }

    private inline fun <reified T> unusedPort(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Editor guard must not access repository method ${method.name}") } as T

    /** 编辑器守卫不读设置内容，只允许构造时订阅显示配置流。 */
    private fun editorSettingsPort(): AppSettingsPort = Proxy.newProxyInstance(
        AppSettingsPort::class.java.classLoader, arrayOf(AppSettingsPort::class.java),
    ) { _, method, _ ->
        if (method.name == "getDisplaySettings") flowOf(DisplaySettingsBundle())
        else error("Editor guard must not access settings method ${method.name}")
    } as AppSettingsPort

    /** 编辑器守卫允许构造时订阅当前课表（只为色板），其余仓储方法不得访问。 */
    private fun editorTimetablePort(): TimetableRepositoryPort = Proxy.newProxyInstance(
        TimetableRepositoryPort::class.java.classLoader, arrayOf(TimetableRepositoryPort::class.java),
    ) { _, method, _ ->
        if (method.name == "observeCurrent") flowOf(null)
        else error("Editor guard must not access repository method ${method.name}")
    } as TimetableRepositoryPort

    @Test fun realDirtyEditorKeepsDraftOnCancel_andLeavesOnlyAfterDiscardConfirmation() {
        val model = CourseEditorViewModel(
            editorTimetablePort(),
            unusedPort<ScheduleDataPort>(),
            editorSettingsPort(),
            SavedStateHandle(),
        )
        model.update("name") { it.copy(name = "unsaved course draft") }
        var returned = 0
        compose.setContent { NativeEvents { CourseEditorScreen(onBack = { returned++ }, viewModel = model) } }
        compose.runOnIdle { input.backStarted(event(0f)); input.backProgressed(event(0.6f)) }
        compose.runOnIdle { input.backCancelled() }
        compose.onNodeWithText("放弃并离开").assertDoesNotExist()
        compose.runOnIdle { assertEquals("unsaved course draft", model.form.value.name); assertEquals(0, returned) }
        compose.runOnIdle { input.backStarted(event(0f)); input.backProgressed(event(0.65f)) }
        compose.runOnIdle { input.backCompleted() }
        compose.onNodeWithText("放弃并离开").assertExists()
        compose.runOnIdle { assertEquals("unsaved course draft", model.form.value.name); assertEquals(0, returned) }
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertEquals("unsaved course draft", model.form.value.name); assertEquals(0, returned); input.backCompleted() }
        compose.onNodeWithText("放弃并离开").performClick()
        compose.runOnIdle { assertEquals(1, returned) }
    }

    @Test fun dirtyEditorBackPreviewLayer_scalesAndShiftsAwayFromSwipedEdge() {
        lateinit var preview: BackPreviewState
        compose.setContent {
            NativeEvents {
                preview = rememberBackPreviewState(motionEnabled = false)
                NavigationBackHandler(preview.eventState, isBackEnabled = true, onBackCompleted = {})
                Box(Modifier.fillMaxSize().backPreviewLayer(preview, cardStyle = true)) {
                    Box(Modifier.fillMaxSize().testTag("editor-layer"))
                }
            }
        }
        val layer = compose.onNodeWithTag("editor-layer")
        val original = layer.getBoundsInRoot()
        val origCenterX = (original.left.value + original.right.value) / 2f
        val expectedScale = 1f - PredictiveBackDecelerate.transform(0.5f) * 0.12f

        compose.runOnIdle { input.backStarted(event(0f, NavigationEvent.EDGE_LEFT)) }
        compose.runOnIdle { input.backProgressed(event(0.5f, NavigationEvent.EDGE_LEFT)) }
        val leftScaled = layer.getBoundsInRoot()
        assertEquals(original.width.value * expectedScale, leftScaled.width.value, 2f)
        assertEquals(original.height.value * expectedScale, leftScaled.height.value, 2f)
        assertTrue("Left-edge drag shifts editor card right", (leftScaled.left.value + leftScaled.right.value) / 2f > origCenterX)

        compose.runOnIdle { input.backCancelled() }
        compose.runOnIdle { input.backStarted(event(0f, NavigationEvent.EDGE_RIGHT)) }
        compose.runOnIdle { input.backProgressed(event(0.5f, NavigationEvent.EDGE_RIGHT)) }
        val rightScaled = layer.getBoundsInRoot()
        assertEquals(original.width.value * expectedScale, rightScaled.width.value, 2f)
        assertTrue("Right-edge drag shifts editor card left", (rightScaled.left.value + rightScaled.right.value) / 2f < origCenterX)
    }

    @Test fun schoolAccountSubpagePredictiveBack_scalesOnDrag_cancelsWithoutPopping_andPopsOnComplete() {
        compose.setContent {
            NativeEvents {
                IYangoThemePreview {
                    SchoolAccountContent(SchoolAccountUiState(), {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("登录帮助").performScrollTo().performClick()
        val helpAction = compose.onNodeWithText("打开教务网站").performScrollTo()
        val original = helpAction.getBoundsInRoot()

        compose.runOnIdle { input.backStarted(event(0f)); input.backProgressed(event(0.5f)) }
        val scaled = helpAction.getBoundsInRoot()
        assertTrue("Subpage content must shrink during predictive back", scaled.width < original.width)

        compose.runOnIdle { input.backCancelled() }
        compose.onNodeWithText("打开教务网站").assertExists()
        assertEquals(original.width.value, helpAction.getBoundsInRoot().width.value, 1f)

        compose.runOnIdle { input.backStarted(event(0f)); input.backProgressed(event(0.6f)); input.backCompleted() }
        compose.onNodeWithText("打开教务网站").assertDoesNotExist()
        compose.onNodeWithText("教务网站").assertExists()
    }

    // ===== SecondaryPageRoute：全量路由解析 / 参数解析 / 非法路由拒收（多 Activity 架构） =====

    @Test fun secondaryPageRoute_parsesParameterizedRoutes() {
        val editor = SecondaryPageRoute.parse("editor?courseId=12&copyFromId=3")
        assertTrue(editor is SecondaryPageRoute.Editor)
        editor as SecondaryPageRoute.Editor
        assertEquals(12L, editor.courseId)
        assertEquals(3L, editor.copyFromId)
        assertEquals("editor?courseId=12&copyFromId=3", editor.route)

        val editorDefault = SecondaryPageRoute.parse("editor?courseId=0") as SecondaryPageRoute.Editor
        assertEquals(0L, editorDefault.courseId)
        assertEquals(0L, editorDefault.copyFromId)
        assertEquals("editor?courseId=0&copyFromId=0", editorDefault.route)

        // 缺省参数回退默认（与原 NavHost defaultValue 语义一致）。
        assertEquals(0L, (SecondaryPageRoute.parse("editor") as SecondaryPageRoute.Editor).courseId)
        assertEquals(0L, (SecondaryPageRoute.parse("editor?courseId=") as SecondaryPageRoute.Editor).copyFromId)

        val raw = SecondaryPageRoute.parse("courseraw/9")
        assertTrue(raw is SecondaryPageRoute.CourseRaw)
        assertEquals(9L, (raw as SecondaryPageRoute.CourseRaw).timetableId)
        assertEquals("courseraw/9", raw.route)
        assertEquals(0L, (SecondaryPageRoute.parse("courseraw") as SecondaryPageRoute.CourseRaw).timetableId)

        val display = SecondaryPageRoute.parse("display?perTimetable=true")
        assertTrue(display is SecondaryPageRoute.Display)
        assertTrue((display as SecondaryPageRoute.Display).perTimetable)
        assertEquals(false, (SecondaryPageRoute.parse("display?perTimetable=false") as SecondaryPageRoute.Display).perTimetable)
        assertEquals(false, (SecondaryPageRoute.parse("display") as SecondaryPageRoute.Display).perTimetable)
        assertEquals("display?perTimetable=false", (SecondaryPageRoute.parse("display?perTimetable=false") as SecondaryPageRoute.Display).route)
    }

    @Test fun secondaryRoute_whitelistedPlainRoutesAllParse() {
        val plainRoutes = listOf(
            "courses", "school", "schoolsync", "synchistory", "exams", "classroom",
            "timetable", "semester", "homesettings", "wallpaper", "font", "liquidglass",
            "dockglass", "dockglass/dev", "reminder", "importexport", "aiimport",
            "scheduleexport", "semestersummary", "settingsbackup", "creditprogress",
            "about", "runtimelogs",
        )
        for (route in plainRoutes + MineCategory.entries.map { it.route }) {
            val parsed = SecondaryPageRoute.parse(route)
            assertTrue("Route $route must be whitelisted", parsed is SecondaryPageRoute.Plain)
            assertEquals(route, (parsed as SecondaryPageRoute.Plain).id)
        }
        // LaunchIntents.ROUTES 中的二级路由必须全部可解析（深链入口对齐）。
        for (route in listOf("timetable", "courses", "semester", "semestersummary", "importexport", "display", "wallpaper", "reminder", "exams")) {
            assertNotNull("Deep-link route $route must parse", SecondaryPageRoute.parse(route))
        }
    }

    @Test fun secondaryRoute_rejectsUnknownAndMalformedRoutes() {
        // 未知路由。
        assertNull(SecondaryPageRoute.parse(null))
        assertNull(SecondaryPageRoute.parse(""))
        assertNull(SecondaryPageRoute.parse("main"))
        assertNull(SecondaryPageRoute.parse("nonsense"))
        // 主 Tab 不是二级页路由。
        listOf("today", "schedule", "grades", "mine").forEach { assertNull(SecondaryPageRoute.parse(it)) }
        // 带路径段的白名单路由非法。
        assertNull(SecondaryPageRoute.parse("editor/foo"))
        assertNull(SecondaryPageRoute.parse("wallpaper/extra"))
        assertNull(SecondaryPageRoute.parse("dockglass/dev/extra"))
        // 带查询串的无参路由非法。
        assertNull(SecondaryPageRoute.parse("wallpaper?x=1"))
        // 非法参数值与畸形路由整体拒收。
        assertNull(SecondaryPageRoute.parse("editor?courseId=abc"))
        assertNull(SecondaryPageRoute.parse("editor?copyFromId=1.5"))
        assertNull(SecondaryPageRoute.parse("courseraw/abc"))
        assertNull(SecondaryPageRoute.parse("courseraw/"))
        assertNull(SecondaryPageRoute.parse("courseraw/9?foo=bar"))
        assertNull(SecondaryPageRoute.parse("display?perTimetable=yes"))
        // 已知参数 + 未知查询键：参数合法即放行（未知键忽略）。
        assertNotNull(SecondaryPageRoute.parse("editor?courseId=1&unknown=2"))
    }

    @Test fun wallpaperViewModel_readsSnapshotOnFirstFrame_withoutOverwritingUnreadyNulls() {
        val prevLight = AppAppearanceSnapshot.globalWallpaperPath
        val prevDark = AppAppearanceSnapshot.globalWallpaperPathDark
        val cachedLight = "/data/local/tmp/cached_light.jpg"
        val cachedDark = "/data/local/tmp/cached_dark.jpg"
        try {
            AppAppearanceSnapshot.updateGlobalWallpaperPaths(cachedLight, cachedDark)
            val neverEmittingSettings = Proxy.newProxyInstance(
                AppSettingsPort::class.java.classLoader,
                arrayOf(AppSettingsPort::class.java),
            ) { _, method, _ ->
                if (method.name == "getAppPreferences") flow<AppPreferences> { awaitCancellation() }
                else error("Unexpected AppSettingsPort call: ${method.name}")
            } as AppSettingsPort
            val vm = WallpaperViewModel(
                appSettings = neverEmittingSettings,
                wallpaperStore = unusedPort<WallpaperStore>(),
                bingWallpaper = unusedPort<BingWallpaperPort>(),
            )
            // 新 Activity 刚创建尚未收到 DataStore 发射时，首帧必须同步读到快照路径且快照不被 (null, null) 倒灌覆盖。
            assertEquals(cachedLight, vm.globalWallpaperPath.value)
            assertEquals(cachedDark, vm.globalWallpaperPathDark.value)
            compose.runOnIdle {
                assertEquals(cachedLight, AppAppearanceSnapshot.globalWallpaperPath)
                assertEquals(cachedDark, AppAppearanceSnapshot.globalWallpaperPathDark)
            }
        } finally {
            AppAppearanceSnapshot.updateGlobalWallpaperPaths(prevLight, prevDark)
        }
    }

    @Test fun secondaryRoute_canonicalRouteRoundTrips() {
        val samples = listOf(
            "editor?courseId=12&copyFromId=3",
            "editor?courseId=0",
            "editor",
            "courseraw/9",
            "courseraw",
            "display?perTimetable=true",
            "display",
            "wallpaper",
            "dockglass/dev",
            "mine/appearance",
        )
        for (raw in samples) {
            val parsed = SecondaryPageRoute.parse(raw) ?: error("Sample route must parse: $raw")
            val reparsed = SecondaryPageRoute.parse(parsed.route)
            assertEquals("Canonical route must re-parse to the same route: $raw", parsed, reparsed)
        }
    }

    @Test fun disabledPredictiveBackSuppressesPreviewScale_whileStillCompletingBack() {
        var completed = 0
        lateinit var preview: BackPreviewState
        compose.setContent {
            NativeEvents {
                CompositionLocalProvider(LocalPredictiveBackEnabled provides false) {
                    val state = rememberBackPreviewState(motionEnabled = false)
                    SideEffect { preview = state }
                    NavigationBackHandler(
                        state.eventState,
                        isBackEnabled = true,
                        onBackCompleted = { state.complete(onCompleted = { completed++ }) },
                    )
                    Text("page")
                }
            }
        }
        compose.runOnIdle { input.backStarted(event(0f)); input.backProgressed(event(0.7f)) }
        compose.runOnIdle {
            assertEquals(0f, preview.progress, 0.001f)
            input.backCompleted()
        }
        compose.runOnIdle { assertEquals(1, completed) }
    }
}
