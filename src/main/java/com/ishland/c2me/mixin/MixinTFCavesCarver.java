package com.ishland.c2me.mixin;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import twilightforest.world.components.TFCavesCarver;

import java.util.function.Function;

@Mixin(value = TFCavesCarver.class, remap = false)
public class MixinTFCavesCarver {

    @Unique
    private final ThreadLocal<RandomSource> c2me$carverRandom = new ThreadLocal<>();

    @Inject(method = "carve", at = @At("HEAD"))
    private void onCarveHead(CarvingContext context, CaveCarverConfiguration config, ChunkAccess chunk, Function biomeAccess, RandomSource random, Aquifer aquifer, net.minecraft.world.level.ChunkPos chunkPos, net.minecraft.world.level.chunk.CarvingMask carvingMask, CallbackInfoReturnable<Boolean> cir) {
        this.c2me$carverRandom.set(random);
    }

    @Inject(method = "carve", at = @At("RETURN"))
    private void onCarveReturn(CarvingContext context, CaveCarverConfiguration config, ChunkAccess chunk, Function biomeAccess, RandomSource random, Aquifer aquifer, net.minecraft.world.level.ChunkPos chunkPos, net.minecraft.world.level.chunk.CarvingMask carvingMask, CallbackInfoReturnable<Boolean> cir) {
        this.c2me$carverRandom.remove();
    }

    @Redirect(method = "carveBlock", at = @At(value = "FIELD", target = "Ltwilightforest/world/components/TFCavesCarver;rand:Lnet/minecraft/util/RandomSource;"))
    private RandomSource redirectCarveBlockRand(TFCavesCarver instance) {
        final RandomSource randomSource = this.c2me$carverRandom.get();
        if (randomSource == null) {
            throw new IllegalStateException("c2me$carverRandom is null, this should not happen");
        }
        return randomSource;
    }

}
