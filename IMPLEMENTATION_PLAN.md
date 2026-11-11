# Horizon Cosmetics Fabric 26.3: Investigation and Implementation Plan

## Evidence labels

- **Verified** means directly present in the supplied source or project configuration.
- **Not verified** means the supplied projects do not establish the behavior; no implementation should assume it.

## 1. Project overview

### Fabric template

**Verified:** `horizon-cosmetics-fabric-template-26.3` targets Minecraft `26.3`, Fabric Loader `0.20.1`, Fabric API `0.166.0+26.3`, Java `25`, Loom `1.19-SNAPSHOT`, and Gradle wrapper `9.8.2`. The build uses split main/client source sets, Yarn-independent current Minecraft names, and the standard Fabric main and client entrypoints. Fabric API and Loader are its only declared runtime dependencies. The source consists of example entrypoints and example mixins; there is no existing renderer, networking, settings UI, or cosmetics logic.

### Horizon

**Verified:** `horizon-main` is a Rust workspace. Its dedicated `horizon-cosmetics` crate defines and validates the item format, animation and emote pose logic, preferences, asset fetching/caching, and viewer/preview support. The game-side renderer/integration belongs to Horizon's Rust client-render code. The launcher contains a separate Wardrobe and Shop implementation; it shares the cosmetics model and API contracts but is not a reusable Fabric GUI.

### Essential patcher

**Verified:** `minecraft-essential-1.7.2` is a Java/Mixin patch for Essential, not a standalone cosmetics implementation. Its build targets Java 8 and depends on Essential classes (a local mock JAR is referenced by the POM but is not supplied in `lib`). It assumes Essential's own client, cosmetics types, network packets, entitlement manager, and outfit manager.

## 2. Horizon cosmetics architecture

**Verified:** Cosmetics are client-side visual items and do not alter Minecraft's game protocol. A JSON item describes one slot, bone attachment, texture, model-part tree/cubes, optional animations, and optional tag/emote/effect data. The Rust parser enforces format and size/range limits before drawing.

Discovered cosmetic slots/features:

- Wearables: head, back, shoulder, and torso.
- Nametag badge and line color.
- Emotes: body-bone/root animation tracks, interpolation/easing, optional props, one-shot or loop playback.
- Companions, movement trails, and auras.
- Visibility preferences and Horizon-player presence/logo support.
- Item preview/viewer code, persistent item/worn caches, and local/test loading support in Horizon.

The Horizon renderer is in Rust and is not directly reusable from the Java/Fabric template. Its item validation, parsing, pose evaluation, bone transforms, and rendering need a Java implementation or a separately engineered interop layer; no such interop exists in the template.

## 3. Horizon API and authentication

**Verified:** The official API base is `https://cosmetics.playhorizon.dev` (`horizon-addresses::COSMETICS_API`). The supplied client/docs describe HTTPS JSON requests:

| Method and path | Purpose |
|---|---|
| `POST /v1/worn` | Batch query up to 64 player UUIDs; returns TTL, worn item IDs/hashes, and Horizon-presence UUIDs. |
| `GET /v1/items/<id>?hash=<hash>` | Download an immutable-by-hash item document. |
| `POST /v1/emotes` | Query current emotes for visible Horizon players. |
| `POST /v1/auth/start` | Start service sign-in and obtain a one-time `server_id`. |
| `POST /v1/auth/finish` | Complete sign-in with the Minecraft account name after a Mojang session `join`; returns a service session and expiry. |
| `GET /v1/me` | Retrieve the authenticated account's owned cosmetics and emotes. |
| `GET /v1/catalogue` | Retrieve cosmetic metadata, including IDs, hashes, localized names/descriptions, and slots. |
| `PUT /v1/equipped` | Save an authenticated equipped-slot change. |
| `PUT /v1/visible` | Change whether the account's own cosmetics are visible to other users. |
| `POST /v1/presence` | Set Horizon-player presence. |
| `POST /v1/emote` | Start or stop the authenticated player's emote. |

**Verified authentication flow:** the service provides a one-time server ID; the client asks Mojang's session service to join that ID using the player's Minecraft session; the service then verifies it with Mojang and returns its own short-lived session. The Minecraft access token is sent to Mojang, not the cosmetics service. Authenticated API calls use the service session as a Bearer authorization value. A 401 on a kept emote session is retried once after signing in again.

