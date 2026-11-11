package fuz.horizoncosmetics.client.mixin;

import fuz.horizoncosmetics.client.gui.CosmeticsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {
	@Inject(method = "init()V", at = @At("TAIL"))
	private void horizonCosmetics$addSettingsButton(CallbackInfo callbackInfo) {
		Screen screen = (Screen) (Object) this;
		int x = screen.width / 2 - 60;
		int y = screen.height - 52;
		((ScreenAccessor) this).horizonCosmetics$addRenderableWidget(
				Button.builder(Component.literal("Cosmetics"),
								button -> Minecraft.getInstance().gui.setScreen(new CosmeticsScreen(screen)))
						.bounds(x, y, 120, 20).build());
	}
}
