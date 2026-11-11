package fuz.horizoncosmetics.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fuz.horizoncosmetics.HorizonCosmeticsFabric;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CosmeticsClientConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("horizon-cosmetics-fabric-settings.json");
	private Boolean showOtherWearables;

	public CosmeticsClientConfig() {
		load();
	}

	public boolean showOtherWearables(boolean ownsCosmetics) {
		return showOtherWearables == null ? ownsCosmetics : showOtherWearables;
	}

	public void setShowOtherWearables(boolean value) {
		showOtherWearables = value;
		save();
	}

	private void load() {
		if (!Files.exists(FILE)) {
			return;
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8));
			if (!root.isJsonObject()) {
				throw new IOException("Settings file must contain a JSON object");
			}
			JsonElement setting = root.getAsJsonObject().get("showOtherWearables");
			if (setting != null) {
				if (!setting.isJsonPrimitive() || !setting.getAsJsonPrimitive().isBoolean()) {
					throw new IOException("showOtherWearables must be a boolean");
				}
				showOtherWearables = setting.getAsBoolean();
			}
		} catch (IOException | RuntimeException exception) {
			HorizonCosmeticsFabric.LOGGER.warn("Could not read Horizon Cosmetics settings from {}", FILE, exception);
		}
	}

	private void save() {
		try {
			Files.createDirectories(FILE.getParent());
			JsonObject settings = new JsonObject();
			settings.addProperty("showOtherWearables", showOtherWearables);
			Files.writeString(FILE, settings.toString(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			HorizonCosmeticsFabric.LOGGER.warn("Could not save Horizon Cosmetics settings to {}", FILE, exception);
		}
	}
}
