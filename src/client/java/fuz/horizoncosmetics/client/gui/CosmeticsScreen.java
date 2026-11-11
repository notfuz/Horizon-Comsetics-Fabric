package fuz.horizoncosmetics.client.gui;

import fuz.horizoncosmetics.client.api.CosmeticsApiClient;
import fuz.horizoncosmetics.client.cosmetics.ClientCosmeticsManager;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class CosmeticsScreen extends Screen {
	private static final int PANEL_TOP = 64;
	private static final int PANEL_BOTTOM_MARGIN = 42;
	private static final int ROW_HEIGHT = 68;
	private static final int INK = 0xFFF2F3FA;
	private static final int MUTED = 0xFFA8AEC2;
	private static final int PANEL = 0xE8171B28;
	private static final int PANEL_EDGE = 0xFF343B50;

	private final Screen parent;
	private CosmeticsApiClient.OwnedCosmetics renderedAccount;
	private boolean renderedListed;
	private String renderedPlayingEmote;
	private boolean hasWardrobeItems;
	private List<ClientCosmeticsManager.CatalogueItem> displayedItems = List.of();
	private CosmeticPlayerSkinWidget mannequin;
	private int page;
	private Tab tab = Tab.OWNED;
	private double previewZoom = 0.7;

	private enum Tab {
		OWNED,
		STORE,
		EMOTES,
		SETTINGS
	}

	public CosmeticsScreen(Screen parent) {
		super(Component.literal("Horizon Cosmetics"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ClientCosmeticsManager manager = ClientCosmeticsManager.getInstance();
		if (manager.account() == null && !manager.isLoading()) {
			manager.refresh();
		}
		this.renderedAccount = manager.account();
		this.renderedListed = manager.isListed();
		this.renderedPlayingEmote = manager.playingEmote();

		int margin = 18;
		int previewWidth = Math.min(236, Math.max(168, this.width / 3));
		int previewX = this.width - margin - previewWidth;
		int listX = margin;
		int listWidth = previewX - listX - 12;
		int panelHeight = Math.max(120, this.height - PANEL_TOP - PANEL_BOTTOM_MARGIN);
		this.mannequin = new CosmeticPlayerSkinWidget(previewX, PANEL_TOP + 42, this.minecraft.getEntityModels(),
				previewSkinLookup());
		updateMannequinBounds();
		this.addRenderableWidget(this.mannequin);

		this.addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> manager.refresh())
				.bounds(margin, 37, 76, 20).build());
		CosmeticsApiClient.OwnedCosmetics account = manager.account();
		boolean visible = account == null || account.visible();
		this.addRenderableWidget(Button.builder(Component.literal("Visibility: " + (visible ? "On" : "Off")),
						button -> manager.setVisible(!visible))
				.bounds(margin + 82, 37, 112, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Presence: " + (manager.isListed() ? "On" : "Off")),
						button -> manager.setListed(!manager.isListed()))
				.bounds(margin + 200, 37, 104, 20).build());

		int tabY = PANEL_TOP + 12;
		int tabWidth = (listWidth - 36) / 4;
		Tab[] tabs = Tab.values();
		String[] tabNames = {"My Cosmetics", "Store", "Emotes", "Settings"};
		for (int index = 0; index < tabs.length; index++) {
			Tab selectedTab = tabs[index];
			this.addRenderableWidget(Button.builder(Component.literal(tabNames[index]),
							button -> switchTab(selectedTab))
					.bounds(listX + 12 + index * (tabWidth + 4), tabY, tabWidth, 20).build());
		}
		if (tab == Tab.STORE) {
			this.addRenderableWidget(Button.builder(Component.literal("Full Horizon Shop"),
							button -> manager.openFullStore())
					.bounds(listX + listWidth - 124, PANEL_TOP + 36, 112, 20).build());
		}

		List<ClientCosmeticsManager.CatalogueItem> catalogueItems = cardItems(manager);
		this.displayedItems = catalogueItems;
		Map<String, String> localEquipped = manager.localEquipped();
		this.hasWardrobeItems = !catalogueItems.isEmpty();
		List<CosmeticsApiClient.OwnedEmote> emotes = account == null ? List.of() : account.emotes();
		int itemCount = tab == Tab.EMOTES ? emotes.size() : catalogueItems.size();
		int listStartY = PANEL_TOP + 62;
		int rowsPerPage = Math.max(1, (panelHeight - 128) / ROW_HEIGHT);
		int pageCount = Math.max(1, (itemCount + rowsPerPage - 1) / rowsPerPage);
		this.page = Math.min(this.page, pageCount - 1);
		int pageStart = this.page * rowsPerPage;
		int rowY = listStartY;

		if (tab == Tab.EMOTES) {
			for (CosmeticsApiClient.OwnedEmote emote : emotes.subList(pageStart,
					Math.min(emotes.size(), pageStart + rowsPerPage))) {
				String name = emote.names().getOrDefault("en_us",
						emote.names().values().stream().findFirst().orElse(emote.id()));
				boolean playing = emote.id().equals(manager.playingEmote());
				this.addRenderableWidget(Button.builder(Component.literal((playing ? "Stop  " : "Play  ") + name),
								button -> requestEmote(manager, emote.id()))
						.bounds(listX + 12, rowY + 14, listWidth - 24, 24).build());
				rowY += ROW_HEIGHT;
			}
		} else if (tab == Tab.SETTINGS) {
			boolean showOthers = manager.showOtherWearables();
			this.addRenderableWidget(Button.builder(
							Component.literal("Other players' wearable cosmetics: " + (showOthers ? "Shown" : "Hidden")),
							button -> {
								manager.setShowOtherWearables(!manager.showOtherWearables());
								rebuild();
							})
					.bounds(listX + 12, listStartY + 32, listWidth - 24, 24).build());
		} else if (tab == Tab.OWNED) {
			int columnGap = 4;
			int itemButtonWidth = (listWidth - 24 - columnGap) / 2;
			for (ClientCosmeticsManager.CatalogueItem item : catalogueItems.subList(pageStart,
					Math.min(catalogueItems.size(), pageStart + rowsPerPage))) {
				String current = account == null ? null : account.equipped().get(item.slot());
				boolean serverEquipped = item.id().equals(current);
				boolean locallyEquipped = item.id().equals(localEquipped.get(item.slot()));
				String localTitle = (locallyEquipped ? "Local \u2713  " : "Preview  ")
						+ item.name() + " \u00b7 " + item.slot().replace('_', ' ');
				this.addRenderableWidget(Button.builder(Component.literal(localTitle), button -> {
							if (locallyEquipped) {
								manager.unequipLocally(item.slot());
							} else {
								manager.equipLocally(item.slot(), item.id());
							}
							rebuild();
						})
						.bounds(listX + 12, rowY + 43, itemButtonWidth, 18).build());
				String serverTitle = manager.isOwned(item.id())
						? (serverEquipped ? "Online: Equipped" : "Equip Online")
						: "Not Owned";
				this.addRenderableWidget(Button.builder(Component.literal(serverTitle), button -> {
							if (manager.isOwned(item.id())) {
								manager.equip(item.slot(), serverEquipped ? null : item.id());
							}
						})
						.bounds(listX + 12 + itemButtonWidth + columnGap, rowY + 43, itemButtonWidth, 18).build());
				rowY += ROW_HEIGHT;
			}
		} else {
			int columnGap = 4;
			int itemButtonWidth = (listWidth - 24 - columnGap) / 2;
			for (ClientCosmeticsManager.CatalogueItem item : catalogueItems.subList(pageStart,
					Math.min(catalogueItems.size(), pageStart + rowsPerPage))) {
				String price = formatPrice(item.prices().get(manager.storeCurrency()), manager.storeCurrency());
				boolean locallyEquipped = item.id().equals(localEquipped.get(item.slot()));
				boolean wearable = ClientCosmeticsManager.supportsLocalPreview(item.slot());
				this.addRenderableWidget(Button.builder(
								Component.literal((wearable ? (locallyEquipped ? "Previewing  " : "Preview  ") : "")
										+ item.name() + " \u00b7 " + item.slot().replace('_', ' ')),
								button -> {
									if (wearable) {
										if (locallyEquipped) {
											manager.unequipLocally(item.slot());
										} else {
											manager.equipLocally(item.slot(), item.id());
										}
										rebuild();
									}
								})
						.bounds(listX + 12, rowY + 43, itemButtonWidth, 18).build());
				this.addRenderableWidget(Button.builder(Component.literal("Buy  " + price),
								button -> manager.startCheckout(item.id()))
						.bounds(listX + 12 + itemButtonWidth + columnGap, rowY + 43, itemButtonWidth, 18).build());
				rowY += ROW_HEIGHT;
			}
		}

		int pagerY = PANEL_TOP + panelHeight - 32;
		this.addRenderableWidget(Button.builder(Component.literal("Previous"), button -> {
					this.page = Math.max(0, this.page - 1);
					rebuild();
				})
				.bounds(listX + 12, pagerY, 82, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal((this.page + 1) + " / " + pageCount),
						button -> {})
				.bounds(listX + 100, pagerY, Math.max(45, listWidth - 200), 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Next"), button -> {
					this.page = Math.min(pageCount - 1, this.page + 1);
					rebuild();
				})
				.bounds(listX + listWidth - 94, pagerY, 82, 20).build());

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
						button -> this.minecraft.gui.setScreen(this.parent))
				.bounds(this.width - margin - 96, this.height - 28, 96, 20).build());
	}

	private void switchTab(Tab next) {
		this.tab = next;
		this.page = 0;
		rebuild();
	}

	private static String formatPrice(Long cents, String currency) {
		if (cents == null) {
			return "Price unavailable";
		}
		String amount = (cents / 100) + "." + String.format(java.util.Locale.ROOT, "%02d", cents % 100);
		return switch (currency) {
			case "USD" -> "$" + amount;
			case "EUR" -> "\u20ac" + amount;
			case "GBP" -> "\u00a3" + amount;
			case "CHF" -> "CHF " + amount;
			default -> amount + " " + currency;
		};
	}

	private List<ClientCosmeticsManager.CatalogueItem> cardItems(ClientCosmeticsManager manager) {
		if (tab == Tab.OWNED) {
			return manager.ownedItems();
		}
		if (tab != Tab.STORE) {
			return List.of();
		}
		return manager.catalogueItems().stream()
				.filter(item -> !manager.isOwned(item.id())
						&& item.prices().containsKey(manager.storeCurrency()))
				.toList();
	}

	private void rebuild() {
		this.clearWidgets();
		this.init();
	}

	private void requestEmote(ClientCosmeticsManager manager, String emoteId) {
		if (manager.isListed()) {
			manager.playEmote(emoteId);
			rebuild();
			return;
		}
		this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			this.minecraft.gui.setScreen(this);
			if (confirmed) {
				manager.enablePresenceAndPlayEmote(emoteId);
			}
		}, Component.literal("Enable Horizon Presence?"),
				Component.literal("Playing emotes requires Horizon Presence. This makes you discoverable as a "
						+ "Horizon player for up to three hours after your last update.")));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		ClientCosmeticsManager manager = ClientCosmeticsManager.getInstance();
		if (!manager.isLoading() && (manager.account() != this.renderedAccount
				|| manager.isListed() != this.renderedListed
				|| !java.util.Objects.equals(manager.playingEmote(), this.renderedPlayingEmote))) {
			this.renderedAccount = manager.account();
			this.renderedListed = manager.isListed();
			this.renderedPlayingEmote = manager.playingEmote();
			rebuild();
		}

		int margin = 18;
		int previewWidth = Math.min(236, Math.max(168, this.width / 3));
		int previewX = this.width - margin - previewWidth;
		int listWidth = previewX - margin - 12;
		int panelHeight = Math.max(120, this.height - PANEL_TOP - PANEL_BOTTOM_MARGIN);
		int panelBottom = PANEL_TOP + panelHeight;

		graphics.fill(0, 0, this.width, this.height, 0xA60B0E15);
		graphics.text(this.font, Component.literal("HORIZON  /  COSMETICS"), margin, 13, INK);
		graphics.text(this.font, Component.literal("Your look, your collection."), margin + 2, 25, MUTED);
		graphics.text(this.font, Component.literal(manager.status()), margin + 318, 42, MUTED);

		graphics.fill(margin, PANEL_TOP, margin + listWidth, panelBottom, PANEL);
		graphics.outline(margin, PANEL_TOP, margin + listWidth, panelBottom, PANEL_EDGE);
		graphics.fill(previewX, PANEL_TOP, previewX + previewWidth, panelBottom, PANEL);
		graphics.outline(previewX, PANEL_TOP, previewX + previewWidth, panelBottom, PANEL_EDGE);
		if (tab == Tab.OWNED || tab == Tab.STORE) {
			List<ClientCosmeticsManager.CatalogueItem> cards = displayedItems;
			int rowsPerPage = Math.max(1, (panelHeight - 128) / ROW_HEIGHT);
			int first = this.page * rowsPerPage;
			Map<String, String> local = manager.localEquipped();
			for (int index = first; index < Math.min(cards.size(), first + rowsPerPage); index++) {
				ClientCosmeticsManager.CatalogueItem item = cards.get(index);
				int cardY = PANEL_TOP + 62 + (index - first) * ROW_HEIGHT;
				int cardLeft = margin + 12;
				int cardRight = margin + listWidth - 12;
				graphics.fill(cardLeft, cardY, cardRight, cardY + ROW_HEIGHT - 4, 0xCC202536);
				graphics.outline(cardLeft, cardY, cardRight, cardY + ROW_HEIGHT - 4, PANEL_EDGE);
				graphics.text(this.font,
						Component.literal(this.font.plainSubstrByWidth(item.name(), listWidth - 44)),
						cardLeft + 8, cardY + 5, INK);
				String detail = item.slot().replace('_', ' ').toUpperCase(java.util.Locale.ROOT);
				if (tab == Tab.STORE) {
					detail += "  \u00b7  " + formatPrice(item.prices().get(manager.storeCurrency()),
							manager.storeCurrency());
				} else {
					String equipped = manager.account() == null ? null
							: manager.account().equipped().get(item.slot());
					if (item.id().equals(equipped)) {
						detail += "  \u00b7  EQUIPPED ONLINE";
					}
					if (item.id().equals(local.get(item.slot()))) {
						detail += "  \u00b7  LOCAL PREVIEW";
					}
				}
				graphics.text(this.font,
						Component.literal(this.font.plainSubstrByWidth(detail, listWidth - 44)),
						cardLeft + 8, cardY + 17, MUTED);
				if (!item.description().isBlank()) {
					graphics.text(this.font,
							Component.literal(this.font.plainSubstrByWidth(item.description(), listWidth - 44)),
							cardLeft + 8, cardY + 29, MUTED);
				}
			}
		}
		String section = switch (tab) {
			case OWNED -> "MY COSMETICS";
			case STORE -> "HORIZON STORE  \u00b7  " + manager.storeCurrency();
			case EMOTES -> "EMOTE LIBRARY";
			case SETTINGS -> "COSMETICS SETTINGS";
		};
		graphics.text(this.font, Component.literal(section),
				margin + 12, PANEL_TOP + 42, MUTED);
		graphics.text(this.font, Component.literal("MANNEQUIN PREVIEW"), previewX + 12, PANEL_TOP + 14, INK);

		if (tab == Tab.SETTINGS) {
			graphics.text(this.font, Component.literal("My cosmetics visibility and Horizon Presence are managed above."),
					margin + 14, PANEL_TOP + 84, MUTED);
			graphics.text(this.font, Component.literal("Other-player wearables can be hidden locally; this preference is saved."),
					margin + 14, PANEL_TOP + 99, MUTED);
		} else if (tab != Tab.EMOTES) {
			graphics.centeredText(this.font, Component.literal("Drag to rotate \u00b7 Scroll to zoom"),
					previewX + previewWidth / 2, panelBottom - 36, INK);
			graphics.centeredText(this.font, Component.literal("Local preview never changes ownership"),
					previewX + previewWidth / 2, panelBottom - 22, MUTED);
		} else {
			graphics.centeredText(this.font, Component.literal("Emote playback requires Horizon Presence"),
					previewX + previewWidth / 2, PANEL_TOP + panelHeight / 2, MUTED);
		}

		if (tab != Tab.SETTINGS && !manager.isLoading() && manager.account() != null
				&& (tab == Tab.EMOTES ? manager.account().emotes().isEmpty() : !hasWardrobeItems)) {
			graphics.text(this.font, Component.literal(tab == Tab.EMOTES
							? "No owned emotes have been returned."
							: tab == Tab.STORE ? "No unowned catalogue items are currently for sale."
							: "No owned cosmetics have been returned."),
					margin + 14, PANEL_TOP + 76, MUTED);
		} else if (tab != Tab.SETTINGS && manager.account() == null && !manager.isLoading()) {
			graphics.text(this.font, Component.literal("Refresh to connect and load your wardrobe."),
					margin + 14, PANEL_TOP + 76, MUTED);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private Supplier<PlayerSkin> previewSkinLookup() {
		var user = this.minecraft.getUser();
		GameProfile profile = new GameProfile(user.getProfileId(), user.getName());
		Supplier<PlayerSkin> lookup = this.minecraft.getSkinManager().createLookup(profile, true);
		UUID profileId = user.getProfileId();
		return () -> this.minecraft.player instanceof net.minecraft.client.player.AbstractClientPlayer player
				&& player.getUUID().equals(profileId) ? player.getSkin() : lookup.get();
	}

	private void updateMannequinBounds() {
		int previewWidth = Math.min(236, Math.max(168, this.width / 3));
		int previewX = this.width - 18 - previewWidth;
		int panelHeight = Math.max(120, this.height - PANEL_TOP - PANEL_BOTTOM_MARGIN);
		int maxModelWidth = Math.max(96, previewWidth - 24);
		int maxModelHeight = Math.max(96, panelHeight - 100);
		int modelWidth = Math.max(72, Math.min(maxModelWidth, (int) (maxModelWidth * this.previewZoom)));
		int modelHeight = Math.max(72, Math.min(maxModelHeight, (int) (maxModelHeight * this.previewZoom)));
		this.mannequin.setX(previewX + (previewWidth - modelWidth) / 2);
		this.mannequin.setY(PANEL_TOP + 42 + (maxModelHeight - modelHeight) / 2);
		this.mannequin.setWidth(modelWidth);
		this.mannequin.setHeight(modelHeight);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int previewWidth = Math.min(236, Math.max(168, this.width / 3));
		int previewX = this.width - 18 - previewWidth;
		int panelHeight = Math.max(120, this.height - PANEL_TOP - PANEL_BOTTOM_MARGIN);
		if (mouseX < previewX || mouseX > previewX + previewWidth
				|| mouseY < PANEL_TOP || mouseY > PANEL_TOP + panelHeight) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}
		this.previewZoom = Math.max(0.7, Math.min(1.0, this.previewZoom + scrollY * 0.1));
		updateMannequinBounds();
		return true;
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
