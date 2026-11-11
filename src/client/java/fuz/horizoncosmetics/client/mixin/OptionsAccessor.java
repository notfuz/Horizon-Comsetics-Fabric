package fuz.horizoncosmetics.client.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Options.class)
public interface OptionsAccessor {
	@Accessor("keyMappings")
	KeyMapping[] horizonCosmetics$getKeyMappings();

	@Mutable
	@Accessor("keyMappings")
	void horizonCosmetics$setKeyMappings(KeyMapping[] keyMappings);
}
