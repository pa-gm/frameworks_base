/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.flicker.bubble

import android.app.INotificationManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.ServiceManager
import android.tools.common.Rotation
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import android.tools.device.flicker.legacy.IFlickerTestData
import android.tools.device.helpers.SYSTEMUI_PACKAGE
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.helpers.LaunchBubbleHelper
import com.android.wm.shell.flicker.BaseTest
import org.junit.runners.Parameterized

/** Base configurations for Bubble flicker tests */
abstract class BaseBubbleScreen(flicker: FlickerTest) : BaseTest(flicker) {

    protected val context: Context = instrumentation.context
    protected val testApp = LaunchBubbleHelper(instrumentation)

    private val notifyManager =
        INotificationManager.Stub.asInterface(
            ServiceManager.getService(Context.NOTIFICATION_SERVICE)
        )

    private val uid =
        context.packageManager
            .getApplicationInfo(testApp.`package`, PackageManager.ApplicationInfoFlags.of(0))
            .uid

    @JvmOverloads
    protected open fun buildTransition(
        extraSpec: FlickerBuilder.() -> Unit = {}
    ): FlickerBuilder.() -> Unit {
        return {
            setup {
                notifyManager.setBubblesAllowed(
                    testApp.`package`,
                    uid,
                    NotificationManager.BUBBLE_PREFERENCE_ALL
                )
                testApp.launchViaIntent(wmHelper)
                waitAndGetAddBubbleBtn()
                waitAndGetCancelAllBtn()
            }

            teardown {
                notifyManager.setBubblesAllowed(
                    testApp.`package`,
                    uid,
                    NotificationManager.BUBBLE_PREFERENCE_NONE
                )
                testApp.exit()
            }

            extraSpec(this)
        }
    }

    protected fun IFlickerTestData.waitAndGetAddBubbleBtn(): UiObject2? =
        device.wait(Until.findObject(By.text("Add Bubble")), FIND_OBJECT_TIMEOUT)
    protected fun IFlickerTestData.waitAndGetCancelAllBtn(): UiObject2? =
        device.wait(Until.findObject(By.text("Cancel All Bubble")), FIND_OBJECT_TIMEOUT)

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(Rotation.ROTATION_0)
            )
        }

        const val FIND_OBJECT_TIMEOUT = 2000L
        const val SYSTEM_UI_PACKAGE = SYSTEMUI_PACKAGE
        const val BUBBLE_RES_NAME = "bubble_view"
    }
}
