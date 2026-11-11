package fuz.horizoncosmetics.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fuz.horizoncosmetics.client.api.CosmeticsApiClient;
import fuz.horizoncosmetics.client.cache.CosmeticAssetCache;
import fuz.horizoncosmetics.client.render.CosmeticItemDefinition;
import fuz.horizoncosmetics.HorizonCosmeticsFabric;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;

import java.io.IOException;
import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

public final class ClientCosmeticsManager {
	private static final ClientCosmeticsManager INSTANCE = new ClientCosmeticsManager();

	private final CosmeticsApiClient api = new CosmeticsApiClient();
	private final CosmeticsClientConfig clientConfig = new CosmeticsClientConfig();
	private final CosmeticAssetCache cache = new CosmeticAssetCache(
			FabricLoader.getInstance().getGameDir().resolve("horizon-cosmetics").resolve("assets"));
	private volatile CosmeticsApiClient.Session session;
	private volatile CosmeticsApiClient.OwnedCosmetics account;
	private volatile List<CatalogueItem> catalogue = List.of();
	private volatile boolean loading;
	private volatile boolean listed;
	private volatile String playingEmote;
	private volatile String status = "Not connected";
	private volatile String storeCurrency = "USD";
	private final Map<UUID, List<CosmeticsApiClient.WornItem>> worn = new HashMap<>();
	private final Map<UUID, Long> wornExpiry = new HashMap<>();
	private final Map<UUID, List<CosmeticItemDefinition>> resolvedWorn = new HashMap<>();
	private volatile List<CosmeticItemDefinition> previewWorn = List.of();
	private final Set<UUID> pendingWorn = new HashSet<>();
	private final Set<String> loadingAssets = new HashSet<>();
	private final Set<String> rejectedAssets = new HashSet<>();
	private final Map<String, CosmeticItemDefinition> definitions = new HashMap<>();
	private final Map<String, String> localEquipped = new HashMap<>();
	private final Path localSelectionsFile = FabricLoader.getInstance().getConfigDir()
			.resolve("horizon-cosmetics-fabric-local.json");
	private boolean wornRequestInFlight;
	private long nextWornRequestAt;
	private long retryDelayMillis = 15_000;

	private ClientCosmeticsManager() {
		loadLocalSelections();
	}

	public static ClientCosmeticsManager getInstance() {
		return INSTANCE;
	}

	public synchronized void refresh() {
		if (loading) {
			return;
		}
		loading = true;
		status = "Connecting to Horizon...";
		Minecraft minecraft = Minecraft.getInstance();
		var user = minecraft.getUser();
		CosmeticsApiClient.MinecraftAccount accountInfo;
		try {
			accountInfo = new CosmeticsApiClient.MinecraftAccount(
					user.getName(), user.getProfileId(), user.getAccessToken());
		} catch (RuntimeException exception) {
			loading = false;
			status = "Sign in with an online Minecraft account to use Horizon cosmetics.";
			return;
		}

		CompletableFuture<CosmeticsApiClient.Session> signedIn;
		CosmeticsApiClient.Session existingSession = session;
		if (existingSession != null && existingSession.expiresAtMillis() > System.currentTimeMillis()) {
			signedIn = CompletableFuture.completedFuture(existingSession);
		} else {
			signedIn = api.signIn(accountInfo);
		}

		signedIn.thenCombine(api.getCatalogue(), (signedSession, catalogueResponse) -> {
			session = signedSession;
			return new CatalogueResponse(signedSession, parseCatalogue(catalogueResponse),
					parseCurrencies(catalogueResponse));
		}).thenCompose(response -> api.getMe(response.session())
				.thenApply(me -> new AccountResponse(response.session(), response.items(), response.currencies(), me)))
				.whenComplete((response, error) -> minecraft.execute(() -> {
					loading = false;
					if (error != null) {
						status = errorMessage(error);
						return;
					}
					session = response.session();
					catalogue = response.items();
					storeCurrency = preferredCurrency(response.currencies());
					account = response.me();
					loadEquippedItems(minecraft, response.me(), response.items());
					loadLocalItems(minecraft);
					refreshResolvedWorn(response.session().profileId());
					status = "Loaded " + account.owned().size() + " owned cosmetics.";
				}));
	}

