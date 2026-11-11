package fuz.horizoncosmetics.client.gui;

import fuz.horizoncosmetics.client.cosmetics.ClientCosmeticsManager;
import fuz.horizoncosmetics.client.mixin.PlayerSkinWidgetAccessor;
import fuz.horizoncosmetics.client.render.CosmeticItemDefinition;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class CosmeticPlayerSkinWidget extends PlayerSkinWidget {
	private static final float FIT_SCALE = 0.97f / 2.125f;
	private static final float TRANSLATION_Y = -1.0625f;
	private final Supplier<PlayerSkin> skinLookup;
	private List<CosmeticItemDefinition> attachedItems = List.of();
	private List<TexturedModel> cosmeticModels = List.of();

	public CosmeticPlayerSkinWidget(int x, int y, net.minecraft.client.model.geom.EntityModelSet models,
									Supplier<PlayerSkin> skinLookup) {
		super(x, y, models, skinLookup);
		this.skinLookup = skinLookup;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
											float partialTick) {
		super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
		syncCosmeticModels();
		if (cosmeticModels.isEmpty()) {
			return;
		}
		PlayerSkinWidgetAccessor widget = (PlayerSkinWidgetAccessor) (Object) this;
		float scale = FIT_SCALE * this.getHeight();
		for (TexturedModel cosmetic : cosmeticModels) {
			graphics.skin(cosmetic.model(), cosmetic.texture(), scale,
					widget.horizonCosmetics$getRotationX(), widget.horizonCosmetics$getRotationY(),
					TRANSLATION_Y, this.getX(), this.getY(), this.getRight(), this.getBottom());
		}
	}

	private void syncCosmeticModels() {
		List<CosmeticItemDefinition> current = ClientCosmeticsManager.getInstance().equippedCosmetics();
		if (current.equals(attachedItems)) {
			return;
		}
		attachedItems = current;
		PlayerSkinWidgetAccessor widget = (PlayerSkinWidgetAccessor) (Object) this;
		Model.Simple base = skinLookup.get().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM
				? widget.horizonCosmetics$getSlimModel() : widget.horizonCosmetics$getWideModel();
		Map<Identifier, Map<CosmeticItemDefinition.Bone, List<ModelPart>>> grouped = new LinkedHashMap<>();
		for (CosmeticItemDefinition item : current) {
			for (CosmeticItemDefinition.Bone bone : CosmeticItemDefinition.Bone.values()) {
				List<ModelPart> parts = item.parts(bone);
				if (!parts.isEmpty()) {
					grouped.computeIfAbsent(item.textureId(), ignored ->
							new EnumMap<>(CosmeticItemDefinition.Bone.class))
							.computeIfAbsent(bone, ignored -> new ArrayList<>()).addAll(parts);
				}
			}
		}
		List<TexturedModel> models = new ArrayList<>();
		for (Map.Entry<Identifier, Map<CosmeticItemDefinition.Bone, List<ModelPart>>> texture : grouped.entrySet()) {
			Map<String, ModelPart> boneParts = new LinkedHashMap<>();
			for (Map.Entry<CosmeticItemDefinition.Bone, List<ModelPart>> bone : texture.getValue().entrySet()) {
				ModelPart source = base.root().getChild(boneName(bone.getKey()));
				Map<String, ModelPart> accessories = new LinkedHashMap<>();
				int index = 0;
				for (ModelPart part : bone.getValue()) {
					accessories.put("horizon_cosmetic_" + index++, part);
				}
				ModelPart previewBone = new ModelPart(List.of(), accessories);
				copyTransform(source, previewBone);
				boneParts.put(boneName(bone.getKey()), previewBone);
			}
			ModelPart root = new ModelPart(List.of(), boneParts);
			copyTransform(base.root(), root);
			models.add(new TexturedModel(texture.getKey(), new Model.Simple(root, RenderTypes::entityCutout)));
		}
		cosmeticModels = List.copyOf(models);
	}

	private static String boneName(CosmeticItemDefinition.Bone bone) {
		return switch (bone) {
			case HEAD -> "head";
			case BODY -> "body";
			case LEFT_ARM -> "left_arm";
			case RIGHT_ARM -> "right_arm";
			case LEFT_LEG -> "left_leg";
			case RIGHT_LEG -> "right_leg";
		};
	}

	private static void copyTransform(ModelPart source, ModelPart destination) {
		destination.setPos(source.x, source.y, source.z);
		destination.setRotation(source.xRot, source.yRot, source.zRot);
		destination.xScale = source.xScale;
		destination.yScale = source.yScale;
		destination.zScale = source.zScale;
		destination.visible = source.visible;
	}

	private record TexturedModel(Identifier texture, Model.Simple model) {
	}
}
