package com.gfs.iyango.feature.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.feature.main.coursedetail.CourseDetailDialog
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetainedMainTabsTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun PageWithCompositionAndMeasurementCounters(
        index: Int,
        starts: IntArray,
        disposed: IntArray,
        measures: IntArray,
        compositions: IntArray,
    ) {
        SideEffect { compositions[index]++ }
        var clicks by remember { mutableIntStateOf(0) }
        DisposableEffect(Unit) {
            starts[index]++
            onDispose { disposed[index]++ }
        }
        Box(Modifier.fillMaxSize().layout { measurable, constraints ->
            measures[index]++
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }) {
            Text("page $index: $clicks", Modifier.clickable { clicks++ })
        }
    }

    @Test fun revisitingRetainsCompositionAndLocalState_withoutMeasuringHiddenPages() {
        var selected by mutableIntStateOf(0)
        val starts = IntArray(4)
        val disposed = IntArray(4)
        val measures = IntArray(4)
        val compositions = IntArray(4)
        var compositionsBeforeHide = 0
        var measuresBeforeHide = 0
        compose.setContent {
            RetainedMainTabs(selected) { index ->
                PageWithCompositionAndMeasurementCounters(index, starts, disposed, measures, compositions)
            }
        }
        compose.onNodeWithText("page 0: 0").performClick()
        compose.runOnIdle {
            compositionsBeforeHide = compositions[0]
            measuresBeforeHide = measures[0]
            selected = 1
        }
        compose.onNodeWithText("page 0: 1").assertDoesNotExist()
        compose.onNodeWithText("page 1: 0").assertIsDisplayed()
        val hiddenMeasures = measures[0]
        compose.runOnIdle { selected = 2 }
        compose.runOnIdle {
            assertEquals(hiddenMeasures, measures[0])
            assertEquals(0, starts[3])
            assertEquals(0, disposed[0])
            selected = 0
        }
        compose.onNodeWithText("page 0: 1").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, starts[0])
            assertEquals("Unchanged content must not recompose solely for tab visibility", compositionsBeforeHide, compositions[0])
            assertEquals("Unchanged content must reuse its measurements on return", measuresBeforeHide, measures[0])
        }
    }

    @Test fun secondaryPageCoverage_hidesMainSemanticsAndRetainsState() {
        var visible by mutableStateOf(true)
        compose.setContent {
            RetainedMainTabs(1, visible) {
                var clicks by rememberSaveable { mutableIntStateOf(0) }
                Text("schedule $clicks", Modifier.clickable { clicks++ })
            }
        }
        compose.onNodeWithText("schedule 0").performClick()
        compose.runOnIdle { visible = false }
        compose.onNodeWithText("schedule 1").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("schedule 1").assertIsDisplayed()
    }

    @Test fun lifecyclesAreCappedByVisibilityAndParent() {
        val parent = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        parent.lifecycle.currentState = Lifecycle.State.RESUMED
        var selected by mutableIntStateOf(0)
        val owners = arrayOfNulls<LifecycleOwner>(4)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides parent) {
                RetainedMainTabs(selected) { index ->
                    val owner = LocalLifecycleOwner.current
                    SideEffect { owners[index] = owner }
                    Text("page $index")
                }
            }
        }
        compose.runOnIdle { selected = 1 }
        compose.runOnIdle {
            assertEquals(Lifecycle.State.CREATED, owners[0]!!.lifecycle.currentState)
            assertEquals(Lifecycle.State.RESUMED, owners[1]!!.lifecycle.currentState)
            parent.lifecycle.currentState = Lifecycle.State.CREATED
        }
        compose.runOnIdle {
            assertEquals(Lifecycle.State.CREATED, owners[1]!!.lifecycle.currentState)
            parent.lifecycle.currentState = Lifecycle.State.DESTROYED
        }
        compose.runOnIdle { assertEquals(Lifecycle.State.DESTROYED, owners[1]!!.lifecycle.currentState) }
    }

    @Test fun restoredSelectionAndEachPagesSaveableStateStayIndependent() {
        val restoration = StateRestorationTester(compose)
        var select: (Int) -> Unit = {}
        restoration.setContent {
            var selected by rememberSaveable { mutableIntStateOf(0) }
            SideEffect { select = { selected = it } }
            RetainedMainTabs(selected) { index ->
                var clicks by rememberSaveable { mutableIntStateOf(0) }
                Text("page $index: $clicks", Modifier.clickable { clicks++ })
            }
        }
        compose.onNodeWithText("page 0: 0").performClick()
        compose.runOnIdle { select(2) }
        compose.onNodeWithText("page 2: 0").performClick()
        compose.onNodeWithText("page 2: 1").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("page 2: 2").assertIsDisplayed()
        compose.runOnIdle { select(0) }
        compose.onNodeWithText("page 0: 1").assertIsDisplayed()
    }

    class PageViewModel : ViewModel()

    @Test fun pageViewModelsAreIndependent_andSurviveRevisit() {
        var selected by mutableIntStateOf(0)
        val models = arrayOfNulls<PageViewModel>(4)
        compose.setContent {
            RetainedMainTabs(selected) { index ->
                val model = viewModel<PageViewModel>()
                SideEffect { models[index] = model }
                Text("page $index")
            }
        }
        compose.runOnIdle { selected = 1 }
        lateinit var first: PageViewModel
        compose.runOnIdle {
            first = models[0]!!
            org.junit.Assert.assertNotSame(first, models[1])
            selected = 0
        }
        compose.runOnIdle { org.junit.Assert.assertSame(first, models[0]) }
    }

    @Test fun hidingPageRemovesItsDialogWindow_andClearsTextInputFocus() {
        var visible by mutableStateOf(true)
        var showDialog by mutableStateOf(false)
        compose.setContent {
            RetainedMainTabs(0, visible) {
                BasicTextField("draft", {}, Modifier.testTag("draft"))
                if (showDialog) CourseDetailDialog(
                    courses = listOf(Course(name = "retained course")),
                    timeSlots = emptyList(),
                    displayedWeek = 1,
                    onEdit = {}, onCopy = {}, onDelete = { _, _ -> },
                    onAdjust = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("draft").performTouchInput { click() }
        compose.onNodeWithTag("draft").assertIsFocused()
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("draft").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.onNodeWithTag("draft").assertIsNotFocused()
        compose.runOnIdle { showDialog = true }
        compose.onNodeWithText("retained course").assertIsDisplayed()
        compose.runOnIdle { visible = false }
        compose.onNodeWithText("retained course").assertDoesNotExist()
    }

    @Test fun editingCourseDismissesSnapshotBeforeNavigation_andReturnDoesNotReopenDialog() {
        val events = mutableListOf<String>()
        compose.setContent {
            val navigation = rememberNavController()
            val entry by navigation.currentBackStackEntryAsState()
            val mainVisible = entry == null || entry?.destination?.route == "main"
            Box(Modifier.fillMaxSize()) {
                RetainedMainTabs(1, mainVisible) {
                    var currentCourse by remember { mutableStateOf(Course(id = 7, name = "original course")) }
                    var detailSnapshot by remember { mutableStateOf<Course?>(currentCourse) }
                    Text("current course: ${currentCourse.name}")
                    detailSnapshot?.let { course ->
                        CourseDetailDialog(
                            courses = listOf(course), timeSlots = emptyList(), displayedWeek = 1,
                            onEdit = {
                                events += "edit ${it.id}"
                                currentCourse = it.copy(name = "edited course")
                                navigation.navigate("editor")
                            },
                            onCopy = {}, onDelete = { _, _ -> }, onAdjust = {},
                            onDismiss = { events += "dismiss"; detailSnapshot = null },
                        )
                    }
                }
                NavHost(
                    navController = navigation, startDestination = "main",
                    enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None },
                ) {
                    composable("main") {}
                    composable("editor") {
                        Text("return from editor", Modifier.clickable { navigation.popBackStack() })
                    }
                }
            }
        }
        compose.onNodeWithText("original course").assertIsDisplayed()
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithText("return from editor").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("dismiss", "edit 7"), events) }
        compose.onNodeWithText("return from editor").performClick()
        compose.onNodeWithText("current course: edited course").assertIsDisplayed()
        compose.onNodeWithText("original course").assertDoesNotExist()
        compose.onNodeWithText("编辑").assertDoesNotExist()
    }
}