	public void tick(Minecraft minecraft) {
		synchronized (this) {
			double animationTicks = minecraft.level == null
					? System.currentTimeMillis() / 50.0 : minecraft.level.getGameTime();
			Set<CosmeticItemDefinition> animated = new HashSet<>();
			for (List<CosmeticItemDefinition> items : resolvedWorn.values()) {
				for (CosmeticItemDefinition definition : items) {
					if (animated.add(definition)) {
						definition.animate(animationTicks);
					}
				}
			}
		}
		if (minecraft.level == null) {
			synchronized (this) {
				worn.clear();
				wornExpiry.clear();
				pendingWorn.clear();
				UUID ownPlayer = session == null ? null : session.profileId();
				resolvedWorn.keySet().removeIf(playerId -> !playerId.equals(ownPlayer));
				if (ownPlayer != null) {
					refreshResolvedWorn(ownPlayer);
				}
			}
			return;
		}
		synchronized (this) {
			if (wornRequestInFlight || pendingWorn.isEmpty() || System.currentTimeMillis() < nextWornRequestAt) {
				return;
			}
			List<UUID> batch = pendingWorn.stream().limit(64).toList();
			pendingWorn.removeAll(batch);
			wornRequestInFlight = true;
			api.getWorn(batch).whenComplete((response, error) -> minecraft.execute(() -> {
				synchronized (ClientCosmeticsManager.this) {
					wornRequestInFlight = false;
					long now = System.currentTimeMillis();
					if (error != null) {
						pendingWorn.addAll(batch);
						nextWornRequestAt = now + retryDelayMillis;
						retryDelayMillis = Math.min(600_000, retryDelayMillis * 2);
						HorizonCosmeticsFabric.LOGGER.warn("Could not refresh Horizon player cosmetics: {}",
								rootCause(error).getMessage());
						return;
					}
					retryDelayMillis = 15_000;
					long ttl = Math.max(60, Math.min(3600, response.ttlSeconds()));
					for (UUID player : batch) {
						List<CosmeticsApiClient.WornItem> items = response.players().getOrDefault(player, List.of());
						worn.put(player, items);
						wornExpiry.put(player, now + ttl * 1000);
						refreshResolvedWorn(player);
						for (CosmeticsApiClient.WornItem item : items) {
							loadItem(minecraft, item);
						}
					}
					if (worn.size() > 4096) {
						worn.keySet().removeIf(uuid -> wornExpiry.getOrDefault(uuid, 0L) < now);
						wornExpiry.keySet().removeIf(uuid -> !worn.containsKey(uuid));
						resolvedWorn.keySet().removeIf(uuid -> !worn.containsKey(uuid)
								&& (session == null || !session.profileId().equals(uuid)));
					}
					nextWornRequestAt = now;
				}
			}));
		}
	}

	public synchronized void equip(String slot, String itemId) {
		if (loading || session == null || account == null) {
			status = "Refresh your Horizon wardrobe before changing equipment.";
			return;
		}
		if (itemId != null && !account.owned().contains(itemId)) {
			status = "The service does not list that cosmetic as owned.";
			return;
		}
		loading = true;
		status = "Saving your equipment...";
		Minecraft minecraft = Minecraft.getInstance();
		api.equip(session, slot, itemId).whenComplete((updated, error) -> minecraft.execute(() -> {
			loading = false;
			if (error != null) {
				status = errorMessage(error);
				return;
			}
			account = updated;
			loadEquippedItems(minecraft, updated, catalogue);
			refreshResolvedWorn(session.profileId());
			status = "Equipment saved by Horizon.";
		}));
	}

