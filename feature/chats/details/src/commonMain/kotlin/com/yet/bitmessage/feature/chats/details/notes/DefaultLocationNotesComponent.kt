package com.yet.bitmessage.feature.chats.details.notes

import com.app.common.decompose.asValue
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.LocationNotesRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yet.bitmessage.feature.chats.details.notes.store.LocationNotesStore
import com.yet.bitmessage.feature.chats.details.notes.store.LocationNotesStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultLocationNotesComponent(
    componentContext: ComponentContext,
    storeFactory: LocationNotesStoreFactory,
    private val onClose: () -> Unit,
) : LocationNotesComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }

    override val model: Value<LocationNotesComponent.Model> = store.asValue().map { state ->
        LocationNotesComponent.Model(
            notes = state.notes,
            isLoading = state.isLoading,
            draft = state.draft,
            canSend = state.canSend,
        )
    }

    override fun onDraftChanged(text: String) = store.accept(LocationNotesStore.Intent.DraftChanged(text))

    override fun onSendClicked() = store.accept(LocationNotesStore.Intent.SendClicked)

    override fun onCloseClicked() = onClose()
}

@Inject
internal class DefaultLocationNotesComponentFactory(
    private val storeFactory: StoreFactory,
    private val locationNotes: LocationNotesRepository,
    private val identityRepository: IdentityRepository,
) : LocationNotesComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        geohash: String,
        onClose: () -> Unit,
    ): LocationNotesComponent = DefaultLocationNotesComponent(
        componentContext = componentContext,
        storeFactory = LocationNotesStoreFactory(storeFactory, locationNotes, identityRepository, geohash),
        onClose = onClose,
    )
}
