package com.macfred.protocol.parser;


import com.macfred.util.ref.BiRef;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class JsonUnitCapture {
    private static final byte BRACE_LEFT = '{';
    private static final byte BRACE_RIGHT = '}';
    private static final byte BRACKET_LEFT = '[';
    private static final byte BRACKET_RIGHT = ']';

    private final String mCharset;


    public static void main(String... args) throws UnsupportedEncodingException {
//        byte[] bytes = "{".getBytes();
//        System.out.println(Hex.encodeHex(bytes));
//        System.out.println(new Gson().toJson(new AAA(), AAA.class));
//        String str = "{\"id\":\"123\",name:\"myName\"}{id:\"456\",name:\"yetanotherName\"}{id:\"456\",name:\"anotherName\"}";
//        String[] strs = str.split("(?<=\\})(?=\\{)");
//        for (String s : strs) {
//            System.out.println(s);
//        }
        JsonUnitCapture jsonUnitCapture = new JsonUnitCapture("UTF-8", 8000);
        String str = "{\"id\":\"123\",name:\"myName\"}{id:\"456\",name:\"yetanotherName\"}{id:\"456\",name:\"anotherName\"}";
        byte[] data = str.getBytes(jsonUnitCapture.getCharset());
        List<byte[]> parsed = jsonUnitCapture.parse(data, 0, data.length, null);
        System.out.println(parsed.size());
        System.out.println(jsonUnitCapture.getDataLength());
        for (byte[] bytes : parsed) {
            System.out.println(new String(bytes));
        }
    }

    private final byte[] mBuffer;
    private final int mBufferCapacity;
    private int mDataStart;
    private int mDataLength;
    private int mCurrentScannedLength;
    private List<JsonObject> mJsonCandidatesInScan = new CopyOnWriteArrayList<>();

    public JsonUnitCapture(String charset, int bufferCapacity) {
        mCharset = charset;
        mBuffer = new byte[bufferCapacity];
        mBufferCapacity = bufferCapacity;
        reset();
    }

    public void reset() {
        mDataStart = 0;
        mDataLength = 0;
        mCurrentScannedLength = 0;
        mJsonCandidatesInScan.clear();
    }

    public List<byte[]> parse(byte[] data, int start, int length, JsonVerifier verifier) {
        List<byte[]> jsonParsed = new ArrayList<>();
        if (length <= 0) {
            return jsonParsed;
        }
        int localStart = start;
        int localLength = length;
        while (localLength > 0) {
            int bytesCopied = copyDataToBuffer(data, localStart, localLength);
            localStart += bytesCopied;
            localLength -= bytesCopied;
            if (bytesCopied > 0) {
                scanMultipleJsonBuffer(verifier, jsonParsed);
            }
        }
        return jsonParsed;
    }

    /**
     * @return bytes count copied
     */
    public int copyDataToBuffer(byte[] data, int start, int length) {
        int capacityRemains = mBufferCapacity - mDataLength;
        int copy = Math.min(capacityRemains, length);
        if (copy <= 0) {
            return copy;
        }
        int previousTail = (mDataStart + mDataLength) % mBufferCapacity;
        int newTail = (previousTail + copy) % mBufferCapacity;
        if (newTail >= previousTail) {
            System.arraycopy(data, start, mBuffer, previousTail, copy);
        } else {
            int split = mBufferCapacity - previousTail;
            if (split > 0) {
                System.arraycopy(data, start, mBuffer, previousTail, split);
            }
            System.arraycopy(data, start + split, mBuffer, 0, copy - split);
        }
        mDataLength += copy;
        return copy;
    }

    /**
     * @return json count parse
     */
    public int scanMultipleJsonBuffer(@Nullable JsonVerifier verifier, List<byte[]> container) {
        int count = 0;
        while (mDataLength > 0 && mCurrentScannedLength < mDataLength) {
            byte[] parsedJson = scanSingleJsonBuffer(verifier);
            if (parsedJson != null) {
                count++;
                container.add(parsedJson);
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * @return json captured or null when buffer data end encountered
     */
    public byte[] scanSingleJsonBuffer(@Nullable JsonVerifier verifier) {
        while (mDataLength > 0 && mCurrentScannedLength < mDataLength) {
            int currentScanPosition = mCurrentScannedLength++;
            byte value = mBuffer[(mDataStart + currentScanPosition) % mBufferCapacity];
            for (JsonObject candidate : mJsonCandidatesInScan) {
                candidate.scan(value, currentScanPosition);
                BiRef<JsonObject, byte[]> capture = null;
                if (verifier == null) {
                    if (candidate.getStatus() == JsonObjectStatus.Completed && !(candidate instanceof JsonObjectArray)) {
                        byte[] copy = extractJsonFromBuffer(candidate);
                        String str = new String(copy, Charset.forName(mCharset));
                        try {
                            com.google.gson.JsonParser parser = new com.google.gson.JsonParser();
                            parser.parse(str);
                            capture = BiRef.create(candidate, copy);
                        } catch (Exception e) {
                            // not a valid json
                        }
                    }
                } else {
                    capture = tryCaptureJson(candidate, verifier);
                }
                if (capture != null) {
                    mJsonCandidatesInScan.clear();
                    int trim = capture.getFirst().mTailPosition + 1;
                    mDataStart = (mDataStart + trim) % mBufferCapacity;
                    mDataLength -= trim;
                    mCurrentScannedLength -= trim;
                    return capture.getSecond();
                }
            }
            if (value == BRACE_LEFT) {
                JsonObject candidate = new JsonObject();
                candidate.mHeadPosition = currentScanPosition;
                candidate.mStatus = JsonObjectStatus.WaitForEndPosition;
                mJsonCandidatesInScan.add(candidate);
            }
        }
        if (mDataLength == mBufferCapacity) {
            //缓冲区已满，这个未完成的json得抛弃了
            if (!mJsonCandidatesInScan.isEmpty()) {
                int minTrim = -1;
                for (JsonObject candidate : mJsonCandidatesInScan) {
                    if (candidate.mHeadPosition == 0) {
                        //drop it
                        mJsonCandidatesInScan.remove(candidate);
                    } else {
                        int trim = candidate.mHeadPosition;
                        if (minTrim >= 0) {
                            minTrim = Math.min(trim, minTrim);
                        } else {
                            minTrim = trim;
                        }
                    }
                }
                if (minTrim > 0) {
                    mDataStart = (mDataStart + minTrim) % mBufferCapacity;
                    mDataLength -= minTrim;
                    mCurrentScannedLength -= minTrim;
                    for (JsonObject candidate : mJsonCandidatesInScan) {
                        candidate.trim(minTrim);
                    }
                }
            } else {
                reset();
            }
        }
        return null;
    }

    private BiRef<JsonObject, byte[]> tryCaptureJson(JsonObject jsonObject, @Nonnull JsonVerifier jsonVerifier) {
        if (jsonObject.getStatus() == JsonObjectStatus.Completed) {
            byte[] copy = extractJsonFromBuffer(jsonObject);
            if (jsonVerifier.verify(copy, 0, copy.length)) {
                return BiRef.create(jsonObject, copy);
            }
        }
        for (JsonObject innerJsonObject : jsonObject.mInnerObjects) {
            if (innerJsonObject.getStatus() != JsonObjectStatus.Completed || innerJsonObject instanceof JsonObjectArray) {
                continue;
            }
            BiRef<JsonObject, byte[]> capture = tryCaptureJson(innerJsonObject, jsonVerifier);
            if (capture != null) {
                return capture;
            }
        }
        return null;
    }

    private byte[] extractJsonFromBuffer(JsonObject jsonObject) {
        JsonObjectStatus jsonObjectStatus = jsonObject.getStatus();
        if (jsonObjectStatus != JsonObjectStatus.Completed) {
            throw new RuntimeException(String.format("Trying to extract json object from buffer which is in status: %s", jsonObjectStatus));
        }
        int headPosition = jsonObject.mHeadPosition;
        int tailPosition = jsonObject.mTailPosition;
        int jsonBytesCount = tailPosition + 1 - headPosition;
        byte[] copy = new byte[jsonBytesCount];
        int headInBuffer = (mDataStart + headPosition) % mBufferCapacity;
        int tailInBuffer = (headInBuffer + jsonBytesCount) % mBufferCapacity;//此处指tail + 1，与JsonObject结构中不同
        if (tailInBuffer >= headInBuffer) {
            System.arraycopy(mBuffer, headInBuffer, copy, 0, copy.length);
        } else {
            int split = mBufferCapacity - headInBuffer;
            if (split > 0) {
                System.arraycopy(mBuffer, headInBuffer, copy, 0, split);
            }
            System.arraycopy(mBuffer, (headInBuffer + split) % mBufferCapacity, copy, split, copy.length - split);
        }
        return copy;
    }

    public int getBufferCapacity() {
        return mBufferCapacity;
    }

    public int getDataLength() {
        return mDataLength;
    }

    public int getRemainingCapacity() {
        return mBufferCapacity - mDataLength;
    }

    public String getCharset() {
        return mCharset;
    }

    private static class JsonObject {
        protected final byte mHeadValue;
        protected final byte mTailValue;
        protected int mHeadPosition = -1;  //此处的position指在buffer中已缓存字节中的序列位置，而不是在buffer数组位置
        protected int mTailPosition = -1;
        protected List<JsonObject> mInnerObjects = new ArrayList<>();
        protected JsonObjectStatus mStatus = JsonObjectStatus.WaitForStartPosition;

        private JsonObject() {
            this(BRACE_LEFT, BRACE_RIGHT);
        }

        private JsonObject(byte headValue, byte tailValue) {
            mHeadValue = headValue;
            mTailValue = tailValue;
        }

        private void scan(byte value, int position) {
            JsonObjectStatus previousStatus = mStatus;
            switch (previousStatus) {
                case WaitForEndPosition:
                    if (value == mTailValue) {
                        mTailPosition = position;
                        mStatus = JsonObjectStatus.Completed;
                        break;
                    }
                    if (value == BRACE_LEFT) {
                        JsonObject jsonObject = new JsonObject();
                        jsonObject.mHeadPosition = position;
                        jsonObject.mStatus = JsonObjectStatus.WaitForEndPosition;
                        mInnerObjects.add(jsonObject);
                        mStatus = JsonObjectStatus.WaitForFieldToComplete;
                    } else if (value == BRACKET_LEFT) {
                        JsonObjectArray jsonObjectArray = new JsonObjectArray();
                        jsonObjectArray.mHeadPosition = position;
                        jsonObjectArray.mStatus = JsonObjectStatus.WaitForEndPosition;
                        mInnerObjects.add(jsonObjectArray);
                        mStatus = JsonObjectStatus.WaitForFieldToComplete;
                    }
                    break;
                case WaitForFieldToComplete:
                    JsonObject jsonObject = mInnerObjects.get(mInnerObjects.size() - 1);
                    jsonObject.scan(value, position);
                    if (jsonObject.getStatus() == JsonObjectStatus.Completed) {
                        mStatus = JsonObjectStatus.WaitForEndPosition;
                    }
                    break;
                case Completed:
                    break;
                default:
                    throw new RuntimeException(String.format("Unsupported status: %s", previousStatus));
            }
        }

        private void trim(int trim) {
            if (mHeadPosition >= 0) {
                mHeadPosition -= trim;
            }
            if (mTailPosition >= 0) {
                mTailPosition -= trim;
            }
            for (JsonObject jsonObject : mInnerObjects) {
                jsonObject.trim(trim);
            }
        }

        JsonObjectStatus getStatus() {
            return mStatus;
        }

    }

    private static class JsonObjectArray extends JsonObject {

        private JsonObjectArray() {
            super(BRACKET_LEFT, BRACKET_RIGHT);
        }
    }

    private enum JsonObjectStatus {
        WaitForStartPosition,
        WaitForFieldToComplete,
        WaitForEndPosition,
        Completed
    }

//    public List<String> parse(byte[] data, int start, int length) {
//        List<String> jsonParsed = new ArrayList<>();
//        int capacityRemains = mBufferCapacity - mDataLength;
//        int copy = Math.min(capacityRemains, length);
//        if (copy <= 0) {
//            return jsonParsed;
//        }
//        int newTail = (mDataStart + mDataLength + copy) % mBufferCapacity;
//        if (newTail >= length) {
//            System.arraycopy(data, start, mBuffer, mDataStart + mDataLength, copy);
//        } else {
//
//        }
//        mDataLength += copy;
//
//        start += copy;
//        length -= copy;
//        if (length > 0) {
//            List<String> jsonParsedRecursive = parse(data, start, length);
//            if (!jsonParsedRecursive.isEmpty()) {
//                jsonParsed.addAll(jsonParsedRecursive);
//            }
//        }
//        return jsonParsed;
//    }


//    public static class AAA {
//        //        @SerializedName("ii{\"i")
//        private String ddd = "{}";
//    }
//
//    public static class Body {
//        private String mType;
//        private String mName;
//
//        public Body(String type, String name) {
//            mType = type;
//            mName = name;
//        }
//
//        public String getType() {
//            return mType;
//        }
//
//        public void setType(String type) {
//            mType = type;
//        }
//
//        public String getName() {
//            return mName;
//        }
//
//        public void setName(String name) {
//            mName = name;
//        }
//    }
//
//
//    private static class UTF8 {
//        private static final byte HEADER_1_BYTE_FLAG = /*     */ (byte) 0x10000000;
//        private static final byte HEADER_2_BYTE_FLAG = /*     */ (byte) 0x11000000;
//        private static final byte HEADER_3_BYTE_FLAG = /*     */ (byte) 0x11100000;
//        private static final byte HEADER_4_BYTE_FLAG = /*     */ (byte) 0x11110000;
//        private static final byte HEADER_5_BYTE_FLAG = /*     */ (byte) 0x11111000;
//        private static final byte HEADER_6_BYTE_FLAG = /*     */ (byte) 0x11111100;
//        private static final byte BODY_MULTIPLE_BYTE_FLAG = /**/ (byte) 0x10000000;
//
//        private static final byte HEADER_1_BYTE_FLAG_MASK = /*     */ (byte) 0x10000000;
//        private static final byte HEADER_2_BYTE_FLAG_MASK = /*     */ (byte) 0x11100000;
//        private static final byte HEADER_3_BYTE_FLAG_MASK = /*     */ (byte) 0x11110000;
//        private static final byte HEADER_4_BYTE_FLAG_MASK = /*     */ (byte) 0x11111000;
//        private static final byte HEADER_5_BYTE_FLAG_MASK = /*     */ (byte) 0x11111100;
//        private static final byte HEADER_6_BYTE_FLAG_MASK = /*     */ (byte) 0x11111110;
//        private static final byte BODY_MULTIPLE_BYTE_FLAG_MASK = /**/ (byte) 0x11000000;
//
//    }

}
