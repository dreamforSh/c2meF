package com.ishland.c2me.fixes.worldgen.threading_issues.common;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.minecraft.world.level.levelgen.structure.structures.WoodlandMansionPieces;

public class ConcurrentFlagMatrix extends WoodlandMansionPieces.SimpleGrid {

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    public ConcurrentFlagMatrix(int n, int m, int fallback) {
        super(n, m, fallback);
    }

    @Override
    public void set(int i, int j, int value) {
        rwLock.writeLock().lock();
        try {
            super.set(i, j, value);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void set(int i0, int j0, int i1, int j1, int value) {
        rwLock.writeLock().lock();
        try {
            super.set(i0, j0, i1, j1, value);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public int get(int i, int j) {
        rwLock.readLock().lock();
        try {
            return super.get(i, j);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void setif(int i, int j, int expected, int newValue) {
        rwLock.writeLock().lock();
        try {
            if (super.get(i, j) == expected) {
                super.set(i, j, newValue);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public boolean edgesTo(int i, int j, int value) {
        rwLock.readLock().lock();
        try {
            return super.edgesTo(i, j, value);
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
