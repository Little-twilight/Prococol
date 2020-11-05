package com.macfred.protocol.parser;

public interface JsonVerifier {

    boolean verify(byte[] data, int start, int length);
}
