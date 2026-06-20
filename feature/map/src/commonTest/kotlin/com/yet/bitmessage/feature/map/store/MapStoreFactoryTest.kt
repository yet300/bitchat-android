@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.map.store

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun tapping_selects_a_building_level_geohash_at_high_zoom() = runTest {
        val store = MapStoreFactory(DefaultStoreFactory()).create()
        assertNull(store.state.selectedGeohash)

        store.accept(MapStore.Intent.Tapped(latitude = 37.7749, longitude = -122.4194, zoom = 11.0))

        val selected = store.state.selectedGeohash
        assertTrue(selected != null && selected.length == 8, "expected an 8-char geohash, got $selected")
    }

    @Test
    fun lower_zoom_yields_a_coarser_geohash() = runTest {
        val store = MapStoreFactory(DefaultStoreFactory()).create()
        store.accept(MapStore.Intent.Tapped(latitude = 37.7749, longitude = -122.4194, zoom = 5.0))
        assertEquals(5, store.state.selectedGeohash?.length)
    }
}
