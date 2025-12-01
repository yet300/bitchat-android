# Implementation Plan: GeohashViewModel to Store Migration

- [x] 1. Refactor GeohashRepository to be a standalone service
  - Remove ChatState dependency from constructor
  - Add StateFlow properties for geohashPeople, geohashParticipantCounts, currentGeohash
  - Convert state mutations to StateFlow emissions
  - _Requirements: 3.1, 3.2, 3.3_

- [ ] 2. Extract GeohashMessageHandler from GeohashViewModel
  - Create standalone GeohashMessageHandler class
  - Move message processing logic from GeohashViewModel
  - Add callback for message received events
  - _Requirements: 1.4_

- [ ] 3. Add geohash state to ChatStore.State
  - Add currentGeohash, geohashPeople, geohashParticipantCounts fields
  - Add corresponding Msg types for updates
  - Update reducer to handle geohash state changes
  - _Requirements: 1.2, 1.3_

- [ ] 4. Add geohash intents to ChatStore
  - Add SendGeohashMessage intent
  - Add StartGeohashDM intent
  - Add BlockUserInGeohash intent
  - Add BeginGeohashSampling and EndGeohashSampling intents
  - _Requirements: 1.4, 1.5_

- [ ] 5. Implement geohash operations in ChatStoreFactory
  - Subscribe to GeohashRepository StateFlows
  - Implement sendGeohashMessage using NostrTransport directly
  - Implement startGeohashDM using NostrSubscriptionManager directly
  - Implement blockUserInGeohash using GeohashRepository directly
  - Implement geohash sampling using NostrSubscriptionManager directly
  - _Requirements: 1.1, 1.4, 1.5_

- [ ] 6. Update LocationChannelsStoreFactory
  - Remove GeohashViewModel injection
  - Subscribe to GeohashRepository directly for participant counts
  - Implement geohash sampling intents using NostrSubscriptionManager
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [ ] 7. Remove GeohashViewModel from ChatStoreFactory
  - Remove geohashViewModel injection
  - Remove all geohashViewModel method calls
  - Verify all geohash functionality works through Store
  - _Requirements: 1.1, 4.1_

- [ ] 8. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. Delete GeohashViewModel and related files
  - Delete GeohashViewModel.kt
  - Delete ChatState.kt (if no longer used)
  - Delete legacy managers if no longer used
  - _Requirements: 4.2_

- [ ] 10. Final verification
  - Run full test suite
  - Verify app functionality
  - Verify no GeohashViewModel references remain
  - _Requirements: 4.3, 4.4_
