package com.yet.bitmessage.feature.chats.details.notes.store

import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.LocationNotesRepository
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch

internal class LocationNotesStoreFactory(
    private val storeFactory: StoreFactory,
    private val locationNotes: LocationNotesRepository,
    private val identityRepository: IdentityRepository,
    private val geohash: String,
) {
    fun create(): LocationNotesStore =
        object : LocationNotesStore,
            Store<LocationNotesStore.Intent, LocationNotesStore.State, Nothing> by storeFactory.create(
                name = "LocationNotesStore",
                initialState = LocationNotesStore.State(),
                bootstrapper = SimpleBootstrapper(LocationNotesStore.Action.Load),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<LocationNotesStore.State, LocationNotesStore.Msg> {
        override fun LocationNotesStore.State.reduce(msg: LocationNotesStore.Msg): LocationNotesStore.State =
            when (msg) {
                is LocationNotesStore.Msg.NotesLoaded -> copy(notes = msg.notes)
                is LocationNotesStore.Msg.LoadingChanged -> copy(isLoading = msg.isLoading)
                is LocationNotesStore.Msg.DraftChanged -> copy(draft = msg.text)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<LocationNotesStore.Intent, LocationNotesStore.Action, LocationNotesStore.State, LocationNotesStore.Msg, Nothing>() {

        override fun executeAction(action: LocationNotesStore.Action) {
            when (action) {
                LocationNotesStore.Action.Load -> {
                    locationNotes.select(geohash)
                    scope.launch {
                        locationNotes.observeNotes().collect { dispatch(LocationNotesStore.Msg.NotesLoaded(it)) }
                    }
                    scope.launch {
                        locationNotes.observeLoading().collect { dispatch(LocationNotesStore.Msg.LoadingChanged(it)) }
                    }
                }
            }
        }

        override fun executeIntent(intent: LocationNotesStore.Intent) {
            when (intent) {
                is LocationNotesStore.Intent.DraftChanged -> dispatch(LocationNotesStore.Msg.DraftChanged(intent.text))
                LocationNotesStore.Intent.SendClicked -> {
                    val text = state().draft
                    if (text.isBlank()) return
                    dispatch(LocationNotesStore.Msg.DraftChanged(""))
                    scope.launch { locationNotes.send(text, identityRepository.myIdentity().nickname) }
                }
            }
        }
    }
}
