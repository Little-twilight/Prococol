package com.zhongyou.protocol;

public interface PacketDescriptor {
    int getPacketMaxSize();

    FieldDescription getHeaderDescription();

    boolean verifyHeader(byte[] bytes, int start);

    FieldDescription getPacketSizeDescription();

    int decodePacketSize(byte[] bytes, int start);

    boolean verifyAndAcceptPacket(byte[] bytes, int start, int length);

}
