package com.zhongyou.protocol.parser;

public class AbsParserBuffer {
    private final byte[] mBuffer;
    private final int mBufferSize;
    private int mCachedBytesRangeStart = 0;
    private int mCachedBytesRangeSize = 0;

    public AbsParserBuffer(int bufferSize) {
        mBufferSize = bufferSize;
        mBuffer = new byte[bufferSize];
    }

    public int getBufferSize() {
        return mBufferSize;
    }

    public int getCachedBytes() {
        return mCachedBytesRangeSize;
    }

    public int getAvailableSpace() {
        return mBufferSize - mCachedBytesRangeSize;
    }

    public void offer(byte[] data, int start, int length) {
        int availableSpace = mBufferSize - mCachedBytesRangeSize;
        if (availableSpace < length) {
            throw new RuntimeException(String.format("Parser buffer overflow! Buffer size %1d,available space%2d,receive %3d", mBufferSize, availableSpace, length));
        }
        if (length == 0) {
            return;
        }
        int cachedBytesRangeEnd = mCachedBytesRangeStart + mCachedBytesRangeSize;
        if (cachedBytesRangeEnd >= mBufferSize) {
            cachedBytesRangeEnd -= mBufferSize;
        }
        int newRangeStart = cachedBytesRangeEnd;
        int newRangeEnd = newRangeStart + length;

        if (newRangeEnd > mBufferSize) {
            int wind = newRangeEnd - mBufferSize;
            System.arraycopy(data, start, mBuffer, newRangeStart, length - wind);
            System.arraycopy(data, start + length - wind, mBuffer, 0, wind);
        } else {
            System.arraycopy(data, start, mBuffer, newRangeStart, length);
        }
        mCachedBytesRangeSize += length;
    }

    public void consume(int consumption, byte[] container, int start) {
        if (consumption < 0 || consumption > mCachedBytesRangeSize) {
            throw new RuntimeException(String.format("Invalid bytes consumption:%1d while available range [0,%2d]", consumption, mCachedBytesRangeSize));
        }
        int newCachedBytesRangeStart = mCachedBytesRangeStart + consumption;
        if (newCachedBytesRangeStart >= mBufferSize) {
            int wind = newCachedBytesRangeStart - mBufferSize;
            System.arraycopy(mBuffer, mCachedBytesRangeStart, container, start, consumption - wind);
            System.arraycopy(mBuffer, 0, container, start + consumption - wind, wind);
            mCachedBytesRangeStart = wind;
        } else {
            System.arraycopy(mBuffer, mCachedBytesRangeStart, container, start, consumption);
            mCachedBytesRangeStart = newCachedBytesRangeStart;
        }
        mCachedBytesRangeSize -= consumption;
    }

    public void peek(int peekStart, int peek, byte[] container, int start) {
        if (peekStart < 0 || peekStart + peek > mCachedBytesRangeSize) {
            throw new RuntimeException(String.format("Invalid bytes peek:[%1d,%2d) while available range [0,%3d)", peekStart, peekStart + peek, mCachedBytesRangeSize));
        }
        int peekRangeStart = mCachedBytesRangeStart + peekStart;
        if (peekRangeStart >= mBufferSize) {
            peekRangeStart -= mBufferSize;
        }
        int peekRangeEnd = peekRangeStart + peek;
        if (peekRangeEnd >= mBufferSize) {
            int wind = peekRangeEnd - mBufferSize;
            System.arraycopy(mBuffer, peekRangeStart, container, start, peek - wind);
            System.arraycopy(mBuffer, 0, container, start + peek - wind, wind);
        } else {
            System.arraycopy(mBuffer, peekRangeStart, container, start, peek);
        }
    }

    public void skip(int skip) {
        if (skip < 0 || skip > mCachedBytesRangeSize) {
            throw new RuntimeException(String.format("Invalid bytes skip:%1d while available range [0,%2d]", skip, mCachedBytesRangeSize));
        }
        mCachedBytesRangeStart += skip;
        if (mCachedBytesRangeStart >= mBufferSize) {
            mCachedBytesRangeStart -= mBufferSize;
        }
        mCachedBytesRangeSize -= skip;
    }

    public void clear() {
        mCachedBytesRangeSize = 0;
    }

}
