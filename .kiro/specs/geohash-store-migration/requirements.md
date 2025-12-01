# Requirements Document: GeohashViewModel to Store Migration

## Introduction

Complete the MVI migration by eliminating GeohashViewModel from the feature layer. GeohashViewModel is currently used in ChatStoreFactory and LocationChannelsStoreFactory, violating the pure MVI architecture principle. This migration will move geohash functionality into proper Store-based architecture.

## Glossary

- **GeohashViewModel**: Legacy AndroidViewModel managing geohash/Nostr features
- **ChatStore**: MVI Store managing chat state and operations
- **LocationChannelsStore**: MVI Store managing location channel selection
- **GeohashRepository**: Data layer managing geohash participant data
- **NostrSubscriptionManager**: Manages Nostr relay subscriptions
- **Store**: MVIKotlin Store following MVI pattern (Intent → Executor → Reducer → State)

## Requirements

### Requirement 1

**User Story:** As a developer, I want geohash functionality integrated into ChatStore, so that the feature layer uses pure MVI architecture without ViewModels.

#### Acceptance Criteria

1. WHEN ChatStoreFactory is instantiated THEN the system SHALL NOT inject GeohashViewModel
2. WHEN geohash people data changes THEN ChatStore SHALL receive updates directly from GeohashRepository
3. WHEN geohash participant counts change THEN ChatStore SHALL receive updates directly from GeohashRepository
4. WHEN a user sends a geohash message THEN ChatStore SHALL handle the operation without delegating to GeohashViewModel
5. WHEN a user starts a geohash DM THEN ChatStore SHALL handle the operation without delegating to GeohashViewModel

### Requirement 2

**User Story:** As a developer, I want LocationChannelsStore to access geohash data directly, so that it doesn't depend on GeohashViewModel.

#### Acceptance Criteria

1. WHEN LocationChannelsStoreFactory is instantiated THEN the system SHALL NOT inject GeohashViewModel
2. WHEN geohash participant counts are needed THEN LocationChannelsStore SHALL subscribe to GeohashRepository directly
3. WHEN geohash sampling begins THEN LocationChannelsStore SHALL interact with NostrSubscriptionManager directly
4. WHEN geohash sampling ends THEN LocationChannelsStore SHALL interact with NostrSubscriptionManager directly

### Requirement 3

**User Story:** As a developer, I want GeohashRepository to be a service, so that multiple Stores can access geohash data without coupling to a ViewModel.

#### Acceptance Criteria

1. WHEN GeohashRepository is created THEN the system SHALL NOT depend on ChatState
2. WHEN GeohashRepository updates participant data THEN the system SHALL expose updates via StateFlow
3. WHEN GeohashRepository updates people list THEN the system SHALL expose updates via StateFlow
4. WHEN multiple Stores subscribe to GeohashRepository THEN the system SHALL provide consistent data to all subscribers

### Requirement 4

**User Story:** As a developer, I want to delete GeohashViewModel after migration, so that the codebase only uses MVI architecture.

#### Acceptance Criteria

1. WHEN the migration is complete THEN the system SHALL have zero references to GeohashViewModel in feature layer
2. WHEN GeohashViewModel is deleted THEN the system SHALL compile successfully
3. WHEN the app runs THEN all geohash features SHALL function identically to before migration
4. WHEN tests run THEN all existing tests SHALL pass without modification

### Requirement 5

**User Story:** As a developer, I want clear separation between data layer and presentation layer, so that the architecture is maintainable.

#### Acceptance Criteria

1. WHEN geohash data is needed THEN Stores SHALL access GeohashRepository (data layer) directly
2. WHEN Nostr subscriptions are needed THEN Stores SHALL access NostrSubscriptionManager directly
3. WHEN geohash messages are sent THEN Stores SHALL use NostrTransport and NostrProtocol directly
4. WHEN the architecture is reviewed THEN the system SHALL show clear layering: Services → Repository → Store → UI
5. WHEN new geohash features are added THEN developers SHALL add them to GeohashRepository or Store, not ViewModel
