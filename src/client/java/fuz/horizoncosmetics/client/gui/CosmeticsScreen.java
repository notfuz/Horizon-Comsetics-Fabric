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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cosmetics screen. All geometry comes from a single {@link Layout} computed once per init(), and both the widgets
 * and the hand-drawn visuals read from it, so outlines, cards and buttons can no longer drift apart.
 */
public final class CosmeticsScreen extends Screen {
	private static final int MARGIN = 16;
	private static final int GAP = 6;
	private static final int PAD = 10;
	private static final int CARD_HEIGHT = 52;
	private static final int CARD_MIN_WIDTH = 150;
	private static final int DETAIL_HEIGHT = 44;
	private static final int BOTTOM_BAR = 30;

	private static final int INK = 0xFFF2F3FA;
	private static final int MUTED = 0xFFA8AEC2;
	private static final int ACCENT = 0xFFA98FFF;
	private static final int GOOD = 0xFF7BE0A4;
	private static final int PRICE = 0xFFFFD27A;
	private static final int BACKDROP = 0xA60B0E15;
	private static final int PANEL = 0xE8171B28;
	private static final int PANEL_EDGE = 0xFF343B50;
	private static final int CARD = 0xCC202536;
	private static final int CARD_HOVER = 0xDD2A3048;
	private static final int CARD_SELECTED = 0xDD30284F;

	private final Screen parent;
	private CosmeticsApiClient.OwnedCosmetics renderedAccount;
	private boolean renderedListed;
	private String renderedPlayingEmote;
	private boolean renderedLoading;
	private CosmeticPlayerSkinWidget mannequin;
	private Layout layout;
	private final List<CardView> cards = new ArrayList<>();
	private final List<Button> cardHits = new ArrayList<>();
	private int page;
	private Tab tab = Tab.OWNED;
	private String selectedId;
	private double previewZoom = 0.7;

	private enum Tab {
		OWNED("My Cosmetics"),
		STORE("Store"),
		EMOTES("Emotes"),
		SETTINGS("Settings");

		private final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	/** One clickable card. Both catalogue items and emotes are turned into this. */
	private record CardView(String id, String title, String subtitle, String badge, int badgeColor, String initial,
							int x, int y, int width, int height) {
	}

	/** Every rectangle on screen, computed once. */
	private record Layout(int leftX, int leftY, int leftW, int leftH,
						  int rightX, int rightY, int rightW, int rightH,
						  int innerX, int innerW,
						  int tabY, int titleY, int contentTop, int contentBottom,
						  int detailY, int pagerY, int barY,
						  int columns, int cardWidth, int rows) {
		int leftBottom() {
			return leftY + leftH;
		}

		int rightBottom() {
			return rightY + rightH;
		}
	}

	public CosmeticsScreen(Screen parent) {
		super(Component.literal("Horizon Cosmetics"));
		this.parent = parent;
	}

	private Layout computeLayout() {
		int panelY = 50;
		int panelBottom = Math.max(panelY + 160, this.height - BOTTOM_BAR - 8);
		int panelH = panelBottom - panelY;

		int rightW = Math.min(236, Math.max(168, this.width / 3));
		int rightX = this.width - MARGIN - rightW;
		int leftX = MARGIN;
		int leftW = Math.max(200, rightX - GAP * 2 - leftX);

		int innerX = leftX + PAD;
		int innerW = leftW - PAD * 2;
		int tabY = panelY + PAD;
		int titleY = tabY + 28;
		int contentTop = titleY + 14;
		int pagerY = panelBottom - PAD - 20;
		int detailY = pagerY - GAP - DETAIL_HEIGHT;
		int contentBottom = detailY - GAP;

		int columns = Math.max(1, Math.min(3, (innerW + GAP) / (CARD_MIN_WIDTH + GAP)));
		int cardWidth = (innerW - GAP * (columns - 1)) / columns;
		int rows = Math.max(1, (contentBottom - contentTop + GAP) / (CARD_HEIGHT + GAP));

		return new Layout(leftX, panelY, leftW, panelH, rightX, panelY, rightW, panelH, innerX, innerW,
				tabY, titleY, contentTop, contentBottom, detailY, pagerY, this.height - BOTTOM_BAR + 4,
				columns, cardWidth, rows);
	}

