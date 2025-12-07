package com.ishland.c2me.threading.worldgen.mixin;

import com.ishland.c2me.base.common.scheduler.ThreadLocalWorldGenSchedulingState;
import com.ishland.c2me.base.common.util.SneakyThrow;
import com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import com.ishland.c2me.opts.chunk_access.common.CurrentWorldGenState;
import com.ishland.c2me.threading.worldgen.common.ChunkStatusUtils;
import com.ishland.c2me.threading.worldgen.common.Config;
import com.ishland.c2me.threading.worldgen.common.IChunkStatus;
import com.ishland.c2me.base.common.scheduler.IVanillaChunkManager;
import com.ishland.c2me.threading.worldgen.common.IWorldGenLockable;
import com.mojang.datafixers.util.Either;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.util.profiling.jfr.callback.ProfiledDuration;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

@Mixin(ChunkStatus.class)
public abstract class MixinChunkStatus implements IChunkStatus {

    private static final Logger LOGGER = LoggerFactory.getLogger(MixinChunkStatus.class);

    @Shadow
    @Final
    private ChunkStatus.GenerationTask generationTask;

    @Shadow
    @Final
    private int range;

    @Shadow
    public static List<ChunkStatus> getStatusList() {
        throw new AbstractMethodError();
    }

    @Shadow public abstract String toString();

    private int reducedTaskRadius = -1;

    public void calculateReducedTaskRadius() {
        if (this.range == 0) {
            this.reducedTaskRadius = 0;
        } else {
            for (int i = 0; i <= this.range; i++) {
                final ChunkStatus status = ChunkStatus.getStatusAroundFullChunk(ChunkStatus.getDistance((ChunkStatus) (Object) this) + i); // TODO [VanillaCopy] from TACS getRequiredStatusForGeneration
                if (status.getIndex() <= ChunkStatus.BIOMES.getIndex()) {
                    this.reducedTaskRadius = Math.min(this.range, Math.max(0, i - 1));
                    break;
                }
            }
        }
        //noinspection ConstantConditions
        if ((Object) this == ChunkStatus.LIGHT) {
            this.reducedTaskRadius = 1;
        }
        LOGGER.info("{} task radius: {} -> {}", this, this.range, this.reducedTaskRadius);
    }

    @Override
    public int getReducedTaskRadius() {
        return this.reducedTaskRadius;
    }

    @Dynamic
    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void onCLInit(CallbackInfo info) {
        for (ChunkStatus chunkStatus : getStatusList()) {
            ((IChunkStatus) chunkStatus).calculateReducedTaskRadius();
        }
    }

    /**
     * @author ishland
     * @reason take over generation
     */
    @Overwrite
    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> generate(Executor executor, ServerLevel world, ChunkGenerator chunkGenerator, StructureTemplateManager structureManager, ThreadedLevelLightEngine lightingProvider, Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> function, List<ChunkAccess> list) {
        final ChunkStatus thiz = (ChunkStatus) (Object) this;
        final ChunkAccess targetChunk = list.get(list.size() / 2);

        ProfiledDuration finishable = JvmProfiler.INSTANCE.onChunkGenerate(targetChunk.getPos(), world.dimension(), this.toString());

        final Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> generationTask = () -> {
            try {
                CurrentWorldGenState.setCurrentRegion(new WorldGenRegion(world, list, thiz, -1));
                return this.generationTask.doWork(thiz, executor, world, chunkGenerator, structureManager, lightingProvider, function, list, targetChunk);
            } finally {
                CurrentWorldGenState.clearCurrentRegion();
            }
        };

        final CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completableFuture;

        if (targetChunk.getStatus().isOrAfter(thiz)) {
            completableFuture = generationTask.get();
        } else {
            final ChunkHolder holder = ThreadLocalWorldGenSchedulingState.getChunkHolder();
            final ChunkMap tacs = world.getChunkSource().chunkMap;
            if (holder != null && ChunkStatusUtils.isCancelled(holder, thiz)) {
                completableFuture = ChunkHolder.UNLOADED_CHUNK_FUTURE;
                ((IThreadedAnvilChunkStorage) tacs).invokeReleaseLightTicket(targetChunk.getPos()); // vanilla behavior
//                System.out.println(String.format("%s: %s is already done or cancelled, skipping generation", this, targetChunk.getPos()));
            } else {
                int lockRadius = Config.reduceLockRadius && this.reducedTaskRadius != -1 ? this.reducedTaskRadius : this.range;
                //noinspection ConstantConditions
                completableFuture = ChunkStatusUtils.runChunkGenWithLock(
                                targetChunk.getPos(),
                                thiz,
                                holder,
                                lockRadius,
                                ((IVanillaChunkManager) tacs).c2me$getSchedulingManager(),
                        (Object) this == ChunkStatus.LIGHT, // lighting is async so don't hold the slot TODO make this check less dirty
                                ((IWorldGenLockable) world).getWorldGenChunkLock(),
                                () -> ChunkStatusUtils.getThreadingType(thiz).runTask(((IWorldGenLockable) world).getWorldGenSingleThreadedLock(), generationTask))
                        .exceptionally(t -> {
                            Throwable actual = t;
                            while (actual instanceof CompletionException) actual = t.getCause();
                            if (actual instanceof CancellationException) {
                                return ChunkHolder.UNLOADED_CHUNK;
                            } else {
                                SneakyThrow.sneaky(t);
                                return null; // unreachable
                            }
                        });

            }
        }

        completableFuture.exceptionally(throwable -> {
            LOGGER.error("Error during chunk generation for {}", targetChunk.getPos(), throwable);
            return null;
        });

        // TODO [VanillaCopy]
        return completableFuture.thenApply(either -> {
            if (either.left().isPresent()) {
                if (either.left().get() instanceof ProtoChunk protoChunk && !protoChunk.getStatus().isOrAfter(thiz)) {
                    protoChunk.setStatus(thiz);
                }
            }

            if (finishable != null) {
                finishable.finish();
            }
            return either;
        });
    }

}
