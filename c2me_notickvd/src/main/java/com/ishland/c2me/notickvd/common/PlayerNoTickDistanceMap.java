package com.ishland.c2me.notickvd.common;

import com.ishland.c2me.base.common.structs.DynamicPriorityQueue;
import com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTracker;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

public class PlayerNoTickDistanceMap extends ChunkTracker {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final TicketType<ChunkPos> TICKET_TYPE = TicketType.create("c2me_no_tick_vd", Comparator.comparingLong(ChunkPos::toLong));

    private final LongSet sourceChunks = new LongOpenHashSet();
    private final Long2IntOpenHashMap distanceFromNearestPlayer = new Long2IntOpenHashMap();
    private final DynamicPriorityQueue<ChunkPos> pendingTicketAdds = new DynamicPriorityQueue<>(251);
    private final LongOpenHashSet pendingTicketRemoves = new LongOpenHashSet();
    private final LongOpenHashSet managedChunkTickets = new LongOpenHashSet();
    private final ReferenceArrayList<CompletableFuture<Void>> chunkLoadFutures = new ReferenceArrayList<>();

    private final DistanceManager chunkTicketManager;
    private final NoTickSystem noTickSystem;
    private volatile int viewDistance;
    private volatile int pendingTicketUpdatesCount = 0; // for easier access concurrently

    public PlayerNoTickDistanceMap(DistanceManager chunkTicketManager, NoTickSystem noTickSystem) {
        super(251, 16, 256);
        this.chunkTicketManager = chunkTicketManager;
        this.noTickSystem = noTickSystem;
        this.distanceFromNearestPlayer.defaultReturnValue(251);
        this.setViewDistance(12);
    }

    @Override
    protected int getLevelFromSource(long chunkPos) {
        final ObjectSet<ServerPlayer> players = ((com.ishland.c2me.base.mixin.access.IChunkTicketManager) chunkTicketManager).getPlayersByChunkPos().get(chunkPos);
        return players != null && !players.isEmpty() ? 249 - viewDistance : Integer.MAX_VALUE;
    }

    @Override
    protected int getLevel(long chunkPos) {
        return this.distanceFromNearestPlayer.get(chunkPos);
    }

    @Override
    protected void setLevel(long chunkPos, int level) {
        if (level > 249) {
            if (this.distanceFromNearestPlayer.containsKey(chunkPos)) {
                this.pendingTicketRemoves.add(chunkPos);
                this.pendingTicketAdds.remove(new ChunkPos(chunkPos));
                this.distanceFromNearestPlayer.remove(chunkPos);
            }
        } else {
            if (!this.distanceFromNearestPlayer.containsKey(chunkPos)) {
                pendingTicketRemoves.remove(chunkPos);
                pendingTicketAdds.enqueue(new ChunkPos(chunkPos), level);
            }
            pendingTicketAdds.changePriority(new ChunkPos(chunkPos), level);
            this.distanceFromNearestPlayer.put(chunkPos, level);
        }
    }

    public void addSource(ChunkPos chunkPos) {
        this.update(chunkPos.toLong(), 249 - this.viewDistance, true);
        this.sourceChunks.add(chunkPos.toLong());
    }

    public void removeSource(ChunkPos chunkPos) {
        this.update(chunkPos.toLong(), Integer.MAX_VALUE, false);
        this.sourceChunks.remove(chunkPos.toLong());
    }

    public boolean update() {
        final boolean hasUpdates = this.runUpdates(Integer.MAX_VALUE) != Integer.MAX_VALUE;
        this.pendingTicketUpdatesCount = this.pendingTicketAdds.size() + this.pendingTicketRemoves.size();
        return hasUpdates;
    }

    private boolean hasPendingTicketUpdatesAsync = false;

    boolean runPendingTicketUpdates(ChunkMap tacs) {
        final boolean hasUpdatesNow = runPendingTicketUpdatesInternal(tacs);
        final boolean hasUpdatesEarlier = hasPendingTicketUpdatesAsync;
        hasPendingTicketUpdatesAsync = false;
        return hasUpdatesNow || hasUpdatesEarlier;
    }

    private boolean runPendingTicketUpdatesInternal(ChunkMap tacs) {
        boolean hasUpdates = false;
        // remove old tickets
        {
            final LongIterator it = pendingTicketRemoves.longIterator();
            while (it.hasNext()) {
                final long chunkPos = it.nextLong();
                if (this.managedChunkTickets.remove(chunkPos)) {
                    removeTicket0(new ChunkPos(chunkPos));
                    hasUpdates = true;
                }
            }
            pendingTicketRemoves.clear();
        }

        // clean up futures
        this.chunkLoadFutures.removeIf(CompletableFuture::isDone);

        // add new tickets
        while (this.chunkLoadFutures.size() < Config.maxConcurrentChunkLoads) {
            final ChunkPos pos = this.pendingTicketAdds.dequeue();
            if (pos == null) break;
            if (this.managedChunkTickets.add(pos.toLong())) {
                final CompletableFuture<Void> ticketFuture = this.addTicket0(pos);
                this.chunkLoadFutures.add(getChunkLoadFuture(tacs, pos, ticketFuture));
                hasUpdates = true;
            }
        }

        this.pendingTicketUpdatesCount = this.pendingTicketAdds.size() + this.pendingTicketRemoves.size();
        return hasUpdates;
    }

    private void removeTicket0(ChunkPos pos) {
        this.noTickSystem.mainBeforeTicketTicks.add(() -> this.chunkTicketManager.removeTicket(TICKET_TYPE, pos, 33, pos));
    }

    private CompletableFuture<Void> addTicket0(ChunkPos pos) {
        return CompletableFuture.runAsync(() -> this.chunkTicketManager.addTicket(TICKET_TYPE, pos, 33, pos), this.noTickSystem.mainBeforeTicketTicks::add);
    }

    private CompletableFuture<Void> getChunkLoadFuture(ChunkMap tacs, ChunkPos pos, CompletableFuture<Void> ticketFuture) {
        final CompletableFuture<Void> future = ticketFuture.thenComposeAsync(unused -> {
            final ChunkHolder holder = ((IThreadedAnvilChunkStorage) tacs).getCurrentChunkHolders().get(pos.toLong());
            if (holder == null) {
                return CompletableFuture.completedFuture(null);
            } else {
                return holder.getFullChunkFuture().exceptionally(unused1 -> null).thenAccept(unused1 -> {
                });
            }
        }, this.noTickSystem.mainAfterTicketTicks::add);
        future.thenRunAsync(() -> {
            this.chunkLoadFutures.remove(future);
            final boolean hasUpdates = this.runPendingTicketUpdatesInternal(tacs);
            if (hasUpdates) {
                hasPendingTicketUpdatesAsync = true;
            }
        }, this.noTickSystem.executor);
        return future;
    }

    public void setViewDistance(int viewDistance) {
        this.viewDistance = Mth.clamp(viewDistance, 3, 249);
        sourceChunks.forEach((long value) -> {
            removeSource(new ChunkPos(value));
            addSource(new ChunkPos(value));
        });
    }

    public int getPendingTicketUpdatesCount() {
        return this.pendingTicketUpdatesCount;
    }

    public LongSet getChunks() {
        return managedChunkTickets;
    }

}
