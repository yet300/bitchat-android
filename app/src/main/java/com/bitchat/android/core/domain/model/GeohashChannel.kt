package com.bitchat.android.core.domain.model

/**
 * Уровни гео-каналов (точность geohash). Чистый порт из текущего geohash-слоя для
 * совместимости. Геокодер/координаты остаются инфраструктурой — в domain только эти значения.
 */
enum class GeohashLevel(val precision: Int) {
    BUILDING(8),
    BLOCK(7),
    NEIGHBORHOOD(6),
    CITY(5),
    PROVINCE(4),
    REGION(2),
}

/** Вычисленный гео-канал: уровень + geohash-строка. */
data class GeohashChannel(
    val level: GeohashLevel,
    val geohash: String,
)
