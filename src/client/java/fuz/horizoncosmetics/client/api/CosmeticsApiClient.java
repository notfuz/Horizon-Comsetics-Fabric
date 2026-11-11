package fuz.horizoncosmetics.client.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class CosmeticsApiClient {
	private static final String API_BASE = "https://cosmetics.playhorizon.dev";
	private static final URI MOJANG_JOIN = URI.create("https://sessionserver.mojang.com/session/minecraft/join");
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	private static final int MAX_RESPONSE_BYTES = 1_048_576;

	public CosmeticsApiClient() {
	}

	public record MinecraftAccount(String name, UUID profileId, String accessToken) {
		public MinecraftAccount {
			if (name == null || name.isBlank() || profileId == null || accessToken == null || accessToken.isBlank()) {
				throw new IllegalArgumentException("A signed-in Minecraft account is required");
			}
		}

		@Override
		public String toString() {
			return "MinecraftAccount[name=" + name + ", profileId=" + profileId + ", accessToken=<redacted>]";
		}
	}

	public record Session(String token, UUID profileId, String name, long expiresAtMillis) {
		public Session {
			if (token == null || token.isBlank() || profileId == null || name == null || name.isBlank()) {
				throw new IllegalArgumentException("Invalid Horizon session response");
			}
		}

		@Override
		public String toString() {
			return "Session[profileId=" + profileId + ", name=" + name + ", expiresAtMillis=" + expiresAtMillis
					+ ", token=<redacted>]";
		}
	}

	public record OwnedEmote(String id, String hash, Map<String, String> names) {
		public OwnedEmote {
			names = Map.copyOf(names);
		}
	}

	public record OwnedCosmetics(List<String> owned, Map<String, String> equipped, boolean visible,
								 List<OwnedEmote> emotes) {
		public OwnedCosmetics {
			owned = List.copyOf(owned);
			equipped = Map.copyOf(equipped);
			emotes = List.copyOf(emotes);
		}
	}

	public record WornItem(String id, String hash) {
	}

	public record Checkout(String transactionId) {
	}

	public record WornResponse(long ttlSeconds, Map<UUID, List<WornItem>> players, List<UUID> horizonPlayers) {
		public WornResponse {
			players = Map.copyOf(players);
			horizonPlayers = List.copyOf(horizonPlayers);
		}
	}

	public CompletableFuture<Session> signIn(MinecraftAccount account) {
		return request("POST", "/v1/auth/start", null, new JsonObject())
				.thenCompose(start -> {
					String serverId = requiredString(start, "server_id");
					return joinMojang(account, serverId)
							.thenCompose(ignored -> {
								JsonObject body = new JsonObject();
								body.addProperty("server_id", serverId);
								body.addProperty("name", account.name());
								return request("POST", "/v1/auth/finish", null, body);
							})
							.thenApply(finish -> {
								String token = requiredString(finish, "session");
								String name = finish.has("name") && finish.get("name").isJsonPrimitive()
										? finish.get("name").getAsString() : account.name();
								UUID profileId = finish.has("uuid") && finish.get("uuid").isJsonPrimitive()
										? parseUuid(finish.get("uuid").getAsString()) : account.profileId();
								long expiresIn = finish.has("expires_in") && finish.get("expires_in").isJsonPrimitive()
										? Math.max(0, finish.get("expires_in").getAsLong()) : 3600;
								long safeTtl = Math.max(0, expiresIn - 60);
								return new Session(token, profileId, name,
										System.currentTimeMillis() + safeTtl * 1000);
							});
				});
	}

	public CompletableFuture<OwnedCosmetics> getMe(Session session) {
		return request("GET", "/v1/me", session, null).thenApply(this::parseMe);
	}

	public CompletableFuture<JsonObject> getCatalogue() {
		return request("GET", "/v1/catalogue", null, null);
	}

	public CompletableFuture<Checkout> startCheckout(String playerName, String itemId, String currency) {
		if (playerName == null || playerName.isBlank()
				|| itemId == null || !itemId.matches("[A-Za-z0-9_-]{1,128}")
				|| currency == null || !currency.matches("[A-Z]{3}")) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid Horizon checkout details"));
		}
		JsonObject body = new JsonObject();
		body.addProperty("name", playerName);
		JsonArray items = new JsonArray();
		items.add(itemId);
		body.add("items", items);
		body.addProperty("currency", currency);
		return request("POST", "/v1/web/checkout", null, body)
				.thenApply(json -> new Checkout(requiredString(json, "transaction_id")));
	}

	public static URI checkoutUri(String transactionId) {
		if (transactionId == null || !transactionId.matches("txn_[A-Za-z0-9]{1,64}")) {
			throw new IllegalArgumentException("Horizon returned an invalid checkout transaction ID");
		}
		return URI.create("https://playhorizon.dev/shop/pay?_ptxn="
				+ URLEncoder.encode(transactionId, StandardCharsets.UTF_8));
	}

	public CompletableFuture<OwnedCosmetics> equip(Session session, String slot, String itemId) {
		JsonObject body = new JsonObject();
		body.addProperty("slot", slot);
		if (itemId == null) {
			body.add("item", com.google.gson.JsonNull.INSTANCE);
		} else {
			body.addProperty("item", itemId);
		}
		return request("PUT", "/v1/equipped", session, body).thenApply(this::parseMe);
	}

	public CompletableFuture<OwnedCosmetics> setVisible(Session session, boolean visible) {
		JsonObject body = new JsonObject();
		body.addProperty("visible", visible);
		return request("PUT", "/v1/visible", session, body).thenApply(this::parseMe);
	}

	public CompletableFuture<WornResponse> getWorn(List<UUID> players) {
		if (players.size() > 64) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("At most 64 players per request"));
		}
		JsonObject body = new JsonObject();
		JsonArray ids = new JsonArray();
		for (UUID player : players) {
			ids.add(player.toString().replace("-", ""));
		}
		body.add("players", ids);
		return request("POST", "/v1/worn", null, body).thenApply(json -> {
			Map<UUID, List<WornItem>> result = new java.util.LinkedHashMap<>();
			JsonObject playerItems = json.has("players") && json.get("players").isJsonObject()
					? json.getAsJsonObject("players") : new JsonObject();
			for (Map.Entry<String, JsonElement> entry : playerItems.entrySet()) {
				UUID uuid = parseUuid(entry.getKey());
				if (!entry.getValue().isJsonArray()) {
					continue;
				}
				List<WornItem> items = new ArrayList<>();
				for (JsonElement item : entry.getValue().getAsJsonArray()) {
					if (!item.isJsonObject()) {
						continue;
					}
					JsonObject object = item.getAsJsonObject();
					if (object.has("id") && object.has("hash")) {
						items.add(new WornItem(requiredString(object, "id"), requiredString(object, "hash")));
					}
				}
				result.put(uuid, List.copyOf(items));
			}
			List<UUID> horizonPlayers = new ArrayList<>();
			JsonElement horizonValue = json.get("horizon");
			if (horizonValue != null && horizonValue.isJsonArray()) {
				for (JsonElement entry : horizonValue.getAsJsonArray()) {
					if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
						horizonPlayers.add(parseUuid(entry.getAsString()));
					}
				}
			}
			long ttl = json.has("ttl") && json.get("ttl").isJsonPrimitive()
					? Math.max(0, json.get("ttl").getAsLong()) : 0;
			return new WornResponse(ttl, result, horizonPlayers);
		});
	}

	public CompletableFuture<JsonObject> getEmotes(List<UUID> players) {
		if (players.size() > 64) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("At most 64 players per request"));
		}
		JsonObject body = new JsonObject();
		JsonArray ids = new JsonArray();
		for (UUID player : players) {
			ids.add(player.toString().replace("-", ""));
		}
		body.add("players", ids);
		return request("POST", "/v1/emotes", null, body);
	}

	public CompletableFuture<JsonObject> setPresence(Session session, boolean listed) {
		JsonObject body = new JsonObject();
		body.addProperty("listed", listed);
		return request("POST", "/v1/presence", session, body);
	}

	public CompletableFuture<JsonObject> playEmote(Session session, String emoteId) {
		JsonObject body = new JsonObject();
		if (emoteId == null) {
			body.add("emote", com.google.gson.JsonNull.INSTANCE);
		} else {
			body.addProperty("emote", emoteId);
		}
		return request("POST", "/v1/emote", session, body);
	}

	public CompletableFuture<byte[]> getItem(String id, String hash) {
		if (!id.matches("[A-Za-z0-9_-]{1,128}") || !hash.matches("[A-Za-z0-9_-]{1,256}")) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid item identifier or hash"));
		}
		String path = "/v1/items/" + id + "?hash=" + hash;
		return send(HttpRequest.newBuilder(URI.create(API_BASE + path))
				.timeout(Duration.ofSeconds(20))
				.GET()
				.build()).thenApply(response -> requireSuccess(response, path));
	}

	private OwnedCosmetics parseMe(JsonObject json) {
		List<String> owned = new ArrayList<>();
		JsonElement ownedValue = json.get("owned");
		if (ownedValue != null && ownedValue.isJsonArray()) {
			for (JsonElement item : ownedValue.getAsJsonArray()) {
				if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
					owned.add(item.getAsString());
				}
			}
		}
		Map<String, String> equipped = new java.util.LinkedHashMap<>();
		if (json.has("equipped") && json.get("equipped").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("equipped").entrySet()) {
				if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
					equipped.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
		}
		boolean visible = !json.has("visible") || json.get("visible").getAsBoolean();
		List<OwnedEmote> emotes = new ArrayList<>();
		JsonElement emotesValue = json.get("emotes");
		if (emotesValue != null && emotesValue.isJsonArray()) {
			for (JsonElement element : emotesValue.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject emote = element.getAsJsonObject();
				if (!emote.has("id") || !emote.has("hash")) {
					continue;
				}
				Map<String, String> names = new java.util.LinkedHashMap<>();
				if (emote.has("names") && emote.get("names").isJsonObject()) {
					for (Map.Entry<String, JsonElement> entry : emote.getAsJsonObject("names").entrySet()) {
						if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
							names.put(entry.getKey(), entry.getValue().getAsString());
						}
					}
				}
				emotes.add(new OwnedEmote(requiredString(emote, "id"), requiredString(emote, "hash"), names));
			}
		}
		return new OwnedCosmetics(owned, equipped, visible, emotes);
	}

	private CompletableFuture<Void> joinMojang(MinecraftAccount account, String serverId) {
		JsonObject body = new JsonObject();
		body.addProperty("accessToken", account.accessToken());
		body.addProperty("selectedProfile", account.profileId().toString().replace("-", ""));
		body.addProperty("serverId", serverId);
		HttpRequest request = HttpRequest.newBuilder(MOJANG_JOIN)
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		return send(request).thenApply(response -> {
			if (response.statusCode() != 204) {
				throw new ApiException("Mojang session join failed (HTTP " + response.statusCode() + ")");
			}
			return null;
		});
	}

	private CompletableFuture<JsonObject> request(String method, String path, Session session, JsonObject body) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(API_BASE + path))
				.timeout(Duration.ofSeconds(20))
				.header("Accept", "application/json")
				.header("Content-Type", "application/json");
		if (session != null) {
			if (session.expiresAtMillis() <= System.currentTimeMillis()) {
				return CompletableFuture.failedFuture(new ApiException("Horizon session expired; sign in again"));
			}
			builder.header("Authorization", "Bearer " + session.token());
		}
		HttpRequest.BodyPublisher publisher = body == null
				? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
		builder.method(method, publisher);
		return send(builder.build()).thenApply(response -> {
			byte[] bytes = requireSuccess(response, path);
			try {
				JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
				if (!parsed.isJsonObject()) {
					throw new ApiException("Invalid JSON response from " + path);
				}
				return parsed.getAsJsonObject();
			} catch (RuntimeException exception) {
				if (exception instanceof ApiException apiException) {
					throw apiException;
				}
				throw new ApiException("Invalid JSON response from " + path, exception);
			}
		});
	}

	private static CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request) {
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
				.thenApply(response -> {
					if (response.body().length > MAX_RESPONSE_BYTES) {
						throw new ApiException("Response exceeded the 1 MiB limit");
					}
					return response;
				})
				.exceptionallyCompose(error -> CompletableFuture.failedFuture(
						error instanceof ApiException ? error : new ApiException("Network request failed", error)));
	}

	private static byte[] requireSuccess(HttpResponse<byte[]> response, String path) {
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new ApiException("Request to " + path + " failed (HTTP " + response.statusCode() + ")");
		}
		return response.body();
	}

	private static String requiredString(JsonObject json, String property) {
		JsonElement value = json.get(property);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
				|| value.getAsString().isBlank()) {
			throw new ApiException("Invalid API response: missing " + property);
		}
		return value.getAsString();
	}

	private static UUID parseUuid(String value) {
		try {
			String normalized = value.replace("-", "");
			if (normalized.length() != 32) {
				throw new IllegalArgumentException();
			}
			return UUID.fromString(normalized.substring(0, 8) + "-" + normalized.substring(8, 12) + "-"
					+ normalized.substring(12, 16) + "-" + normalized.substring(16, 20) + "-"
					+ normalized.substring(20));
		} catch (IllegalArgumentException exception) {
			throw new ApiException("Invalid UUID in API response", exception);
		}
	}

	public static final class ApiException extends RuntimeException {
		public ApiException(String message) {
			super(message);
		}

		public ApiException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