	public synchronized void equipLocally(String slot, String itemId) {
		CatalogueItem item = catalogue.stream()
				.filter(candidate -> candidate.id().equals(itemId) && candidate.slot().equals(slot))
				.findFirst().orElse(null);
		if (item == null) {
			status = "That item is not in the current Horizon catalogue.";
			return;
		}

		if (!isWearableSlot(slot)) {
			status = "Local preview is not implemented for the " + slot.replace('_', ' ') + " category.";
			return;
		}
		localEquipped.put(slot, itemId);
		saveLocalSelections();
		loadItem(Minecraft.getInstance(), new CosmeticsApiClient.WornItem(item.id(), item.hash()));
		if (session != null) {
			refreshResolvedWorn(session.profileId());
		}
		status = "Selected locally; Horizon equipment was not changed.";
	}

	public synchronized void startCheckout(String itemId) {
		if (loading || session == null || account == null) {
			status = "Refresh your Horizon wardrobe before starting checkout.";
			return;
		}
		if (isOwned(itemId)) {
			status = "You already own that Horizon cosmetic.";
			return;
		}
		CatalogueItem item = catalogue.stream().filter(candidate -> candidate.id().equals(itemId))
				.findFirst().orElse(null);
		if (item == null || !item.prices().containsKey(storeCurrency)) {
			status = "That item is not currently offered for sale in the Horizon catalogue.";
			return;
		}
		loading = true;
		status = "Starting secure Horizon checkout...";
		Minecraft minecraft = Minecraft.getInstance();
		api.startCheckout(session.name(), item.id(), storeCurrency).whenComplete((checkout, error) ->
				minecraft.execute(() -> {
					loading = false;
					if (error != null) {
						status = errorMessage(error);
						return;
					}
					URI checkoutUri;
					try {
						checkoutUri = CosmeticsApiClient.checkoutUri(checkout.transactionId());
					} catch (IllegalArgumentException exception) {
						status = exception.getMessage();
						return;
					}
					status = "Checkout created. Complete payment in your browser, then refresh your wardrobe.";
					CompletableFuture.runAsync(() -> openExternalUrl(checkoutUri))
							.whenComplete((ignored, browserError) -> {
								if (browserError != null) {
									minecraft.execute(() -> status = "Checkout is ready at " + checkoutUri
											+ " (the browser could not be opened automatically).");
								}
							});
				}));
	}

	private static void openExternalUrl(URI checkoutUri) {
		try {
			if (!Desktop.isDesktopSupported()) {
				throw new UnsupportedOperationException("Desktop browser integration is unavailable");
			}
			Desktop.getDesktop().browse(checkoutUri);
		} catch (IOException | UnsupportedOperationException | SecurityException exception) {
			throw new java.util.concurrent.CompletionException(exception);
		}
	}

	public void openFullStore() {
		URI storeUri = URI.create("https://playhorizon.dev/shop");
		Minecraft minecraft = Minecraft.getInstance();
		CompletableFuture.runAsync(() -> openExternalUrl(storeUri)).whenComplete((ignored, error) -> {
			if (error == null) {
				return;
			}
			minecraft.execute(() -> status = "Could not open Horizon's store in your browser: " + errorMessage(error));
		});
	}

	public synchronized void unequipLocally(String slot) {
		if (localEquipped.remove(slot) == null) {
			return;
		}
		saveLocalSelections();
		if (session != null) {
			refreshResolvedWorn(session.profileId());
		}
		status = "Local selection removed; Horizon equipment was not changed.";
	}

	public synchronized void setVisible(boolean visible) {
		if (loading || session == null || account == null) {
			status = "Refresh your Horizon wardrobe before changing visibility.";
			return;
		}
		loading = true;
		status = "Saving visibility...";
		Minecraft minecraft = Minecraft.getInstance();
		api.setVisible(session, visible).whenComplete((updated, error) -> minecraft.execute(() -> {
			loading = false;
			if (error != null) {
				status = errorMessage(error);
				return;
			}
			account = updated;
			status = "Visibility saved by Horizon.";
		}));
	}

