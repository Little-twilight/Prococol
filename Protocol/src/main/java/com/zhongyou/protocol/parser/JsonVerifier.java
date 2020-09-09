package com.zhongyou.protocol.parser;

public interface JsonVerifier {

    boolean verify(byte[] data, int start, int length);
}
