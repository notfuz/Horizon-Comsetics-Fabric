package fuz.horizoncosmetics.client.mixin;

import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.model.Model;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerSkinWidget.class)
public interface PlayerSkinWidgetAccessor {
	@Accessor("wideModel")
	Model.Simple horizonCosmetics$getWideModel();

	@Accessor("slimModel")
	Model.Simple horizonCosmetics$getSlimModel();

	@Accessor("rotationX")
	float horizonCosmetics$getRotationX();

	@Accessor("rotationY")
	float horizonCosmetics$getRotationY();
}
