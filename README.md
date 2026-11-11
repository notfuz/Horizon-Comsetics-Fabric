# Horizon Cosmetics Fabric

A client-side Fabric 26.3 mod that brings Horizon's cosmetics experience directly into Minecraft.

Browse your wardrobe, preview cosmetics on a 3D player mannequin, manage equipped items, explore the store, and interact with Horizon's cosmetics service without launching the original Horizon client.

## Features

- **Cosmetics Menu** - Access Horizon cosmetics directly from Minecraft's Options menu.
- **Account Authentication** - Authenticate through Mojang's session verification system.
- **Cosmetic Wardrobe** - Browse owned cosmetics and the public catalogue, with ownership and equipped status.
- **3D Mannequin** - Preview wearable cosmetics on your Minecraft skin without joining a server.
- **Cosmetic Rendering** - Render supported head, back, shoulder, and torso accessories on players.
- **Local Previews** - Preview and save wearable cosmetic selections independently of your server-confirmed wardrobe.
- **Horizon Store** - Browse available cosmetics and start purchases through Horizon's official checkout.
- **Emotes** - Browse owned emotes and send play/stop requests through Horizon Presence.
- **Player Visibility** - Control whether other players' wearable cosmetics are displayed.
- **Custom Keybind** - Open the emote wheel using a configurable keybind, defaulting to `B`.
- **Asset Caching** - Cache cosmetic data and textures to reduce unnecessary downloads.
- **Settings** - Configure local cosmetic visibility preferences.

## Screenshots

*Coming soon.*

## Compatibility

| Requirement | Version |
|---|---|
| Minecraft | 26.3 |
| Mod Loader | Fabric |
| Fabric API | Matching 26.3 version |
| Java | 25 |

## Installation

1. Install Fabric Loader for Minecraft 26.3.
2. Install the matching Fabric API.
3. Download the latest mod JAR from Releases.
4. Place the JAR inside your Minecraft `mods` folder.
5. Launch Minecraft.
6. Open **Options > Cosmetics**.

An online Minecraft account and an available Horizon cosmetics service are required for authentication and live cosmetic operations.

## Limitations

Some Horizon cosmetic features are not yet implemented.

Currently unsupported:

- Nametag cosmetics.
- Local and remote emote animation playback.
- Emote icons and animated previews.
- Companions.
- Trails.
- Auras and other non-wearable effects.

The emote interface currently sends play/stop requests to Horizon but does not animate player models.

The in-game store supports individual catalogue items with Horizon-provided prices. Bundles, gifts, collections, variable-price Supporter badges, and Founder Set claims remain available through Horizon's website.

Cosmetic catalogue images are not currently displayed because the catalogue API does not provide icon URLs.

**Important:** Local cosmetic previews do not grant ownership. Equipping cosmetics through Horizon requires the authenticated account to own the item and receive a successful response from the service.

Some recent changes have compiled but have not yet been verified in a live Minecraft 26.3 session. Checkout and payment completion also require live-service testing.

## Configuration

Local settings and cosmetic selections are stored separately from Horizon's server-confirmed wardrobe.

| Location | Purpose |
|---|---|
| `config/horizon-cosmetics-fabric-local.json` | Local cosmetic selections and visibility preferences |
| `horizon-cosmetics/assets` | Cached cosmetic item data |
| `Options > Controls > Key Binds` | Configure the emote wheel key |

Authentication sessions are held in memory and expire. Minecraft access tokens are sent to Mojang's session verification endpoint, not stored on disk or sent to Horizon.

## Building

Requires Java 25.

Clone the repository and run:

```powershell
.\gradlew.bat build
```

The compiled mod JAR will be available in:

```text
build/libs/
```

## References

- [Horizon Cosmetics API Documentation](../horizon-main/docs/cosmetics.md)
- [Horizon Source Code](../horizon-main)
- [Implementation Plan](IMPLEMENTATION_PLAN.md)