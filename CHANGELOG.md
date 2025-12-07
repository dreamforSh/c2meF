# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased] - 2025-12-05

### Fixed
#### Thread Safety Fixes
- **Fixed race condition in `ConcurrentFlagMatrix.setif()`**
  - Location: `c2me_fixes_worldgen_threading_issues/.../ConcurrentFlagMatrix.java`
  - Issue: Race condition between read and write locks in `setif()` method
  - Solution: Use write lock throughout the entire operation for atomicity

- **Prevented potential deadlocks in lock acquisition**
  - Location: `c2me_base/.../AsyncCombinedLock.java`, `SchedulingAsyncCombinedLock.java`
  - Issue: Unordered lock acquisition could cause deadlocks
  - Solution: Sort lock names to ensure consistent acquisition order

- **Fixed infinite recursion in error handling**
  - Location: `AsyncCombinedLock.java`, `SchedulingAsyncCombinedLock.java`
  - Issue: Recursive calls in error path could cause stack overflow
  - Solution: Throw `IllegalStateException` instead of recursing

- **Enhanced object pool safety**
  - Location: `c2me_base/.../SimpleObjectPool.java`
  - Added: Null checks and object state cleanup before return to pool

### Changed
#### Error Handling Improvements
- **Replaced printStackTrace() with proper logging**
  - Location: `SchedulingAsyncCombinedLock.java`, `NeighborLockingTask.java`, `NoTickSystem.java`, `MixinChunkStatus.java`, `C2MEMod.java`
  - Changed: Use SLF4J Logger instead of stack trace printing / System.out.printf for better log management

- **Added ForkJoinPool compatibility**
  - Location: `c2me_fixes_chunkio_threading_issues/.../SynchronizedCodec.java`
  - Added: `ManagedBlocker` support to prevent ForkJoinPool thread starvation

### Performance
#### Chunk Generation Speed Optimizations
- **Aggressive spin-wait strategy for ultra-low latency**
  - Location: `c2me_base/.../CFUtil.java`
  - Optimization: 10,000 pure spins + 1ns park to minimize thread context switching
  - Effect: Minimized task completion wait time

- **High-throughput I/O thread optimization**
  - Location: `c2me_rewrites_chunkio/.../C2MEStorageThread.java`
  - Optimization: 10,000 spins + 100ns park, optimized read request processing
  - Effect: Maximized disk I/O throughput

- **Scheduler batch processing optimization**
  - Location: `c2me_base/.../SchedulingManager.java`
  - Optimization: Removed unused timing code, immediate rescheduling in finally block, batch task scheduling
  - Effect: Keep scheduler running at full capacity

- **Priority queue fast path**
  - Location: `c2me_base/.../DynamicPriorityQueue.java`
  - Added: `isEmpty()` method for O(1) fast checking
  - Effect: Reduced queue checking overhead

- **Optimized lock checking in NeighborLockingTask**
  - Location: `c2me_base/.../NeighborLockingTask.java`
  - Optimization: Use indexed for loop instead of enhanced for loop, cache array length
  - Effect: Reduced allocation and improved loop performance

- **Optimized priority distance calculation**
  - Location: `c2me_base/.../SchedulingManager.java`
  - Optimization: Use long-based Chebyshev distance calculation instead of creating ChunkPos objects
  - Effect: Reduced object allocation during priority updates

