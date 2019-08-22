package com.zhongyou.protocol.parser;

public interface PacketReceiver {

    void onNewPacket(byte[] bytes, int start, int length);
}
