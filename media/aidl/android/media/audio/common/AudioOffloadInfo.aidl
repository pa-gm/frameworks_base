/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.media.audio.common;

import android.media.audio.common.AudioChannelLayout;
import android.media.audio.common.AudioFormatDescription;
import android.media.audio.common.AudioStreamType;
import android.media.audio.common.AudioUsage;

/**
 * Additional information about the stream passed to hardware decoders.
 *
 * {@hide}
 */
@JavaDerive(equals = true, toString = true)
@VintfStability
parcelable AudioOffloadInfo {
    int sampleRateHz;
    AudioChannelLayout channelMask;
    AudioFormatDescription format;
    AudioStreamType streamType = AudioStreamType.INVALID;
    int bitRatePerSecond;
    long durationMicroseconds;
    boolean hasVideo;
    boolean isStreaming;
    int bitWidth;
    int bufferSize;
    AudioUsage usage = AudioUsage.INVALID;
}
