/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.integration.uiwidgets

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.TextureView
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.testing.CameraUtil
import androidx.camera.testing.CoreAppTestUtil
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle.State
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
@LargeTest
class ViewPager2ActivityTest(private val lensFacing: Int) {

    companion object {
        private const val ACTION_IDLE_TIMEOUT: Long = 5000
        @JvmStatic
        @Parameterized.Parameters(name = "lensFacing={0}")
        fun data() = listOf(CameraSelector.LENS_FACING_FRONT,
            CameraSelector.LENS_FACING_BACK)
    }

    @get:Rule
    val cameraPermissionRule: GrantPermissionRule =
            GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    private val mDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() {
        Assume.assumeTrue(CameraUtil.hasCameraWithLensFacing(lensFacing))

        // Clear the device UI before start each test.
        CoreAppTestUtil.clearDeviceUI(InstrumentationRegistry.getInstrumentation())
    }

    // The test makes sure the camera PreviewView is in the streaming state.
    @Test
    fun testPreviewViewUpdateAfterStopResume() {
        launchActivity(lensFacing).use { scenario ->
            // At first, check Preview in stream state
            assertStreamState(scenario, PreviewView.StreamState.STREAMING)

            // Go through Stop/Resume and then check Preview in stream state still
            scenario.moveToState(State.CREATED)
            scenario.moveToState(State.RESUMED)

            assertStreamState(scenario, PreviewView.StreamState.STREAMING)
        }
    }

    // The test makes sure the TextureView surface texture keeps the same after swipe out/in.
    @Test
    fun testPreviewViewUpdateAfterSwipeOutIn() {
        var newSurfaceTexture: SurfaceTexture? = null
        var surfaceTexture: SurfaceTexture? = null
        lateinit var previewView: PreviewView

        launchActivity(lensFacing).use { scenario ->
            // At first, check Preview in stream state
            assertStreamState(scenario, PreviewView.StreamState.STREAMING)

            scenario.onActivity { activity ->
                previewView = activity.findViewById(R.id.preview_textureview)
                surfaceTexture = getTextureView(previewView)!!.surfaceTexture
            }

            // swipe out CameraFragment and then swipe in to check Preview update
            onView(withId(R.id.viewPager2)).perform(swipeLeft())
            onView(withId(R.id.blank_textview)).check(matches(isDisplayed()))

            onView(withId(R.id.viewPager2)).perform(swipeRight())
            scenario.onActivity { activity ->
                previewView = activity.findViewById(R.id.preview_textureview)
                newSurfaceTexture = getTextureView(previewView)!!.surfaceTexture
            }

            // Except confirming the PreviewView is in the Streaming state, need to check if
            // there  are different surface textures usage in textureview for b/149877652 and
            // make sure the same surface texture when detach window and then attach window.
            assertThat(newSurfaceTexture).isEqualTo(surfaceTexture)
        }
    }

    @Test
    fun testPreviewViewUpdateAfterSwipeOutAndStop_ResumeAndSwipeIn() {
        var newSurfaceTexture: SurfaceTexture? = null
        var surfaceTexture: SurfaceTexture? = null
        lateinit var previewView: PreviewView

        launchActivity(lensFacing).use { scenario ->
            // At first, check Preview in stream state
            assertStreamState(scenario, PreviewView.StreamState.STREAMING)

            scenario.onActivity { activity ->
                previewView = activity.findViewById(R.id.preview_textureview)
                surfaceTexture = getTextureView(previewView)!!.surfaceTexture
            }

            // swipe out CameraFragment and then Stop and Resume ViewPager2Activity
            onView(withId(R.id.viewPager2)).perform(swipeLeft())
            onView(withId(R.id.blank_textview)).check(matches(isDisplayed()))

            scenario.moveToState(State.CREATED)
            scenario.moveToState(State.RESUMED)
            mDevice.waitForIdle(ACTION_IDLE_TIMEOUT)

            // After resume, swipe in CameraFragment to check Preview in stream state
            onView(withId(R.id.viewPager2)).perform(swipeRight())
            assertStreamState(scenario, PreviewView.StreamState.STREAMING)

            // As previous test, it also needs to compare the surfacetextures are the same. However,
            // the test has stop/resume and Preview surface texture will be recreated
            // when API level <= 26.
            scenario.onActivity { activity ->
                previewView = activity.findViewById(R.id.preview_textureview)
                newSurfaceTexture = getTextureView(previewView)!!.surfaceTexture
            }
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
                assertThat(newSurfaceTexture).isEqualTo(surfaceTexture)
            }
        }
    }

    private fun launchActivity(lensFacing: Int):
            ActivityScenario<ViewPager2Activity> {
        val intent = Intent(
            ApplicationProvider.getApplicationContext<Context>(),
            ViewPager2Activity::class.java
        )
        intent.putExtra(BaseActivity.INTENT_LENS_FACING, lensFacing)
        return ActivityScenario.launch<ViewPager2Activity>(intent)
    }

    private fun getTextureView(previewView: PreviewView): TextureView? {
        var index: Int = 0
        var textureView: TextureView? = null
        lateinit var childView: View

        while (index < previewView.childCount) {
            childView = previewView.getChildAt(index)
            if (childView is TextureView) {
                textureView = childView
                break
            }
            index++
        }
        return textureView
    }

    private fun assertStreamState(
        scenario: ActivityScenario<ViewPager2Activity>,
        expectStreamState: PreviewView.StreamState
    ) = runBlocking<Unit> {
        lateinit var result: Deferred<Boolean>

        scenario.onActivity { activity ->
            // Make async Coroutine to wait the result, not block the test thread.
            result = async { activity.waitForStreamState(expectStreamState) }
        }

        assertThat(result.await()).isTrue()
    }
}
