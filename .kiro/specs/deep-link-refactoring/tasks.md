# Implementation Plan

- [ ] 1. Refactor DeepLinkData to proper sealed interface
  - [ ] 1.1 Create @Serializable DeepLinkData sealed interface with None, PrivateChat, and GeohashChat variants
    - Replace type alias with proper sealed interface in `feature/root/DeepLinkData.kt`
    - Add kotlinx-serialization @Serializable annotations
    - Add `toChatStartupConfig()` extension function
    - _Requirements: 2.1, 2.3, 4.1_
  - [ ] 1.2 Write property test for DeepLinkData serialization round-trip
    - **Property 2: DeepLinkData serialization round-trip**
    - **Validates: Requirements 2.1, 2.2**
  - [ ] 1.3 Write property test for DeepLinkData to ChatStartupConfig mapping
    - **Property 3: DeepLinkData to ChatStartupConfig mapping consistency**
    - **Validates: Requirements 4.2**

- [ ] 2. Update DeepLinkExtractor
  - [ ] 2.1 Refactor DeepLinkExtractor to extract from Intent
    - Move extraction logic from MainActivity to DeepLinkExtractor
    - Add `fromIntent(intent: Intent): DeepLinkData?` method
    - Keep factory methods for programmatic creation
    - _Requirements: 4.3_
  - [ ] 2.2 Write unit tests for DeepLinkExtractor
    - Test extraction with private chat intent extras
    - Test extraction with geohash chat intent extras
    - Test extraction with missing/invalid extras returns null
    - _Requirements: 1.1, 1.2_

- [ ] 3. Update DefaultRootComponent navigation config
  - [ ] 3.1 Update Config.Chat to use DeepLinkData directly
    - Change `Config.Chat(val deepLink: DeepLinkData? = null)` to use `DeepLinkData = DeepLinkData.None`
    - Update createChild to use `toChatStartupConfig()` mapping
    - Update onDeepLink and onOnboardingComplete to use new types
    - _Requirements: 1.1, 1.2, 1.3, 1.4_
  - [ ] 3.2 Write property test for deep link to navigation config preservation
    - **Property 1: Deep link to navigation config preservation**
    - **Validates: Requirements 1.1, 1.2, 5.1**

- [ ] 4. Update MainActivity to use handleDeepLink
  - [ ] 4.1 Integrate Decompose handleDeepLink extension
    - Replace manual component creation with handleDeepLink block
    - Use DeepLinkExtractor.fromIntent() for extraction
    - Pass discardSavedState = true when deep link present
    - _Requirements: 3.1, 3.2_
  - [ ] 4.2 Update onNewIntent to use DeepLinkExtractor
    - Simplify onNewIntent to use DeepLinkExtractor.fromIntent()
    - Remove extractDeepLinkFromIntent private method
    - _Requirements: 5.1, 5.3_

- [ ] 5. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 6. Cleanup and verification
  - [ ] 6.1 Remove deprecated code and verify build
    - Remove any unused imports or dead code
    - Run full build to verify no compilation errors
    - Verify deep link navigation works end-to-end
    - _Requirements: 4.1, 4.2_
