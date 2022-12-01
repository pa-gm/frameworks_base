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

package com.android.settingslib.spa.framework.common

import android.content.UriMatcher
import androidx.annotation.VisibleForTesting

/**
 * Enum to define all column names in provider.
 */
enum class ColumnEnum(val id: String) {
    // Columns related to page
    PAGE_ID("pageId"),
    PAGE_NAME("pageName"),
    PAGE_ROUTE("pageRoute"),
    PAGE_INTENT_URI("pageIntent"),
    PAGE_ENTRY_COUNT("entryCount"),
    HAS_RUNTIME_PARAM("hasRuntimeParam"),
    PAGE_START_ADB("pageStartAdb"),

    // Columns related to entry
    ENTRY_ID("entryId"),
    ENTRY_NAME("entryName"),
    ENTRY_ROUTE("entryRoute"),
    ENTRY_INTENT_URI("entryIntent"),
    ENTRY_HIERARCHY_PATH("entryPath"),
    ENTRY_START_ADB("entryStartAdb"),

    // Columns related to search
    SEARCH_TITLE("searchTitle"),
    SEARCH_KEYWORD("searchKw"),
    SEARCH_PATH("searchPath"),
    SEARCH_STATUS_DISABLED("searchDisabled"),
}

/**
 * Enum to define all queries supported in the provider.
 */
enum class QueryEnum(
    val queryPath: String,
    val queryMatchCode: Int,
    val columnNames: List<ColumnEnum>
) {
    // For debug
    PAGE_DEBUG_QUERY(
        "page_debug", 1,
        listOf(ColumnEnum.PAGE_START_ADB)
    ),
    ENTRY_DEBUG_QUERY(
        "entry_debug", 2,
        listOf(ColumnEnum.ENTRY_START_ADB)
    ),

    // page related queries.
    PAGE_INFO_QUERY(
        "page_info", 100,
        listOf(
            ColumnEnum.PAGE_ID,
            ColumnEnum.PAGE_NAME,
            ColumnEnum.PAGE_ROUTE,
            ColumnEnum.PAGE_INTENT_URI,
            ColumnEnum.PAGE_ENTRY_COUNT,
            ColumnEnum.HAS_RUNTIME_PARAM,
        )
    ),

    // entry related queries
    ENTRY_INFO_QUERY(
        "entry_info", 200,
        listOf(
            ColumnEnum.ENTRY_ID,
            ColumnEnum.ENTRY_NAME,
            ColumnEnum.ENTRY_ROUTE,
            ColumnEnum.ENTRY_INTENT_URI,
            ColumnEnum.ENTRY_HIERARCHY_PATH,
        )
    ),

    SEARCH_STATIC_DATA_QUERY(
        "search_static", 301,
        listOf(
            ColumnEnum.ENTRY_ID,
            ColumnEnum.ENTRY_INTENT_URI,
            ColumnEnum.SEARCH_TITLE,
            ColumnEnum.SEARCH_KEYWORD,
            ColumnEnum.SEARCH_PATH,
        )
    ),
    SEARCH_DYNAMIC_DATA_QUERY(
        "search_dynamic", 302,
        listOf(
            ColumnEnum.ENTRY_ID,
            ColumnEnum.ENTRY_INTENT_URI,
            ColumnEnum.SEARCH_TITLE,
            ColumnEnum.SEARCH_KEYWORD,
            ColumnEnum.SEARCH_PATH,
        )
    ),
    SEARCH_IMMUTABLE_STATUS_DATA_QUERY(
        "search_immutable_status", 303,
        listOf(
            ColumnEnum.ENTRY_ID,
            ColumnEnum.SEARCH_STATUS_DISABLED,
        )
    ),
    SEARCH_MUTABLE_STATUS_DATA_QUERY(
        "search_mutable_status", 304,
        listOf(
            ColumnEnum.ENTRY_ID,
            ColumnEnum.SEARCH_STATUS_DISABLED,
        )
    ),
}

@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
fun QueryEnum.getColumns(): Array<String> {
    return columnNames.map { it.id }.toTypedArray()
}

@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
fun QueryEnum.getIndex(name: ColumnEnum): Int {
    return columnNames.indexOf(name)
}

@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
fun QueryEnum.addUri(uriMatcher: UriMatcher, authority: String) {
    uriMatcher.addURI(authority, queryPath, queryMatchCode)
}
