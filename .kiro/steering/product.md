# BitChat Android

BitChat is a secure, decentralized, peer-to-peer messaging app for Android that works over Bluetooth mesh networks.

## Core Capabilities
- Bluetooth LE mesh networking (no internet required for mesh chats)
- End-to-end encryption (X25519 + AES-256-GCM)
- Channel-based group messaging with optional password protection
- Geohash location channels (requires internet)
- Store-and-forward for offline message delivery
- Cross-platform compatibility with iOS BitChat

## Key Principles
- Privacy first: No accounts, phone numbers, or persistent identifiers
- Decentralized: No servers, peer-to-peer only
- Ephemeral by default: Messages exist only in device memory
- Security warning: Not yet externally audited - not for sensitive use cases

## User Features
- IRC-style commands (`/join`, `/msg`, `/who`, etc.)
- Password-protected channels
- Message retention (owner-controlled)
- Emergency wipe (triple-tap logo)
- Dark/light terminal-inspired themes
- Bundled Tor support for enhanced privacy