**Not verified:** The backend implementation is not present in the supplied Horizon tree. The above routes and contracts are documented/client-consumed contracts, not server source verification. Account/session authentication, live service availability, payload variations, and current production behavior cannot be validated offline from the supplied source.

The launcher's separate Shop includes catalogue and checkout paths, but browser purchase/payment flows are not necessary for the in-game wardrobe and must not be emulated as local ownership.

## 4. Essential patcher architecture

**Verified:** Essential's patcher uses Mixins to intercept Essential's cosmetics-population and outfit paths. It:

- Downloads/merges a legacy cosmetic-ID list and requests those IDs from Essential.
- Rewrites Essential's `isAvailable` result to always return true.
- Synthesizes unlocked/purchased cosmetic data for received cosmetics.
- Captures cosmetic/outfit metadata and writes JSON dumps to disk.
- Preserves outfit selections/settings that Essential may drop.

These are Essential-specific types, services, packet flows, and entitlements. In particular, unconditional availability and fabricated unlock records bypass Essential's ownership/availability checks. They do not provide a legitimate way to unlock Horizon cosmetics and will not be ported. Only the general client-rendering lessons—local model rendering, asset caching, and rendering an authorized cosmetic—are relevant.

## 5. Minecraft/Fabric rendering and UI

**Verified:** The template has no rendering/UI abstractions beyond standard Fabric client entrypoints and placeholder Mixins. Horizon's renderer uses its own Rust model/pose types and a custom CPU preview renderer in the launcher; neither maps directly to Minecraft 26.3 Java rendering.

**Implementation approach:** use the Minecraft client player model and its bone `ModelPart` transforms for third-person player cosmetics; add a separate player render-layer integration only where supported by the 26.3 mappings/API. Keep network/file work away from render callbacks. Nametag drawing requires a separate name-label hook. A vanilla `OptionsScreen` entry can open a dedicated cosmetics screen; no GUI library is present. Exact 26.3 method signatures must be confirmed by compilation against Loom's resolved mappings before relying on them.

## 6. Dependencies to port or replace

- Reimplement Horizon's bounded item parser/validation and emote sampling in Java; do not import the Rust crate.
- Use Java's HTTPS/JSON facilities and the installed Minecraft/Fabric client rather than introducing a native Rust dependency.
- Use Minecraft's texture/model rendering facilities and a mod-owned cache directory.
- Keep Essential classes, its mock JAR, and its entitlement-specific Mixin hooks out of the Fabric mod.
- No added dependencies are justified by the starter project at this stage.

## 7. Compatibility issues

- Minecraft 26.3 and Java 25 are materially newer than the Essential patch's Java 8-era target. Essential Mixin targets and descriptors are not portable.
- The project uses current Minecraft names and Fabric API `26.3` artifacts; older rendering/UI examples may not compile.
- Horizon's model coordinates, UVs, bone order, animation rules, name tags, and effects need explicit Java-side mapping and tests.
- Standard Fabric rendering hooks may not expose every exact player/nameplate stage needed; any Mixin must be narrow, verified, and tested in a 26.3 client.

## 8. Direct ports vs rewrites

**Portable as behavioral contracts:** documented Horizon API routes and JSON contracts; supported slot semantics; item bounds; account proof flow; caching/backoff behavior; preference meaning; emote timing/track rules.

**Requires rewrite:** Rust serde/glam types and parser, model transforms/rendering, custom preview viewer, Rust networking/cache worker, Horizon launcher Wardrobe/Shop UI, and settings integration.

**Not transferable:** Essential's backend, entitlement model, unlocked-cosmetic mutation, and Essential wardrobe/outfit internals.

## 9. Features that may be impossible or limited