	@Override
	protected void init() {
		ClientCosmeticsManager manager = ClientCosmeticsManager.getInstance();
		if (manager.account() == null && !manager.isLoading()) {
			manager.refresh();
		}
		snapshot(manager);

		Layout l = computeLayout();
		this.layout = l;
		this.cards.clear();
		this.cardHits.clear();

		// Mannequin first so everything else draws above it.
		this.mannequin = new CosmeticPlayerSkinWidget(l.rightX(), l.rightY() + 26, this.minecraft.getEntityModels(),
				previewSkinLookup());
		updateMannequinBounds();
		this.addRenderableWidget(this.mannequin);

		addTabs(l);
		addBottomBar(l, manager);

		if (tab == Tab.SETTINGS) {
			addSettings(l, manager);
		} else {
			addCards(l, manager);
			addDetailButtons(l, manager);
			addPager(l);
		}
	}

	private void snapshot(ClientCosmeticsManager manager) {
		this.renderedAccount = manager.account();
		this.renderedListed = manager.isListed();
		this.renderedPlayingEmote = manager.playingEmote();
		this.renderedLoading = manager.isLoading();
	}

	private void addTabs(Layout l) {
		Tab[] tabs = Tab.values();
		int gap = 4;
		int tabWidth = (l.innerW() - gap * (tabs.length - 1)) / tabs.length;
		for (int index = 0; index < tabs.length; index++) {
			Tab target = tabs[index];
			this.addRenderableWidget(Button.builder(Component.literal(target.label), button -> switchTab(target))
					.bounds(l.innerX() + index * (tabWidth + gap), l.tabY(), tabWidth, 20).build());
		}
	}

