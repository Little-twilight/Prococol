package com.zhongyou.protocol.parser;

import com.zhongyou.protocol.PacketDescriptor;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class AdvancedParser {
    private final AbsParser mAbsParser;
    private CongestionPolicy mCongestionPolicy;
    private ReadWriteLock mLock = new ReentrantReadWriteLock();
    private Semaphore mSemaphore = new Semaphore(0);
    private PacketReceiver mPacketReceiver;

    public AdvancedParser(int bufferSize, PacketDescriptor packetDescriptor) {
        mAbsParser = new AbsParser(bufferSize, packetDescriptor);
        mCongestionPolicy = CongestionPolicy.BlockAndWait;
        mSemaphore.release(bufferSize);
    }

    public void receive(byte[] data, int start, int length) {
        if (mCongestionPolicy == CongestionPolicy.BlockAndWait) {
            try {
                mSemaphore.acquire(length);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            while (!mSemaphore.tryAcquire(length)) {
                try {
                    mLock.readLock().lock();
                    int availableSpace = mAbsParser.getBuffer().getAvailableSpace();
                    if (availableSpace < length) {
                        int drop = length - availableSpace;
                        switch (mCongestionPolicy) {
                            case DropOldest:
                                try {
                                    mLock.writeLock().lock();
                                    mAbsParser.getBuffer().skip(drop);
                                    mSemaphore.release(drop);
                                } finally {
                                    mLock.writeLock().unlock();
                                }
                                break;
                            case DropLatestHead:
                                start += drop;
                            case DropLatestTail:
                            default:
                                length -= drop;
                                break;
                        }
                    }
                } finally {
                    mLock.readLock().unlock();
                }
            }
        }
        try {
            mLock.writeLock().lock();
            mAbsParser.receive(data, start, length);
        } finally {
            mLock.writeLock().unlock();
        }
    }

    private void capturePackets() {
        for (; ; ) {
            AbsParser.ProcessReport report;
            mLock.writeLock().lock();
            try {
                report = mAbsParser.tryParsePackets(5);
                mSemaphore.release(report.consumedBytes + report.wastedBytes);
            } finally {
                mLock.writeLock().unlock();
            }
            if (report.newPacketsFound.size() == 0) {
                return;
            }
            if (mPacketReceiver != null) {
                for (byte[] packet : report.newPacketsFound) {
                    mPacketReceiver.onNewPacket(packet, 0, packet.length);
                }
            }
        }
    }

    public void setPacketReceiver(PacketReceiver packetReceiver) {
        mPacketReceiver = packetReceiver;
    }


    public enum CongestionPolicy {
        DropOldest, DropLatestHead, DropLatestTail, BlockAndWait
    }


}
