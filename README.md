# Horizon Cosmetics Fabric

A client-only Fabric 26.3 integration for Horizon's cosmetics service. The mod uses the service's documented HTTPS API and its Mojang-mediated account verification flow; it does not create local ownership or grant cosmetics.

## Current functionality

- Opens a cosmetics screen from Minecraft's Options/Settings screen.
- Authenticates the currently signed-in Minecraft account with Horizon by asking Mojang's session server to verify a one-time server ID.
- Reads the account's authoritative owned/equipped state and the public catalogue.
- Separates owned cosmetics, for-sale unowned catalogue items, emotes, and settings. Catalogue cards show Horizon-provided names, descriptions, categories, ownership/equipped state, and prices where supplied.
- Starts real Horizon checkouts through the documented `POST /v1/web/checkout` flow and opens Horizon's validated HTTPS payment page in the browser. Payment is completed by Horizon's web checkout; the mod never accepts payment details or treats checkout creation as a completed purchase. Refresh the wardrobe after payment to load updated ownership.
- Distinguishes persistent local wearable preview selections from equip/unequip changes confirmed by Horizon. Local selections affect only this client; server equipment still requires ownership and a successful service response.
- Submits visibility changes to Horizon and displays service errors.
- Submits Horizon presence and owned-emote play/stop requests. Playing an emote requires Horizon Presence; if it is off, the UI asks before enabling it because that makes the account discoverable to Horizon players for up to three hours. Presence is refreshed before the emote request to avoid the service's HTTP 409 response when presence has expired. These requests do not animate the local player model.
- Fetches item JSON asynchronously and caches it by item ID/hash.
- Renders textured head, back, shoulder, and torso models for players returned by Horizon's worn-items API, including Horizon's swing, bob, and spin part animations.
- Shows the signed-in player's skin on an always-available mannequin, with the same wearable model parts and textures used by the in-world renderer. Minecraft's skin manager resolves the account profile without requiring a world or server.
- Provides a persistent local setting to hide other players' wearable cosmetics. By default, they are shown when the signed-in account owns cosmetics, matching Horizon's documented default.
- Registers the configurable emote wheel key in Minecraft's Controls menu (default B).
- Validates wearable geometry, texture bounds, complexity, and the documented 24-pixel reach limit before registering textures.
- Limits wearer queries to the API's documented batch size (64).

## Limitations

Name-tag styles, local/remote emote animation playback, emote icons/previews, companion models, trails, auras, and other non-wearable effects are not implemented. Unsupported cosmetic slots/features are rejected by the wearable parser rather than silently treated as rendered. The emotes tab sends play/stop requests to Horizon but does not animate the local or remote model.

The in-game Store currently supports individual catalogue items with a Horizon-supplied price. Sets, collection bundles, gifts, the Supporter badge's variable amount, and founder-set claiming remain available through Horizon's full website shop. The mod does not display catalogue images because the cosmetics API catalogue does not provide icon URLs; wearable items can be selected for local use, while purchase and payment details remain on Horizon's checkout page.

The settings screen currently exposes only the local wearable visibility preference. The mod does not add Horizon controls for name tags, trails, auras, or other unsupported effects.

The mannequin previews wearable accessories on the skin model but does not preview emote poses or non-wearable effects. These changes have compiled but have not yet been relaunched in Minecraft 26.3. Horizon's checkout, payment completion, and live service behavior have not been tested here.

Only items the authenticated service identifies as owned can be equipped through Horizon. Local wearable previews use catalogue items and are saved in `config/horizon-cosmetics-fabric-local.json`; they do not grant ownership or change the server-confirmed wardrobe. The Essential patcher's ownership/availability overrides are intentionally not included: they belong to a separate service and entitlement system and would bypass ownership checks.

Live authentication and service operations require an online Minecraft account and an available Horizon cosmetics service.

## Installation

1. Install Fabric Loader for Minecraft 26.3 and a matching Fabric API.
2. Put the built `horizon-cosmetics-fabric` JAR in the instance's `mods` folder.
3. Open Minecraft Options/Settings and select **Cosmetics**.
4. Configure the emote wheel key in **Options > Controls > Key Binds** (default **B**).
5. An online Minecraft account is required to load and change the Horizon wardrobe.

## Configuration and credentials

The service session is held in memory and expires; the Minecraft access token is sent only to Mojang's session join endpoint and is not written to disk or sent to Horizon. Item JSON responses are cached under the game directory in `horizon-cosmetics/assets`. Local wearable selections and the local visibility preference are saved separately in the Minecraft config directory.

## Build

Use the included Gradle wrapper with Java 25:

```powershell
.\gradlew.bat build
```

The remapped mod JAR is produced under `build\libs`.

## References

- [Horizon cosmetics format and API documentation](../horizon-main/docs/cosmetics.md)
- [Horizon source project](../horizon-main)
- [Implementation investigation](IMPLEMENTATION_PLAN.md)
