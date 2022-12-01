/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.media.IAudioService;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper class to provide queries for app states concerning gentle-update.
 */
public class AppStateHelper {
    private final Context mContext;

    public AppStateHelper(Context context) {
        mContext = context;
    }

    /**
     * True if the package is loaded into the process.
     */
    private static boolean isPackageLoaded(RunningAppProcessInfo info, String packageName) {
        return ArrayUtils.contains(info.pkgList, packageName)
                || ArrayUtils.contains(info.pkgDeps, packageName);
    }

    /**
     * Returns the importance of the given package.
     */
    private int getImportance(String packageName) {
        var am = mContext.getSystemService(ActivityManager.class);
        return am.getPackageImportance(packageName);
    }

    /**
     * True if the app owns the audio focus.
     */
    private boolean hasAudioFocus(String packageName) {
        var audioService = IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE));
        try {
            var focusInfos = audioService.getFocusStack();
            int size = focusInfos.size();
            var audioFocusPackage = (size > 0) ? focusInfos.get(size - 1).getPackageName() : null;
            return TextUtils.equals(packageName, audioFocusPackage);
        } catch (Exception ignore) {
        }
        return false;
    }

    /**
     * True if the app is in the foreground.
     */
    private boolean isAppForeground(String packageName) {
        return getImportance(packageName) <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
    }

    /**
     * True if the app is currently at the top of the screen that the user is interacting with.
     */
    public boolean isAppTopVisible(String packageName) {
        return getImportance(packageName) <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    /**
     * True if the app is playing/recording audio.
     */
    private boolean hasActiveAudio(String packageName) {
        // TODO(b/235306967): also check recording
        return hasAudioFocus(packageName);
    }

    /**
     * True if the app is sending or receiving network data.
     */
    private boolean hasActiveNetwork(String packageName) {
        // To be implemented
        return false;
    }

    /**
     * True if any app is interacting with the user.
     */
    public boolean hasInteractingApp(List<String> packageNames) {
        for (var packageName : packageNames) {
            if (hasActiveAudio(packageName)
                    || hasActiveNetwork(packageName)
                    || isAppTopVisible(packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if any app is in the foreground.
     */
    public boolean hasForegroundApp(List<String> packageNames) {
        for (var packageName : packageNames) {
            if (isAppForeground(packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if any app is top visible.
     */
    public boolean hasTopVisibleApp(List<String> packageNames) {
        for (var packageName : packageNames) {
            if (isAppTopVisible(packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if there is an ongoing phone call.
     */
    public boolean isInCall() {
        // To be implemented
        return false;
    }

    /**
     * Returns a list of packages which depend on {@code packageNames}. These are the packages
     * that will be affected when updating {@code packageNames} and should participate in
     * the evaluation of install constraints.
     *
     * TODO(b/235306967): Also include bounded services as dependency.
     */
    public List<String> getDependencyPackages(List<String> packageNames) {
        var results = new ArraySet<String>();
        var am = mContext.getSystemService(ActivityManager.class);
        for (var info : am.getRunningAppProcesses()) {
            for (var packageName : packageNames) {
                if (!isPackageLoaded(info, packageName)) {
                    continue;
                }
                for (var pkg : info.pkgList) {
                    results.add(pkg);
                }
            }
        }
        return new ArrayList<>(results);
    }
}
