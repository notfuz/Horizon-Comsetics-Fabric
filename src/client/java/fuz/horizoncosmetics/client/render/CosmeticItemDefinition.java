package fuz.horizoncosmetics.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CosmeticItemDefinition {
	private static final int MAX_PARTS = 16;
	private static final int MAX_CUBES = 21;
	private static final int MAX_TEXTURE_SIDE = 64;
	private static final float MAX_REACH = 24;

	private final String id;
	private final String slot;
	private final String assetKey;
	private final byte[] texturePng;
	private final Map<Bone, List<ModelPart>> attachedParts;
	private final List<PartPose> restPoses;
	private final List<Animation> animations;
	private Identifier textureId;

	private CosmeticItemDefinition(String id, String slot, String assetKey, byte[] texturePng,
								   Map<Bone, List<ModelPart>> attachedParts,
								   List<PartPose> restPoses, List<Animation> animations) {
		this.id = id;
		this.slot = slot;
		this.assetKey = assetKey;
		this.texturePng = texturePng;
		this.attachedParts = Map.copyOf(attachedParts);
		this.restPoses = List.copyOf(restPoses);
		this.animations = List.copyOf(animations);
	}

	public static CosmeticItemDefinition parse(byte[] bytes, String expectedId, String assetKey) {
		if (bytes.length == 0 || bytes.length > 1_048_576) {
			throw new IllegalArgumentException("Cosmetic JSON must be between 1 byte and 1 MiB");
		}
		JsonElement rootValue = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
		if (!rootValue.isJsonObject()) {
			throw new IllegalArgumentException("Cosmetic document must be an object");
		}
		JsonObject root = rootValue.getAsJsonObject();
		rejectUnknownFields(root, Set.of("format", "id", "slot", "bone", "attach", "texture", "parts",
				"animations", "tag", "emote", "companion", "trail", "aura"), "cosmetic");
		if (requiredInt(root, "format") != 1) {
			throw new IllegalArgumentException("Unsupported Horizon cosmetic format");
		}
		String id = requiredString(root, "id");
		if (!id.equals(expectedId)) {
			throw new IllegalArgumentException("Cosmetic ID does not match the service response");
		}
		String slot = requiredString(root, "slot");
		if (!Set.of("head", "back", "shoulder", "torso", "name_tag", "emote", "companion", "trail", "aura")
				.contains(slot)) {
			throw new IllegalArgumentException("Unsupported cosmetic slot");
		}
		if (!Set.of("head", "back", "shoulder", "torso").contains(slot)) {
			throw new IllegalArgumentException("Only wearable model cosmetics are currently supported");
		}
		for (String unsupported : List.of("tag", "emote", "companion", "trail", "aura")) {
			if (root.has(unsupported)) {
				throw new IllegalArgumentException("Cosmetic feature is not supported: " + unsupported);
			}
		}
		TextureData textureData = null;
		if (root.has("texture")) {
			JsonElement texture = root.get("texture");
			if (!texture.isJsonObject() || !texture.getAsJsonObject().has("png")) {
				throw new IllegalArgumentException("Invalid cosmetic texture");
			}
			rejectUnknownFields(texture.getAsJsonObject(), Set.of("png"), "texture");
			String encoded = requiredString(texture.getAsJsonObject(), "png");
			if (encoded.length() > 350_000) {
				throw new IllegalArgumentException("Cosmetic texture exceeds the size limit");
			}
			byte[] png = Base64.getDecoder().decode(encoded);
			int[] dimensions = validatePngHeader(png);
			textureData = new TextureData(png, dimensions[0], dimensions[1]);
		}
		JsonArray rawParts = requiredArray(root, "parts");
		Counters counters = new Counters();
		Set<String> names = new HashSet<>();
		Map<String, ModelPart> modelParts = new HashMap<>();
		List<PartPose> restPoses = new ArrayList<>();
		List<ParsedPart> parsed = new ArrayList<>();
		Matrix4f attachTransform = attachmentTransform(root);
		for (JsonElement element : rawParts) {
			if (!element.isJsonObject()) {
				throw new IllegalArgumentException("Cosmetic part must be an object");
			}
			parsed.add(parsePart(element.getAsJsonObject(), counters, names, true,
					new Matrix4f(attachTransform), modelParts, restPoses,
					textureData == null ? MAX_TEXTURE_SIDE : textureData.width(),
					textureData == null ? MAX_TEXTURE_SIDE : textureData.height()));
		}
		List<Animation> animations = parseAnimations(root, modelParts);
		if (counters.parts > MAX_PARTS || counters.cubes > MAX_CUBES) {
			throw new IllegalArgumentException("Cosmetic exceeds model complexity limits");
		}
		if (textureData == null && (slot.equals("head") || slot.equals("back") || slot.equals("shoulder") || slot.equals("torso"))) {
			throw new IllegalArgumentException("Wearable cosmetics require a texture");
		}
		if (counters.maxReach > MAX_REACH) {
			throw new IllegalArgumentException("Cosmetic exceeds the 24-pixel reach limit");
		}
		Map<Bone, List<ModelPart>> attachments = new HashMap<>();
		for (ParsedPart part : parsed) {
			Bone bone = part.bone() == null
					? Bone.parse(requiredString(root, "bone", "head")) : part.bone();
			ModelPart rootPart = attachedRoot(part.model(), root);
			attachments.computeIfAbsent(bone, ignored -> new ArrayList<>()).add(rootPart);
		}
		attachments.replaceAll((bone, models) -> List.copyOf(models));
		return new CosmeticItemDefinition(id, slot, assetKey,
				textureData == null ? null : textureData.png(), attachments, restPoses, animations);
	}

	private static ParsedPart parsePart(JsonObject json, Counters counters, Set<String> names,
										boolean topLevel, Matrix4f parentTransform,
										Map<String, ModelPart> modelParts, List<PartPose> restPoses,
										int textureWidth, int textureHeight) {
		if (++counters.parts > MAX_PARTS) {
			throw new IllegalArgumentException("Cosmetic has too many model parts");
		}
		rejectUnknownFields(json, topLevel
				? Set.of("name", "bone", "pivot", "rotation", "cubes", "children")
				: Set.of("name", "pivot", "rotation", "cubes", "children"), "part");
		String name = requiredString(json, "name");
		if (name.isBlank() || name.startsWith("cosmetic") || !names.add(name)) {
			throw new IllegalArgumentException("Invalid or duplicate cosmetic part name");
		}
		Bone bone = topLevel && json.has("bone") ? Bone.parse(json.get("bone").getAsString()) : null;
		float[] pivot = optionalVector(json, "pivot", new float[] {0, 0, 0});
		float[] rotation = optionalVector(json, "rotation", new float[] {0, 0, 0});
		Matrix4f transform = parentTransform.translate(pivot[0], pivot[1], pivot[2])
				.rotateZYX((float) Math.toRadians(rotation[2]), (float) Math.toRadians(rotation[1]),
						(float) Math.toRadians(rotation[0]));
		List<ModelPart.Cube> cubes = new ArrayList<>();
		JsonArray rawCubes = json.has("cubes") ? asArray(json.get("cubes"), "cubes") : new JsonArray();
		for (JsonElement cubeValue : rawCubes) {
			if (!cubeValue.isJsonObject()) {
				throw new IllegalArgumentException("Cosmetic cube must be an object");
			}
			if (++counters.cubes > MAX_CUBES) {
				throw new IllegalArgumentException("Cosmetic has too many model cubes");
			}
			JsonObject cube = cubeValue.getAsJsonObject();
			cubes.add(parseCube(cube, textureWidth, textureHeight));
			measureReach(cube, transform, counters);
		}
		Map<String, ModelPart> children = new HashMap<>();
		JsonArray rawChildren = json.has("children") ? asArray(json.get("children"), "children") : new JsonArray();
		for (JsonElement childValue : rawChildren) {
			if (!childValue.isJsonObject()) {
				throw new IllegalArgumentException("Cosmetic child must be an object");
			}
			ParsedPart child = parsePart(childValue.getAsJsonObject(), counters, names, false,
					new Matrix4f(transform), modelParts, restPoses,
					textureWidth, textureHeight);
			children.put(child.name(), child.model());
		}
		ModelPart model = new ModelPart(cubes, children);
		model.setPos(pivot[0], pivot[1], pivot[2]);
		model.setRotation((float) Math.toRadians(rotation[0]), (float) Math.toRadians(rotation[1]),
				(float) Math.toRadians(rotation[2]));
		modelParts.put(name, model);
		restPoses.add(new PartPose(model, pivot[0], pivot[1], pivot[2], model.xRot, model.yRot, model.zRot));
		return new ParsedPart(name, bone, model);
	}

	private static List<Animation> parseAnimations(JsonObject root, Map<String, ModelPart> modelParts) {
		if (!root.has("animations")) {
			return List.of();
		}
		JsonArray values = asArray(root.get("animations"), "animations");
		List<Animation> animations = new ArrayList<>();
		for (JsonElement value : values) {
			if (!value.isJsonObject()) {
				throw new IllegalArgumentException("Cosmetic animation must be an object");
			}
			JsonObject json = value.getAsJsonObject();
			rejectUnknownFields(json, Set.of("part", "motion", "axis", "amplitude", "period", "phase"),
					"animation");
			String name = requiredString(json, "part");
			ModelPart modelPart = modelParts.get(name);
			if (modelPart == null) {
				throw new IllegalArgumentException("Cosmetic animation references an unknown part: " + name);
			}
			String motion = requiredString(json, "motion");
			String axis = requiredString(json, "axis");
			if (!Set.of("swing", "bob", "spin").contains(motion) || !Set.of("x", "y", "z").contains(axis)) {
				throw new IllegalArgumentException("Unsupported cosmetic animation motion or axis");
			}
			float amplitude = json.has("amplitude") ? finiteFloat(json.get("amplitude"), "amplitude") : 0;
			float period = finiteFloat(json.get("period"), "period");
			float phase = json.has("phase") ? finiteFloat(json.get("phase"), "phase") : 0;
			float maxAmplitude = switch (motion) {
				case "swing" -> 45;
				case "bob" -> 4;
				case "spin" -> 0;
				default -> throw new IllegalArgumentException("Unsupported cosmetic animation motion");
			};
			if (Math.abs(amplitude) > maxAmplitude || period < 4 || period > 1200) {
				throw new IllegalArgumentException("Cosmetic animation is out of bounds");
			}
			animations.add(new Animation(modelPart, motion, axis, amplitude, period, phase));
		}
		return List.copyOf(animations);
	}

	private static ModelPart.Cube parseCube(JsonObject json, int textureWidth, int textureHeight) {
		rejectUnknownFields(json, Set.of("origin", "size", "uv", "inflate", "mirror"), "cube");
		float[] origin = requiredVector(json, "origin", 3);
		float[] size = requiredVector(json, "size", 3);
		float[] uv = requiredVector(json, "uv", 2);
		if (size[0] < 0 || size[1] < 0 || size[2] < 0) {
			throw new IllegalArgumentException("Cosmetic cube dimensions cannot be negative");
		}
		float inflate = json.has("inflate") ? finiteFloat(json.get("inflate"), "inflate") : 0;
		if (inflate < 0 || inflate > 4) {
			throw new IllegalArgumentException("Cosmetic cube inflate must be between 0 and 4");
		}
		boolean mirror = json.has("mirror") && requiredBoolean(json, "mirror");
		if (uv[0] != Math.round(uv[0]) || uv[1] != Math.round(uv[1])
				|| uv[0] < 0 || uv[1] < 0 || uv[0] + 2 * (size[0] + size[2]) > textureWidth
				|| uv[1] + size[1] + size[2] > textureHeight) {
			throw new IllegalArgumentException("Cosmetic cube UVs exceed the texture bounds");
		}
		int u = (int) uv[0];
		int v = (int) uv[1];
		return new ModelPart.Cube(
				u, v,
				origin[0], origin[1], origin[2], size[0], size[1], size[2],
				inflate, inflate, inflate, mirror,
				textureWidth, textureHeight, EnumSet.allOf(Direction.class));
	}

	private static Matrix4f attachmentTransform(JsonObject item) {
		JsonObject attach = new JsonObject();
		if (item.has("attach")) {
			if (!item.get("attach").isJsonObject()) {
				throw new IllegalArgumentException("Cosmetic attachment must be an object");
			}
			attach = item.getAsJsonObject("attach");
		}
		rejectUnknownFields(attach, Set.of("offset", "rotation", "scale"), "attachment");
		float[] offset = optionalVector(attach, "offset", new float[] {0, 0, 0});
		float[] rotation = optionalVector(attach, "rotation", new float[] {0, 0, 0});
		float scale = attach.has("scale") ? finiteFloat(attach.get("scale"), "attach.scale") : 1.0f;
		if (scale <= 0 || scale > 2) {
			throw new IllegalArgumentException("Cosmetic attachment scale must be in (0, 2]");
		}
		return new Matrix4f().translate(offset[0], offset[1], offset[2])
				.rotateZYX((float) Math.toRadians(rotation[2]), (float) Math.toRadians(rotation[1]),
						(float) Math.toRadians(rotation[0]))
				.scale(scale);
	}

	private static ModelPart attachedRoot(ModelPart part, JsonObject item) {
		JsonObject attach = item.has("attach") ? item.getAsJsonObject("attach") : new JsonObject();
		float[] offset = optionalVector(attach, "offset", new float[] {0, 0, 0});
		float[] rotation = optionalVector(attach, "rotation", new float[] {0, 0, 0});
		float scale = attach.has("scale") ? finiteFloat(attach.get("scale"), "attach.scale") : 1.0f;
		ModelPart wrapper = new ModelPart(List.of(), Map.of("item", part));
		wrapper.setPos(offset[0], offset[1], offset[2]);
		wrapper.setRotation((float) Math.toRadians(rotation[0]), (float) Math.toRadians(rotation[1]),
				(float) Math.toRadians(rotation[2]));
		wrapper.xScale = scale;
		wrapper.yScale = scale;
		wrapper.zScale = scale;
		return wrapper;
	}

	private static void measureReach(JsonObject cube, Matrix4f transform, Counters counters) {
		float[] origin = requiredVector(cube, "origin", 3);
		float[] size = requiredVector(cube, "size", 3);
		float inflate = cube.has("inflate") ? finiteFloat(cube.get("inflate"), "inflate") : 0;
		for (int x = 0; x < 2; x++) {
			for (int y = 0; y < 2; y++) {
				for (int z = 0; z < 2; z++) {
					float cornerX = origin[0] + (x == 0 ? -inflate : size[0] + inflate);
					float cornerY = origin[1] + (y == 0 ? -inflate : size[1] + inflate);
					float cornerZ = origin[2] + (z == 0 ? -inflate : size[2] + inflate);
					Vector3f corner = new Vector3f(cornerX, cornerY, cornerZ).mulPosition(transform);
					counters.maxReach = Math.max(counters.maxReach, corner.length());
				}
			}
		}
	}

	private static void rejectUnknownFields(JsonObject json, Set<String> allowed, String objectName) {
		for (String key : json.keySet()) {
			if (!allowed.contains(key)) {
				throw new IllegalArgumentException("Unknown field in cosmetic " + objectName + ": " + key);
			}
		}
	}

	private static int[] validatePngHeader(byte[] png) {
		byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
		if (png.length < 24) {
			throw new IllegalArgumentException("Cosmetic texture is not a valid PNG");
		}
		for (int i = 0; i < signature.length; i++) {
			if (png[i] != signature[i]) {
				throw new IllegalArgumentException("Cosmetic texture is not a valid PNG");
			}
		}
		int width = readInt(png, 16);
		int height = readInt(png, 20);
		if (width < 1 || height < 1 || width > MAX_TEXTURE_SIDE || height > MAX_TEXTURE_SIDE) {
			throw new IllegalArgumentException("Cosmetic texture dimensions must be between 1 and 64");
		}
		return new int[] {width, height};
	}

	private static int readInt(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFF) << 24 | (bytes[offset + 1] & 0xFF) << 16
				| (bytes[offset + 2] & 0xFF) << 8 | (bytes[offset + 3] & 0xFF);
	}

	private static String requiredString(JsonObject json, String key) {
		return requiredString(json, key, null);
	}

	private static String requiredString(JsonObject json, String key, String fallback) {
		JsonElement value = json.get(key);
		if (value == null) {
			if (fallback != null) {
				return fallback;
			}
			throw new IllegalArgumentException("Cosmetic field is missing: " + key);
		}
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("Cosmetic field is not text: " + key);
		}
		return value.getAsString();
	}

	private static int requiredInt(JsonObject json, String key) {
		JsonElement value = json.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException("Cosmetic field is not an integer: " + key);
		}
		double number = value.getAsDouble();
		if (!Double.isFinite(number) || number != Math.rint(number)
				|| number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Cosmetic field is not an integer: " + key);
		}
		return (int) number;
	}

	private static boolean requiredBoolean(JsonObject json, String key) {
		JsonElement value = json.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException("Cosmetic field is not a boolean: " + key);
		}
		return value.getAsBoolean();
	}

	private static JsonArray requiredArray(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return asArray(value, key);
	}

	private static JsonArray asArray(JsonElement value, String key) {
		if (value == null || !value.isJsonArray()) {
			throw new IllegalArgumentException("Cosmetic field is not a list: " + key);
		}
		return value.getAsJsonArray();
	}

	private static float[] requiredVector(JsonObject json, String key, int dimensions) {
		JsonElement value = json.get(key);
		if (value == null) {
			throw new IllegalArgumentException("Cosmetic vector is missing: " + key);
		}
		return vector(value, key, dimensions);
	}

	private static float[] optionalVector(JsonObject json, String key, float[] fallback) {
		JsonElement value = json.get(key);
		return value == null ? fallback : vector(value, key, fallback.length);
	}

	private static float[] vector(JsonElement value, String key, int dimensions) {
		if (!value.isJsonArray() || value.getAsJsonArray().size() != dimensions) {
			throw new IllegalArgumentException("Cosmetic vector has the wrong dimensions: " + key);
		}
		float[] values = new float[dimensions];
		for (int i = 0; i < dimensions; i++) {
			values[i] = finiteFloat(value.getAsJsonArray().get(i), key);
		}
		return values;
	}

	private static float finiteFloat(JsonElement value, String key) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException("Cosmetic value is not numeric: " + key);
		}
		float result = value.getAsFloat();
		if (!Float.isFinite(result)) {
			throw new IllegalArgumentException("Cosmetic value is not finite: " + key);
		}
		return result;
	}

	public void registerTexture(TextureManager textureManager) {
		if (texturePng == null) {
			throw new IllegalStateException("Cosmetic does not have a renderable texture");
		}
		if (textureId != null) {
			return;
		}
		try {
			NativeImage image = NativeImage.read(texturePng);
			textureId = Identifier.fromNamespaceAndPath("horizon-cosmetics-fabric", "items/" + assetKey);
			textureManager.register(textureId,
					new DynamicTexture(() -> "Horizon cosmetic " + id, image));
		} catch (IOException exception) {
			throw new IllegalArgumentException("Could not decode cosmetic texture", exception);
		}
	}

	public String id() {
		return id;
	}

	public String slot() {
		return slot;
	}

	public Identifier textureId() {
		return textureId;
	}

	public List<ModelPart> parts(Bone bone) {
		return attachedParts.getOrDefault(bone, List.of());
	}

	public void animate(double ticks) {
		if (animations.isEmpty()) {
			return;
		}
		for (PartPose pose : restPoses) {
			pose.part().setPos(pose.x(), pose.y(), pose.z());
			pose.part().setRotation(pose.xRot(), pose.yRot(), pose.zRot());
		}
		for (Animation animation : animations) {
			double turns = ticks / animation.period() + animation.phase();
			float value = switch (animation.motion()) {
				case "swing", "bob" -> (float) (animation.amplitude() * Math.sin(turns * Math.PI * 2));
				case "spin" -> (float) ((turns - Math.floor(turns)) * Math.PI * 2);
				default -> 0;
			};
			switch (animation.axis()) {
				case "x" -> {
					if (animation.motion().equals("bob")) {
						animation.part().x += value;
					} else {
						animation.part().xRot += animation.motion().equals("swing")
								? (float) Math.toRadians(value) : value;
					}
				}
				case "y" -> {
					if (animation.motion().equals("bob")) {
						animation.part().y += value;
					} else {
						animation.part().yRot += animation.motion().equals("swing")
								? (float) Math.toRadians(value) : value;
					}
				}
				case "z" -> {
					if (animation.motion().equals("bob")) {
						animation.part().z += value;
					} else {
						animation.part().zRot += animation.motion().equals("swing")
								? (float) Math.toRadians(value) : value;
					}
				}
			}
		}
	}

	public enum Bone {
		HEAD,
		BODY,
		LEFT_ARM,
		RIGHT_ARM,
		LEFT_LEG,
		RIGHT_LEG;

		static Bone parse(String value) {
			try {
				return Bone.valueOf(value.toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException exception) {
				throw new IllegalArgumentException("Unsupported cosmetic bone: " + value, exception);
			}
		}
	}

	private record ParsedPart(String name, Bone bone, ModelPart model) {
	}

	private record Animation(ModelPart part, String motion, String axis, float amplitude, float period, float phase) {
	}

	private record PartPose(ModelPart part, float x, float y, float z, float xRot, float yRot, float zRot) {
	}

	private record TextureData(byte[] png, int width, int height) {
	}

	private static final class Counters {
		private int parts;
		private int cubes;
		private float maxReach;
	}
}
