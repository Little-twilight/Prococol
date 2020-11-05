package com.macfred.protocol.parser;

public interface PacketReceiver {

    void onNewPacket(byte[] bytes, int start, int length);
}
