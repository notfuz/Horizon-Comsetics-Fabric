package fuz.horizoncosmetics.client.mixin;

import fuz.horizoncosmetics.client.render.HorizonCosmeticsRenderLayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void horizonCosmetics$addWearableLayer(CallbackInfo callbackInfo) {
		((LivingEntityRendererAccessor) this)
				.horizonCosmetics$addLayer(new HorizonCosmeticsRenderLayer((AvatarRenderer<?>)(Object)this));
	}
}