	public synchronized void setListed(boolean value) {
		if (loading || session == null) {
			status = "Refresh your Horizon wardrobe before changing presence.";
			return;
		}
		loading = true;
		status = "Saving Horizon presence...";
		Minecraft minecraft = Minecraft.getInstance();
		api.setPresence(session, value).whenComplete((response, error) -> minecraft.execute(() -> {
			loading = false;
			if (error != null) {
				status = errorMessage(error);
				return;
			}
			JsonElement result = response.get("listed");
			if (result == null || !result.isJsonPrimitive() || !result.getAsJsonPrimitive().isBoolean()) {
				status = "Horizon returned an invalid presence response.";
				return;
			}
			listed = result.getAsBoolean();
			status = "Horizon presence updated.";
		}));
	}

	public synchronized void playEmote(String emoteId) {
		if (loading || session == null || account == null) {
			status = "Refresh your Horizon wardrobe before playing an emote.";
			return;
		}
		if (!listed) {
			status = "Enable Horizon Presence before playing an emote.";
			return;
		}
		if (emoteId != null && account.emotes().stream().noneMatch(emote -> emote.id().equals(emoteId))) {
			status = "The service does not list that emote as owned.";
			return;
		}
		String requestedEmote = emoteId != null && emoteId.equals(playingEmote) ? null : emoteId;
		startEmoteWithPresence(requestedEmote, "Starting emote...");
	}

	public synchronized void enablePresenceAndPlayEmote(String emoteId) {
		if (loading || session == null || account == null) {
			status = "Refresh your Horizon wardrobe before playing an emote.";
			return;
		}
		if (emoteId != null && account.emotes().stream().noneMatch(emote -> emote.id().equals(emoteId))) {
			status = "The service does not list that emote as owned.";
			return;
		}
		String requestedEmote = emoteId != null && emoteId.equals(playingEmote) ? null : emoteId;
		startEmoteWithPresence(requestedEmote, "Enabling Horizon Presence and starting emote...");
	}

	private void startEmoteWithPresence(String emoteId, String progress) {
		loading = true;
		status = progress;
		Minecraft minecraft = Minecraft.getInstance();
		AtomicBoolean presenceEnabled = new AtomicBoolean();
		api.setPresence(session, true)
				.thenCompose(response -> {
					JsonElement result = response.get("listed");
					if (result == null || !result.isJsonPrimitive() || !result.getAsJsonPrimitive().isBoolean()
							|| !result.getAsBoolean()) {
						return CompletableFuture.failedFuture(
								new CosmeticsApiClient.ApiException("Horizon did not confirm Presence was enabled."));
					}
					presenceEnabled.set(true);
					return api.playEmote(session, emoteId);
				})
				.whenComplete((response, error) -> minecraft.execute(() -> {
					loading = false;
					if (presenceEnabled.get()) {
						listed = true;
					}
					if (error != null) {
						status = errorMessage(error);
						return;
					}
					playingEmote = emoteId;
					status = emoteId == null
							? "Horizon Presence enabled; emote stopped."
							: "Horizon Presence enabled; emote sent.";
				}));
	}