- A live authenticated flow cannot be demonstrated without a real online Minecraft account/session and an available service.
- Horizon's backend is not included; undocumented endpoints or server-side capabilities cannot be invented.
- A Fabric mod must respect the service's account ownership and server-authoritative equipment/visibility state. It must not mint ownership, sideload paid items into an owned inventory, or bypass payment/entitlement checks.
- Only cosmetics the service returns as owned/equipped, or otherwise explicitly authorizes for the current client, can be presented as account cosmetics. Client rendering is not a license to claim another user's entitlements.
- Server-side Minecraft clients do not receive Horizon cosmetics; only compatible clients that query the service can render them.
- Some Horizon features (launcher purchases, gift checkout, purchase history, and launcher-only previews) are outside the in-game client contract and may require separate, documented APIs.

## 10. Proposed architecture

Implement a client-only Fabric mod with separate packages for API/auth, validated cosmetic data, cache, renderer, emotes, nametags, preferences, and UI. Fetch visible-player cosmetics asynchronously in batches; parse and cache only validated assets. Keep authenticated account state ephemeral and never persist Minecraft or service bearer credentials. Equip/visibility changes should be sent to Horizon and reflected locally only in accordance with the service's authoritative response. Render callbacks should perform cache lookups only.

The Essential-inspired part is limited to a clean client-side renderer and cache for authorized Horizon items. No Essential patching, forced availability, fabricated unlock data, or cross-service entitlement translation is planned.

## 11. Testing strategy

1. Build with the supplied Gradle wrapper and the exact Minecraft 26.3 configuration.
2. Unit-test item bounds/format rejection, UUID/payload serialization, cache hashing, and emote timing.
3. Use a test transport for documented API responses and error/expiry cases; never use mock data as production inventory.
4. Verify the Options screen entry, navigation, loading/error/empty states, and no main-thread network work in a launched client.
5. Test rendering of authorized fixtures for all supported slots, name tags, emotes, first/third person where applicable, and other players.
6. Clearly mark live authentication/backend behavior and cross-mod rendering compatibility unverified unless tested with a real account/service and a launched game.

## 12. Known risks and limitations

- The API contract is available only through Horizon client/docs; the actual service implementation is absent.
- Minecraft 26.3 is recent and rendering/UI API differences may require narrow Mixin adaptation.
- Full feature parity is a substantial rewrite, especially the emote system, companions/trails/auras, and nametag rendering.
- API authentication depends on using the player's own valid Minecraft session through Mojang's join flow; no token should be logged, persisted, or sent to Horizon.
- Features without an authorized, documented backend response must remain unavailable rather than being fabricated.
- Essential's entitlement-bypass behavior is explicitly excluded.

## 13. Verified implementation status

Implemented in the Fabric project:

- Client-only Mojang-mediated sign-in and asynchronous calls for the documented Horizon account, catalogue, equipment, visibility, presence, emote, worn-item, and asset routes.
- An Options-screen wardrobe/emotes UI, with equip/unequip and visibility changes driven by Horizon responses.
- A disk asset cache keyed by item ID and hash, plus bounded wearer polling and retry backoff.
- A wearable parser and player render layer for head, back, shoulder, and torso slots, with swing, bob, and spin part animations. Parsing enforces format/texture/model complexity limits, strict recognized fields, and the 24-pixel rest-pose reach limit.
- A two-panel wardrobe UI with an always-available mannequin and equipped-item texture badges, plus collection controls. The mannequin intentionally avoids Minecraft's picture-in-picture entity renderer, which failed on the reported Intel graphics setup.
- Client entrypoint and renderer Mixins are registered in the client metadata/configuration.

Verification:

- `.\gradlew.bat build` completes successfully, including `compileClientJava`.
- No Java test sources are configured, so the build reports `test NO-SOURCE`.
- Minecraft 26.3 has not been launched to verify Mixin application, GUI navigation, or rendered output.
- The Horizon backend is absent from the workspace; live authentication and service response behavior remain unverified.
- The launch crash report showed an OpenGL render-target allocation with width `-748`. The previous inventory preview call passed width/height instead of the required lower-right coordinates. It has been removed in favor of a UI mannequin.

Not implemented: a fully posed 3D mannequin, nametag rendering, local emote animation playback, catalogue item try-on previews, companions, trails, and auras. The attached launch log showed `founder_tag` and `founder_backpack`; the tag is a nametag feature not yet supported, while the backpack's part animation is now supported. Essential's ownership/availability overrides remain intentionally excluded.
