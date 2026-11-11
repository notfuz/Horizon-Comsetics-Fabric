package fuz.horizoncosmetics.client;

import com.mojang.blaze3d.platform.InputConstants;
import fuz.horizoncosmetics.client.cosmetics.ClientCosmeticsManager;
import fuz.horizoncosmetics.client.gui.EmoteWheelScreen;
import fuz.horizoncosmetics.client.mixin.OptionsAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HorizonCosmeticsFabricClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("horizon-cosmetics-fabric");
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath("horizon-cosmetics-fabric", "controls"));
	private static KeyMapping emoteWheel;
	private static boolean wheelOpen;
	private static boolean keyMappingRegistered;

	@Override
	public void onInitializeClient() {
		emoteWheel = new KeyMapping("key.horizon-cosmetics-fabric.emote_wheel",
				InputConstants.Type.KEYBOARD, 66, CATEGORY);

		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			if (!keyMappingRegistered && minecraft.options != null) {
				OptionsAccessor options = (OptionsAccessor) minecraft.options;
				KeyMapping[] currentMappings = options.horizonCosmetics$getKeyMappings();
				KeyMapping[] updatedMappings = java.util.Arrays.copyOf(currentMappings, currentMappings.length + 1);
				updatedMappings[currentMappings.length] = emoteWheel;
				options.horizonCosmetics$setKeyMappings(updatedMappings);
				keyMappingRegistered = true;
			}
			ClientCosmeticsManager.getInstance().tick(minecraft);
			while (keyMappingRegistered && emoteWheel.consumeClick()) {
				if (wheelOpen) {
					minecraft.gui.setScreen(null);
				} else if (minecraft.gui.screen() == null && minecraft.player != null) {
					if (ClientCosmeticsManager.getInstance().account() == null) {
						ClientCosmeticsManager.getInstance().refresh();
					}
					wheelOpen = true;
					minecraft.gui.setScreen(new EmoteWheelScreen());
				}
			}
		});
		LOGGER.info("Horizon Cosmetics Fabric client initialized");
	}

	public static void wheelClosed() {
		wheelOpen = false;
	}
}