	public CompletableFuture<byte[]> getItem(String itemId) {
		CatalogueItem item = catalogue.stream().filter(candidate -> candidate.id().equals(itemId)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Horizon has not provided metadata for this item"));
		return cache.get(item.id(), item.hash(), () -> api.getItem(item.id(), item.hash()));
	}

	public synchronized List<CosmeticItemDefinition> wornCosmetics(UUID playerId) {
		UUID ownPlayer = Minecraft.getInstance().getUser().getProfileId();
		if (!playerId.equals(ownPlayer) && !clientConfig.showOtherWearables(ownsCosmetics())) {
			return List.of();
		}
		long now = System.currentTimeMillis();
		if (wornExpiry.getOrDefault(playerId, 0L) <= now) {
			pendingWorn.add(playerId);
		}
		if (!resolvedWorn.containsKey(playerId)) {
			refreshResolvedWorn(playerId);
		}
		return resolvedWorn.getOrDefault(playerId, List.of());
	}

	public synchronized List<CosmeticItemDefinition> equippedCosmetics() {
		return previewWorn;
	}

	private synchronized void refreshResolvedWorn(UUID playerId) {
		Map<String, CosmeticItemDefinition> bySlot = new java.util.LinkedHashMap<>();
		for (CosmeticsApiClient.WornItem item : worn.getOrDefault(playerId, List.of())) {
			CosmeticItemDefinition definition = definitions.get(assetKey(item.id(), item.hash()));
			if (definition != null) {
				bySlot.put(definition.slot(), definition);
			}
		}
		if (session != null && session.profileId().equals(playerId) && account != null) {
			for (String itemId : account.equipped().values()) {
				catalogue.stream().filter(item -> item.id().equals(itemId)).findFirst()
						.map(item -> definitions.get(assetKey(item.id(), item.hash())))
						.filter(java.util.Objects::nonNull)
						.ifPresent(item -> bySlot.put(item.slot(), item));
			}
			for (Map.Entry<String, String> selection : localEquipped.entrySet()) {
				catalogue.stream().filter(item -> item.id().equals(selection.getValue())
								&& item.slot().equals(selection.getKey()))
						.findFirst()
						.map(item -> definitions.get(assetKey(item.id(), item.hash())))
						.filter(java.util.Objects::nonNull)
						.ifPresent(item -> bySlot.put(selection.getKey(), item));
			}
		}
		List<CosmeticItemDefinition> resolved = List.copyOf(bySlot.values());
		resolvedWorn.put(playerId, resolved);
		if (playerId.equals(Minecraft.getInstance().getUser().getProfileId())) {
			previewWorn = resolved;
		}
	}

	private void loadEquippedItems(Minecraft minecraft, CosmeticsApiClient.OwnedCosmetics owned,
								   List<CatalogueItem> items) {
		for (String itemId : owned.equipped().values()) {
			items.stream().filter(item -> item.id().equals(itemId)).findFirst().ifPresent(item ->
					loadItem(minecraft, new CosmeticsApiClient.WornItem(item.id(), item.hash())));
		}
	}

	private void loadLocalItems(Minecraft minecraft) {
		synchronized (this) {
			for (Map.Entry<String, String> selection : localEquipped.entrySet()) {
				catalogue.stream().filter(item -> item.id().equals(selection.getValue())
								&& item.slot().equals(selection.getKey()))
						.findFirst().ifPresent(item ->
								loadItem(minecraft, new CosmeticsApiClient.WornItem(item.id(), item.hash())));
			}
		}
	}

	private void loadItem(Minecraft minecraft, CosmeticsApiClient.WornItem item) {
		String key = assetKey(item.id(), item.hash());
		if (definitions.containsKey(key) || rejectedAssets.contains(key) || !loadingAssets.add(key)) {
			return;
		}
		cache.get(item.id(), item.hash(), () -> api.getItem(item.id(), item.hash()))
				.thenApply(bytes -> CosmeticItemDefinition.parse(bytes, item.id(), key))
				.whenComplete((definition, error) -> minecraft.execute(() -> {
					synchronized (ClientCosmeticsManager.this) {
						loadingAssets.remove(key);
						if (error != null) {
							rejectedAssets.add(key);
							HorizonCosmeticsFabric.LOGGER.warn("Could not load Horizon cosmetic {}: {}",
									item.id(), rootCause(error).getMessage());
							return;
						}
						try {
							TextureManager textures = minecraft.getTextureManager();
							definition.registerTexture(textures);
							definitions.put(key, definition);
							for (UUID playerId : worn.keySet()) {
								refreshResolvedWorn(playerId);
							}
							if (session != null) {
								refreshResolvedWorn(session.profileId());
							}
						} catch (RuntimeException exception) {
							HorizonCosmeticsFabric.LOGGER.warn("Could not register Horizon cosmetic {}",
									item.id(), exception);
						}
					}
				}));
	}

	public synchronized void requestWorn(UUID playerId) {
		if (wornExpiry.getOrDefault(playerId, 0L) <= System.currentTimeMillis()) {
			pendingWorn.add(playerId);
		}
	}

	public boolean isLoading() {
		return loading;
	}

	public boolean isListed() {
		return listed;
	}

	public String playingEmote() {
		return playingEmote;
	}

	public List<CosmeticsApiClient.OwnedEmote> ownedEmotes() {
		CosmeticsApiClient.OwnedCosmetics current = account;
		return current == null ? List.of() : current.emotes();
	}

	public String status() {
		return status;
	}

	public CosmeticsApiClient.OwnedCosmetics account() {
		return account;
	}

	public List<CatalogueItem> ownedItems() {
		CosmeticsApiClient.OwnedCosmetics current = account;
		if (current == null) {
			return List.of();
		}
		return catalogue.stream().filter(item -> current.owned().contains(item.id())).toList();
	}

	public List<CatalogueItem> catalogueItems() {
		return catalogue;
	}

	public String storeCurrency() {
		return storeCurrency;
	}

	public boolean showOtherWearables() {
		return clientConfig.showOtherWearables(ownsCosmetics());
	}

	public void setShowOtherWearables(boolean value) {
		clientConfig.setShowOtherWearables(value);
	}

	private boolean ownsCosmetics() {
		CosmeticsApiClient.OwnedCosmetics current = account;
		return current != null && !current.owned().isEmpty();
	}

	public synchronized Map<String, String> localEquipped() {
		return Map.copyOf(localEquipped);
	}

	public boolean isOwned(String itemId) {
		CosmeticsApiClient.OwnedCosmetics current = account;
		return current != null && current.owned().contains(itemId);
	}

	public static boolean supportsLocalPreview(String slot) {
		return isWearableSlot(slot);
	}

	public record CatalogueItem(String id, String hash, String slot, String name, String description,
								Map<String, Long> prices) {
		public CatalogueItem {
			prices = Map.copyOf(prices);
		}
	}

	private record CatalogueResponse(CosmeticsApiClient.Session session, List<CatalogueItem> items,
									 List<String> currencies) {
	}

	private record AccountResponse(CosmeticsApiClient.Session session, List<CatalogueItem> items,
								   List<String> currencies,
								   CosmeticsApiClient.OwnedCosmetics me) {
	}

	private static List<CatalogueItem> parseCatalogue(JsonObject response) {
		List<CatalogueItem> entries = new ArrayList<>();
		JsonElement itemsValue = response.get("items");
		if (itemsValue == null || !itemsValue.isJsonArray()) {
			throw new CosmeticsApiClient.ApiException("Horizon catalogue response has no items list");
		}
		for (JsonElement element : itemsValue.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject item = element.getAsJsonObject();
			if (!item.has("id") || !item.has("hash") || !item.has("slot")) {
				continue;
			}
			String id = item.get("id").getAsString();
			String hash = item.get("hash").getAsString();
			String slot = item.get("slot").getAsString();
			String name = localized(item.get("names"), id);
			String description = localized(item.get("lines"), "");
			Map<String, Long> prices = new HashMap<>();
			JsonElement pricesValue = item.get("prices");
			if (pricesValue != null && pricesValue.isJsonObject()) {
				for (Map.Entry<String, JsonElement> price : pricesValue.getAsJsonObject().entrySet()) {
					if (price.getValue().isJsonPrimitive() && price.getValue().getAsJsonPrimitive().isNumber()) {
						long cents = price.getValue().getAsLong();
						if (cents >= 0 && price.getKey().matches("[A-Z]{3}")) {
							prices.put(price.getKey(), cents);
						}
					}
				}
			}
			if (id.matches("[A-Za-z0-9_-]{1,128}") && hash.matches("[A-Za-z0-9_-]{1,256}")) {
				entries.add(new CatalogueItem(id, hash, slot, name, description, prices));
			}
		}
		return List.copyOf(entries);
	}

	private static List<String> parseCurrencies(JsonObject response) {
		JsonElement currencies = response.get("currencies");
		if (currencies == null || !currencies.isJsonArray()) {
			return List.of();
		}
		return currencies.getAsJsonArray().asList().stream()
				.filter(element -> element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
				.map(JsonElement::getAsString)
				.filter(currency -> currency.matches("[A-Z]{3}"))
				.distinct()
				.toList();
	}

	private static String preferredCurrency(List<String> currencies) {
		if (currencies.isEmpty()) {
			return "USD";
		}
		String country = Locale.getDefault().getCountry();
		String preferred = switch (country) {
			case "CH", "LI" -> "CHF";
			case "GB" -> "GBP";
			case "AT", "BE", "CY", "DE", "EE", "ES", "FI", "FR", "GR", "HR", "IE", "IT", "LT",
					"LU", "LV", "MT", "NL", "PT", "SI", "SK", "AD", "MC", "SM", "VA", "ME", "XK" -> "EUR";
			default -> "USD";
		};
		return currencies.contains(preferred) ? preferred
				: currencies.contains("USD") ? "USD" : currencies.getFirst();
	}
	private static String localized(JsonElement names, String fallback) {
		if (names == null || !names.isJsonObject()) {
			return fallback;
		}
		JsonObject values = names.getAsJsonObject();
		JsonElement english = values.get("en_us");
		if (english != null && english.isJsonPrimitive()) {
			return english.getAsString();
		}
		for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
			if (entry.getValue().isJsonPrimitive()) {
				return entry.getValue().getAsString();
			}
		}
		return fallback;
	}

	private static String errorMessage(Throwable throwable) {
		Throwable cause = rootCause(throwable);
		String message = cause.getMessage();
		return message == null || message.isBlank() ? "Horizon request failed." : message;
	}

	private static Throwable rootCause(Throwable throwable) {
		Throwable cause = throwable;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause;
	}

	private static String assetKey(String id, String hash) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest((id + "@" + hash).getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private void loadLocalSelections() {
		if (!Files.exists(localSelectionsFile)) {
			return;
		}
		try {
			JsonElement root = com.google.gson.JsonParser.parseString(
					Files.readString(localSelectionsFile, StandardCharsets.UTF_8));
			if (!root.isJsonObject()) {
				throw new IOException("Local selections must be a JSON object");
			}
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
				if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
					localEquipped.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
		} catch (IOException | RuntimeException exception) {
			HorizonCosmeticsFabric.LOGGER.warn("Could not read local Horizon cosmetic selections from {}",
					localSelectionsFile, exception);
		}
	}

	private synchronized void saveLocalSelections() {
		try {
			Files.createDirectories(localSelectionsFile.getParent());
			JsonObject selections = new JsonObject();
			localEquipped.forEach(selections::addProperty);
			Files.writeString(localSelectionsFile, selections.toString(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			HorizonCosmeticsFabric.LOGGER.warn("Could not save local Horizon cosmetic selections to {}",
					localSelectionsFile, exception);
			status = "Local selection changed, but could not be saved to disk.";
		}
	}

	private static boolean isWearableSlot(String slot) {
		return slot.equals("head") || slot.equals("back") || slot.equals("shoulder") || slot.equals("torso");
	}
}
