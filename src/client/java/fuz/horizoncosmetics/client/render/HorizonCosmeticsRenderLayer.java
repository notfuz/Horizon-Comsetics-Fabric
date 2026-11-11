package fuz.horizoncosmetics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import fuz.horizoncosmetics.client.cosmetics.ClientCosmeticsManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;

public final class HorizonCosmeticsRenderLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	public HorizonCosmeticsRenderLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light,
					   AvatarRenderState state, float bodyYaw, float headPitch) {
		Minecraft minecraft = Minecraft.getInstance();
		if (state.isInvisible || minecraft.level == null) {
			return;
		}
		Entity wearer = minecraft.level.getEntity(state.id);
		if (wearer == null || wearer.isInvisible()) {
			return;
		}

		ClientCosmeticsManager manager = ClientCosmeticsManager.getInstance();
		for (CosmeticItemDefinition item : manager.wornCosmetics(wearer.getUUID())) {
			if (!isWearable(item.slot()) || item.textureId() == null) {
				continue;
			}
			for (CosmeticItemDefinition.Bone bone : CosmeticItemDefinition.Bone.values()) {
				ModelPart playerPart = bonePart(this.getParentModel(), bone);
				if (playerPart == null || !playerPart.visible) {
					continue;
				}
				for (ModelPart cosmeticPart : item.parts(bone)) {
					poseStack.pushPose();
					playerPart.translateAndRotate(poseStack);
					collector.submitModelPart(cosmeticPart, poseStack,
							RenderTypes.entityCutout(item.textureId()), light,
							OverlayTexture.NO_OVERLAY, null);
					poseStack.popPose();
				}
			}
		}
	}

	private static boolean isWearable(String slot) {
		return slot.equals("head") || slot.equals("back") || slot.equals("shoulder") || slot.equals("torso");
	}

	private static ModelPart bonePart(PlayerModel model, CosmeticItemDefinition.Bone bone) {
		return switch (bone) {
			case HEAD -> model.head;
			case BODY -> model.body;
			case LEFT_ARM -> model.leftArm;
			case RIGHT_ARM -> model.rightArm;
			case LEFT_LEG -> model.leftLeg;
			case RIGHT_LEG -> model.rightLeg;
		};
	}
}
