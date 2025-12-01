# Requirements Document

## Introduction

This document specifies requirements for refactoring BitChat's deep link handling to follow Decompose best practices. The current implementation handles notification-based deep links but doesn't use Decompose's recommended `handleDeepLink` extension function and lacks support for external URI deep links. This refactoring will consolidate deep link handling, add proper serialization support, and enable future external URI deep link support.

## Glossary

- **Deep Link**: A URL or intent that navigates directly to specific content within the app
- **DeepLinkData**: Data class representing parsed deep link information for navigation
- **RootComponent**: The top-level Decompose component managing app navigation
- **ChatComponent**: The main chat screen component that receives deep link navigation
- **ChatStartupConfig**: Configuration specifying initial state when ChatComponent is created
- **handleDeepLink**: Decompose extension function for processing deep links in Activity

## Requirements

### Requirement 1

**User Story:** As a user, I want to tap a notification and be taken directly to the relevant chat, so that I can quickly respond to messages.

#### Acceptance Criteria

1. WHEN a user taps a private message notification THEN the System SHALL navigate to the private chat with the specified peer
2. WHEN a user taps a geohash channel notification THEN the System SHALL navigate to the geohash chat for the specified location
3. WHEN the app is not initialized and a deep link is received THEN the System SHALL store the deep link and process it after initialization completes
4. WHEN a deep link is processed THEN the System SHALL clear any pending deep link state

### Requirement 2

**User Story:** As a developer, I want deep link data to be serializable, so that navigation state can be properly saved and restored by Decompose.

#### Acceptance Criteria

1. WHEN deep link data is included in navigation configuration THEN the System SHALL serialize the data using kotlinx-serialization
2. WHEN the app is restored from saved state with a deep link configuration THEN the System SHALL deserialize and restore the navigation correctly
3. WHEN defining deep link data types THEN the System SHALL use sealed classes with @Serializable annotation

### Requirement 3

**User Story:** As a developer, I want to use Decompose's handleDeepLink extension, so that deep link processing follows framework best practices.

#### Acceptance Criteria

1. WHEN MainActivity receives an intent with deep link data THEN the System SHALL use Decompose's handleDeepLink extension function
2. WHEN handleDeepLink processes a deep link THEN the System SHALL discard saved state to ensure fresh navigation
3. WHEN handleDeepLink returns null THEN the System SHALL handle the case gracefully without crashing

### Requirement 4

**User Story:** As a developer, I want a single source of truth for deep link data types, so that the codebase is maintainable and avoids duplication.

#### Acceptance Criteria

1. WHEN defining deep link types THEN the System SHALL use a single sealed class hierarchy in the root feature package
2. WHEN ChatComponent needs startup configuration THEN the System SHALL derive it from the deep link data without duplication
3. WHEN extracting deep links from intents THEN the System SHALL use a dedicated extractor object with clear factory methods

### Requirement 5

**User Story:** As a user, I want the app to handle new intents while running, so that I can navigate via notifications without restarting the app.

#### Acceptance Criteria

1. WHEN onNewIntent is called with deep link data THEN the System SHALL navigate to the specified destination
2. WHEN the app is already showing the target destination THEN the System SHALL update the view appropriately
3. WHEN processing a new intent deep link THEN the System SHALL not disrupt ongoing operations unnecessarily