	private void addBottomBar(Layout l, ClientCosmeticsManager manager) {
		CosmeticsApiClient.OwnedCosmetics account = manager.account();
		boolean visible = account == null || account.visible();
		int x = MARGIN;
		int y = l.barY();
		this.addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> manager.refresh())
				.bounds(x, y, 70, 20).build());
		x += 74;
		this.addRenderableWidget(Button.builder(Component.literal("Visibility: " + (visible ? "On" : "Off")),
				button -> manager.setVisible(!visible)).bounds(x, y, 104, 20).build());
		x += 108;
		this.addRenderableWidget(Button.builder(Component.literal("Presence: " + (manager.isListed() ? "On" : "Off")),
				button -> manager.setListed(!manager.isListed())).bounds(x, y, 98, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
				button -> this.minecraft.gui.setScreen(this.parent))
				.bounds(this.width - MARGIN - 90, y, 90, 20).build());
	}

	private void addSettings(Layout l, ClientCosmeticsManager manager) {
		boolean showOthers = manager.showOtherWearables();
		this.addRenderableWidget(Button.builder(
				Component.literal("Other players' wearables: " + (showOthers ? "Shown" : "Hidden")),
				button -> {
					manager.setShowOtherWearables(!manager.showOtherWearables());
					rebuild();
				}).bounds(l.innerX(), l.contentTop(), Math.min(l.innerW(), 260), 20).build());
	}

	private void addCards(Layout l, ClientCosmeticsManager manager) {
		List<CardView> all = tab == Tab.EMOTES ? emoteCards(l, manager) : itemCards(l, manager);
		int perPage = l.columns() * l.rows();
		int pageCount = Math.max(1, (all.size() + perPage - 1) / perPage);
		this.page = Math.min(this.page, pageCount - 1);
		int first = this.page * perPage;
		int last = Math.min(all.size(), first + perPage);
		for (int index = first; index < last; index++) {
			int slot = index - first;
			int column = slot % l.columns();
			int row = slot / l.columns();
			CardView template = all.get(index);
			CardView placed = new CardView(template.id(), template.title(), template.subtitle(), template.badge(),
					template.badgeColor(), template.initial(),
					l.innerX() + column * (l.cardWidth() + GAP), l.contentTop() + row * (CARD_HEIGHT + GAP),
					l.cardWidth(), CARD_HEIGHT);
			this.cards.add(placed);
			// Invisible, focusable hit-box: gives us click handling and keyboard focus for free.
			Button hit = Button.builder(Component.empty(), button -> onCardClicked(placed.id(), manager))
					.bounds(placed.x(), placed.y(), placed.width(), placed.height()).build();
			hit.setAlpha(0.0f);
			this.cardHits.add(hit);
			this.addRenderableWidget(hit);
		}
		this.pageCount = pageCount;
		this.totalCards = all.size();
	}

	private int pageCount = 1;
	private int totalCards;

	private List<CardView> itemCards(Layout l, ClientCosmeticsManager manager) {
		List<ClientCosmeticsManager.CatalogueItem> items = tab == Tab.OWNED ? manager.ownedItems()
				: manager.catalogueItems().stream()
				.filter(item -> !manager.isOwned(item.id()) && item.prices().containsKey(manager.storeCurrency()))
				.toList();
		Map<String, String> local = manager.localEquipped();
		CosmeticsApiClient.OwnedCosmetics account = manager.account();
		List<CardView> result = new ArrayList<>();
		for (ClientCosmeticsManager.CatalogueItem item : items) {
			String badge = "";
			int badgeColor = MUTED;
			if (tab == Tab.STORE) {
				badge = formatPrice(item.prices().get(manager.storeCurrency()), manager.storeCurrency());
				badgeColor = PRICE;
			} else if (account != null && item.id().equals(account.equipped().get(item.slot()))) {
				badge = "EQUIPPED";
				badgeColor = GOOD;
			} else if (item.id().equals(local.get(item.slot()))) {
				badge = "PREVIEWING";
				badgeColor = ACCENT;
			}
			result.add(new CardView(item.id(), item.name(), prettySlot(item.slot()), badge, badgeColor,
					initialOf(item.name()), 0, 0, 0, 0));
		}
		return result;
	}

	private List<CardView> emoteCards(Layout l, ClientCosmeticsManager manager) {
		CosmeticsApiClient.OwnedCosmetics account = manager.account();
		List<CardView> result = new ArrayList<>();
		if (account == null) {
			return result;
		}
		for (CosmeticsApiClient.OwnedEmote emote : account.emotes()) {
			boolean playing = emote.id().equals(manager.playingEmote());
			result.add(new CardView(emote.id(), emoteName(emote), "EMOTE", playing ? "PLAYING" : "",
					GOOD, initialOf(emoteName(emote)), 0, 0, 0, 0));
		}
		return result;
	}

	private void onCardClicked(String id, ClientCosmeticsManager manager) {
		boolean alreadySelected = id.equals(this.selectedId);
		this.selectedId = id;
		if (tab != Tab.EMOTES) {
			ClientCosmeticsManager.CatalogueItem item = findItem(manager, id);
			if (item != null && ClientCosmeticsManager.supportsLocalPreview(item.slot())) {
				boolean previewing = id.equals(manager.localEquipped().get(item.slot()));
				if (previewing && alreadySelected) {
					manager.unequipLocally(item.slot());
				} else if (!previewing) {
					manager.equipLocally(item.slot(), id);
				}
			}
		}
		rebuild();
	}

	private void addDetailButtons(Layout l, ClientCosmeticsManager manager) {
		if (selectedId == null) {
			return;
		}
		int buttonWidth = 96;
		int y = l.detailY() + (DETAIL_HEIGHT - 20) / 2;
		int right = l.innerX() + l.innerW() - 8;
		if (tab == Tab.EMOTES) {
			boolean playing = selectedId.equals(manager.playingEmote());
			this.addRenderableWidget(Button.builder(Component.literal(playing ? "Stop" : "Play"),
					button -> requestEmote(manager, selectedId)).bounds(right - buttonWidth, y, buttonWidth, 20).build());
			return;
		}
		ClientCosmeticsManager.CatalogueItem item = findItem(manager, selectedId);
		if (item == null) {
			return;
		}
		String previewing = manager.localEquipped().get(item.slot());
		if (tab == Tab.STORE) {
			String price = formatPrice(item.prices().get(manager.storeCurrency()), manager.storeCurrency());
			this.addRenderableWidget(Button.builder(Component.literal("Buy " + price),
					button -> manager.startCheckout(item.id())).bounds(right - buttonWidth, y, buttonWidth, 20).build());
		} else {
			CosmeticsApiClient.OwnedCosmetics account = manager.account();
			boolean serverEquipped = account != null && item.id().equals(account.equipped().get(item.slot()));
			this.addRenderableWidget(Button.builder(Component.literal(serverEquipped ? "Unequip" : "Equip"),
					button -> manager.equip(item.slot(), serverEquipped ? null : item.id()))
					.bounds(right - buttonWidth, y, buttonWidth, 20).build());
		}
		if (item.id().equals(previewing)) {
			this.addRenderableWidget(Button.builder(Component.literal("Stop preview"), button -> {
				manager.unequipLocally(item.slot());
				rebuild();
			}).bounds(right - buttonWidth * 2 - 4, y, buttonWidth, 20).build());
		}
	}

	private void addPager(Layout l) {
		int buttonWidth = 72;
		Button previous = Button.builder(Component.literal("Previous"), button -> {
			this.page = Math.max(0, this.page - 1);
			rebuild();
		}).bounds(l.innerX(), l.pagerY(), buttonWidth, 20).build();
		previous.active = this.page > 0;
		this.addRenderableWidget(previous);
		Button next = Button.builder(Component.literal("Next"), button -> {
			this.page = Math.min(this.pageCount - 1, this.page + 1);
			rebuild();
		}).bounds(l.innerX() + l.innerW() - buttonWidth, l.pagerY(), buttonWidth, 20).build();
		next.active = this.page < this.pageCount - 1;
		this.addRenderableWidget(next);
	}

	private void switchTab(Tab next) {
		if (this.tab != next) {
			this.tab = next;
			this.page = 0;
			this.selectedId = null;
		}
		rebuild();
	}

	private ClientCosmeticsManager.CatalogueItem findItem(ClientCosmeticsManager manager, String id) {
		for (ClientCosmeticsManager.CatalogueItem item : manager.catalogueItems()) {
			if (item.id().equals(id)) {
				return item;
			}
		}
		for (ClientCosmeticsManager.CatalogueItem item : manager.ownedItems()) {
			if (item.id().equals(id)) {
				return item;
			}
		}
		return null;
	}

	private static String emoteName(CosmeticsApiClient.OwnedEmote emote) {
		return emote.names().getOrDefault("en_us", emote.names().values().stream().findFirst().orElse(emote.id()));
	}

	private static String prettySlot(String slot) {
		return slot.replace('_', ' ').toUpperCase(Locale.ROOT);
	}

	private static String initialOf(String name) {
		return name == null || name.isBlank() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
	}

	private static String formatPrice(Long cents, String currency) {
		if (cents == null) {
			return "Price unavailable";
		}
		String amount = (cents / 100) + "." + String.format(Locale.ROOT, "%02d", cents % 100);
		return switch (currency) {
			case "USD" -> "$" + amount;
			case "EUR" -> "€" + amount;
			case "GBP" -> "£" + amount;
			case "CHF" -> "CHF " + amount;
			default -> amount + " " + currency;
		};
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

	/** Rebuilding while rendering is unsafe; detect backend changes on the tick instead. */
	@Override
	public void tick() {
		super.tick();
		ClientCosmeticsManager manager = ClientCosmeticsManager.getInstance();
		if (manager.account() != this.renderedAccount
				|| manager.isListed() != this.renderedListed
				|| manager.isLoading() != this.renderedLoading
				|| !Objects.equals(manager.playingEmote(), this.renderedPlayingEmote)) {
			rebuild();
		}
	}

	private String fit(String text, int maxWidth) {
		if (this.font.width(text) <= maxWidth) {
			return text;
		}
		int ellipsis = this.font.width("…");
		return this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - ellipsis)) + "…";
	}

	private void panel(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		graphics.fill(x, y, x + w, y + h, PANEL);
		graphics.outline(x, y, w, h, PANEL_EDGE);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		ClientCosmeticsManager manager = ClientCosmeticsManager.getInstance();
		Layout l = this.layout;

		graphics.fill(0, 0, this.width, this.height, BACKDROP);
		graphics.text(this.font, Component.literal("HORIZON  /  COSMETICS"), MARGIN, 12, INK);
		graphics.text(this.font, Component.literal("Your look, your collection."), MARGIN + 2, 25, MUTED);
		String status = fit(manager.status(), Math.max(40, this.width - MARGIN * 2 - 220));
		graphics.text(this.font, Component.literal(status), this.width - MARGIN - this.font.width(status), 18, MUTED);

		panel(graphics, l.leftX(), l.leftY(), l.leftW(), l.leftH());
		panel(graphics, l.rightX(), l.rightY(), l.rightW(), l.rightH());

		// Selected-tab marker under the tab strip.
		Tab[] tabs = Tab.values();
		int gap = 4;
		int tabWidth = (l.innerW() - gap * (tabs.length - 1)) / tabs.length;
		int tabIndex = this.tab.ordinal();
		graphics.fill(l.innerX() + tabIndex * (tabWidth + gap), l.tabY() + 22,
				l.innerX() + tabIndex * (tabWidth + gap) + tabWidth, l.tabY() + 24, ACCENT);

		String section = switch (tab) {
			case OWNED -> "MY COSMETICS";
			case STORE -> "HORIZON STORE  ·  " + manager.storeCurrency();
			case EMOTES -> "EMOTE LIBRARY";
			case SETTINGS -> "COSMETICS SETTINGS";
		};
		graphics.text(this.font, Component.literal(section), l.innerX(), l.titleY(), MUTED);
		graphics.text(this.font, Component.literal("MANNEQUIN PREVIEW"), l.rightX() + PAD, l.rightY() + 10, INK);

		if (tab == Tab.SETTINGS) {
			graphics.text(this.font, Component.literal(fit("Visibility and Presence are in the bar below.", l.innerW())),
					l.innerX(), l.contentTop() + 30, MUTED);
			graphics.text(this.font, Component.literal(fit("Hiding other players' wearables is saved locally.", l.innerW())),
					l.innerX(), l.contentTop() + 44, MUTED);
		} else {
			drawCards(graphics, mouseX, mouseY);
			drawDetail(graphics, l, manager);
			graphics.centeredText(this.font, Component.literal(this.pageCount > 1
							? (this.page + 1) + " / " + this.pageCount : this.totalCards + " items"),
					l.innerX() + l.innerW() / 2, l.pagerY() + 6, MUTED);
			drawEmptyState(graphics, l, manager);
		}

		int centerX = l.rightX() + l.rightW() / 2;
		graphics.centeredText(this.font, Component.literal(fit("Drag to rotate · Scroll to zoom", l.rightW() - 16)),
				centerX, l.rightBottom() - 28, INK);
		graphics.centeredText(this.font, Component.literal(fit("Local preview never changes ownership", l.rightW() - 16)),
				centerX, l.rightBottom() - 16, MUTED);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		drawCardFocus(graphics);
	}

	private void drawCards(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		for (CardView card : cards) {
			boolean hovered = mouseX >= card.x() && mouseX < card.x() + card.width()
					&& mouseY >= card.y() && mouseY < card.y() + card.height();
			boolean selected = card.id().equals(this.selectedId);
			int x2 = card.x() + card.width();
			int y2 = card.y() + card.height();
			graphics.fill(card.x(), card.y(), x2, y2, selected ? CARD_SELECTED : hovered ? CARD_HOVER : CARD);
			graphics.outline(card.x(), card.y(), card.width(), card.height(),
					selected ? ACCENT : hovered ? MUTED : PANEL_EDGE);

			// Thumbnail tile. Horizon icon art is not wired up yet, so this shows the item's initial.
			int tile = card.height() - 12;
			int tileX = card.x() + 6;
			int tileY = card.y() + 6;
			graphics.fill(tileX, tileY, tileX + tile, tileY + tile, 0xFF11141F);
			graphics.outline(tileX, tileY, tile, tile, PANEL_EDGE);
			graphics.centeredText(this.font, Component.literal(card.initial()), tileX + tile / 2,
					tileY + tile / 2 - 4, MUTED);

			int textX = tileX + tile + 8;
			int textW = x2 - textX - 6;
			graphics.text(this.font, Component.literal(fit(card.title(), textW)), textX, card.y() + 8, INK);
			graphics.text(this.font, Component.literal(fit(card.subtitle(), textW)), textX, card.y() + 21, MUTED);
			if (!card.badge().isEmpty()) {
				graphics.text(this.font, Component.literal(fit(card.badge(), textW)), textX, card.y() + 34,
						card.badgeColor());
			}
		}
	}

	private void drawCardFocus(GuiGraphicsExtractor graphics) {
		for (int index = 0; index < cardHits.size(); index++) {
			if (cardHits.get(index).isFocused()) {
				CardView card = cards.get(index);
				graphics.outline(card.x() - 1, card.y() - 1, card.width() + 2, card.height() + 2, INK);
			}
		}
	}

	private void drawDetail(GuiGraphicsExtractor graphics, Layout l, ClientCosmeticsManager manager) {
		int x2 = l.innerX() + l.innerW();
		graphics.fill(l.innerX(), l.detailY(), x2, l.detailY() + DETAIL_HEIGHT, 0xCC11141F);
		graphics.outline(l.innerX(), l.detailY(), l.innerW(), DETAIL_HEIGHT, PANEL_EDGE);
		int textX = l.innerX() + 8;
		int textW = l.innerW() - 16 - 96 * 2 - 8;
		if (selectedId == null) {
			graphics.text(this.font, Component.literal(fit(tab == Tab.EMOTES
					? "Select an emote to play it." : "Select a cosmetic to preview it on the mannequin.", l.innerW() - 16)),
					textX, l.detailY() + 18, MUTED);
			return;
		}
		if (tab == Tab.EMOTES) {
			String name = manager.ownedEmotes().stream().filter(e -> e.id().equals(selectedId))
					.map(CosmeticsScreen::emoteName).findFirst().orElse(selectedId);
			graphics.text(this.font, Component.literal(fit(name, textW)), textX, l.detailY() + 10, INK);
			graphics.text(this.font, Component.literal(fit("Requires Horizon Presence", textW)), textX,
					l.detailY() + 24, MUTED);
			return;
		}
		ClientCosmeticsManager.CatalogueItem item = findItem(manager, selectedId);
		if (item == null) {
			return;
		}
		graphics.text(this.font, Component.literal(fit(item.name(), textW)), textX, l.detailY() + 8, INK);
		graphics.text(this.font, Component.literal(fit(prettySlot(item.slot()), textW)), textX,
				l.detailY() + 20, MUTED);
		if (!item.description().isBlank()) {
			graphics.text(this.font, Component.literal(fit(item.description(), textW)), textX,
					l.detailY() + 31, MUTED);
		}
	}

	private void drawEmptyState(GuiGraphicsExtractor graphics, Layout l, ClientCosmeticsManager manager) {
		String message = null;
		if (manager.account() == null) {
			message = manager.isLoading() ? "Loading your wardrobe…" : "Press Refresh to connect.";
		} else if (cards.isEmpty()) {
			message = tab == Tab.EMOTES ? "No owned emotes were returned."
					: tab == Tab.STORE ? "Nothing is currently for sale." : "No owned cosmetics were returned.";
		}
		if (message != null) {
			graphics.centeredText(this.font, Component.literal(message), l.innerX() + l.innerW() / 2,
					l.contentTop() + 20, MUTED);
		}
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
		Layout l = this.layout;
		int areaX = l.rightX() + PAD;
		int areaY = l.rightY() + 26;
		int areaW = l.rightW() - PAD * 2;
		int areaH = Math.max(96, l.rightH() - 26 - 36);
		int modelWidth = Math.max(72, Math.min(areaW, (int) (areaW * this.previewZoom)));
		int modelHeight = Math.max(72, Math.min(areaH, (int) (areaH * this.previewZoom)));
		this.mannequin.setX(areaX + (areaW - modelWidth) / 2);
		this.mannequin.setY(areaY + (areaH - modelHeight) / 2);
		this.mannequin.setWidth(modelWidth);
		this.mannequin.setHeight(modelHeight);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		Layout l = this.layout;
		boolean overPreview = mouseX >= l.rightX() && mouseX <= l.rightX() + l.rightW()
				&& mouseY >= l.rightY() && mouseY <= l.rightBottom();
		if (overPreview) {
			this.previewZoom = Math.max(0.7, Math.min(1.0, this.previewZoom + scrollY * 0.1));
			updateMannequinBounds();
			return true;
		}
		boolean overList = mouseX >= l.leftX() && mouseX <= l.leftX() + l.leftW()
				&& mouseY >= l.leftY() && mouseY <= l.leftBottom();
		if (overList && tab != Tab.SETTINGS && this.pageCount > 1 && scrollY != 0) {
			int next = this.page + (scrollY < 0 ? 1 : -1);
			next = Math.max(0, Math.min(this.pageCount - 1, next));
			if (next != this.page) {
				this.page = next;
				rebuild();
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}