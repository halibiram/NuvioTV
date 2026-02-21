package com.nuvio.tv.domain.model

import androidx.annotation.Keep

@Keep
data class SearchHistoryItem(
    val id: String,
    val type: String,
    val title: String,
    val posterUrl: String?
)
