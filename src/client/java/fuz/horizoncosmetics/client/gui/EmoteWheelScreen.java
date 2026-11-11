package fuz.horizoncosmetics.client.gui;

import fuz.horizoncosmetics.client.api.CosmeticsApiClient;
import fuz.horizoncosmetics.client.HorizonCosmeticsFabricClient;
import fuz.horizoncosmetics.client.cosmetics.ClientCosmeticsManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class EmoteWheelScreen extends Screen {
	private static final int INK = 0xFFF2F3FA;
	private static final int MUTED = 0xFFA8AEC2;
	private static final int ACCENT = 0xFFA98FFF;
	private static final int MAX_SLOTS = 8;
	private final List<CosmeticsApiClient.OwnedEmote> emotes;

	public EmoteWheelScreen() {
		super(Component.literal("Horizon Emote Wheel"));
		this.emotes = ClientCosmeticsManager.getInstance().ownedEmotes()
				.stream().limit(MAX_SLOTS).toList();
	}

	@Override
	protected void init() {
		if (emotes.isEmpty()) {
			return;
		}
		int count = emotes.size();
		int centerX = this.width / 2;
		int centerY = this.height / 2;
		double radius = Math.min(115, Math.max(72, Math.min(this.width, this.height) * 0.25));
		int buttonWidth = Math.min(132, Math.max(92, this.width / 7));
		for (int index = 0; index < count; index++) {
			double angle = -Math.PI / 2 + Math.PI * 2 * index / count;
			int x = (int) Math.round(centerX + Math.cos(angle) * radius - buttonWidth / 2.0);
			int y = (int) Math.round(centerY + Math.sin(angle) * radius - 10);
			CosmeticsApiClient.OwnedEmote emote = emotes.get(index);
			String name = emote.names().getOrDefault("en_us",
					emote.names().values().stream().findFirst().orElse(emote.id()));
			this.addRenderableWidget(Button.builder(Component.literal(name),
							button -> selectEmote(emote.id()))
					.bounds(x, y, buttonWidth, 20).build());
		}
	}

	private void selectEmote(String emoteId) {
		ClientCosmeticsManager manager = ClientCosmeticsManager.getInstance();
		this.minecraft.gui.setScreen(null);
		if (manager.isListed()) {
			manager.playEmote(emoteId);
			return;
		}
		this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			this.minecraft.gui.setScreen(null);
			if (confirmed) {
				manager.enablePresenceAndPlayEmote(emoteId);
			}
		}, Component.literal("Enable Horizon Presence?"),
				Component.literal("Playing emotes requires Horizon Presence. This makes you discoverable as a "
						+ "Horizon player for up to three hours after your last update.")));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int centerX = this.width / 2;
		int centerY = this.height / 2;
		int radius = Math.min(130, Math.max(82, Math.min(this.width, this.height) / 3));
		graphics.fill(0, 0, this.width, this.height, 0xA60B0E15);
		graphics.fill(centerX - radius, centerY - radius, centerX + radius, centerY + radius, 0x80171B28);
		graphics.outline(centerX - radius, centerY - radius, centerX + radius, centerY + radius, ACCENT);
		graphics.centeredText(this.font, Component.literal(emotes.isEmpty()
						? "No owned emotes" : "Choose an emote"),
				centerX, centerY - 5, INK);
		graphics.centeredText(this.font, Component.literal("Click an emote \u00b7 Esc to cancel"),
				centerX, centerY + radius + 16, MUTED);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(null);
	}

	@Override
	public void removed() {
		HorizonCosmeticsFabricClient.wheelClosed();
		super.removed();
	}
}
