package com.macfred.protocol.parser;


import com.macfred.protocol.FieldDescription;
import com.macfred.protocol.PacketDescriptor;

import java.util.ArrayList;
import java.util.List;

public class AbsParser {
	private final AbsParserBuffer mBuffer;
	private final PacketDescriptor mPacketDescriptor;
	private final FieldDescription mHeaderDescription;
	private final FieldDescription mPacketSizeDescription;
	private final byte[] mPacketProbe;
	private int mPacketSize;
	private Status mStatus;

	public AbsParser(int bufferSize, PacketDescriptor packetDescriptor) {
		mBuffer = new AbsParserBuffer(bufferSize);
		mPacketDescriptor = packetDescriptor;
		mHeaderDescription = packetDescriptor.getHeaderDescription();
		mPacketSizeDescription = packetDescriptor.getPacketSizeDescription();
		mPacketProbe = new byte[packetDescriptor.getPacketMaxSize()];
		mStatus = Status.WaitForHeader;
		mPacketSize = 0;
	}

	public void receive(byte[] data, int start, int length) {
		mBuffer.offer(data, start, length);
	}

	public AbsParserBuffer getBuffer() {
		return mBuffer;
	}

	public ProcessReport tryParsePackets(int captureLimit) {
		ProcessReport report = new ProcessReport();
		while (true) {
			switch (mStatus) {
				case WaitForHeader:
					while (mBuffer.getCachedBytes() >= mHeaderDescription.length) {
						mBuffer.peek(0, mHeaderDescription.length, mPacketProbe, 0);
						if (mPacketDescriptor.verifyHeader(mPacketProbe, 0)) {
							mStatus = Status.WaitForPacketSize;
							break;
						} else {
							mBuffer.skip(1);
							report.wastedBytes++;
						}
					}
					if (mStatus == Status.WaitForHeader) {
						return report;
					}
				case WaitForPacketSize:
					if (mBuffer.getCachedBytes() < mPacketSizeDescription.start + mPacketSizeDescription.length) {
						return report;
					}
					mStatus = Status.WaitForTermination;
					mBuffer.peek(mPacketSizeDescription.start, mPacketSizeDescription.length, mPacketProbe, mPacketSizeDescription.start);
					mPacketSize = mPacketDescriptor.decodePacketSize(mPacketProbe, mPacketSizeDescription.start);
				case WaitForTermination:
					if (mBuffer.getCachedBytes() < mPacketSize) {
						return report;
					}
					mBuffer.peek(0, mPacketSize, mPacketProbe, 0);
					if (mPacketDescriptor.verifyAndAcceptPacket(mPacketProbe, 0, mPacketSize)) {
						byte[] newPacket = new byte[mPacketSize];
						System.arraycopy(mPacketProbe, 0, newPacket, 0, mPacketSize);
						report.consumedBytes += mPacketSize;
						report.newPacketsFound.add(newPacket);
						mBuffer.skip(mPacketSize);
						mStatus = Status.WaitForHeader;
						mPacketSize = 0;
						if (captureLimit <= 0) {
							continue;
						}
						if (--captureLimit <= 0) {
							return report;
						}
					} else {
						mBuffer.skip(mHeaderDescription.length);
						report.wastedBytes += mHeaderDescription.length;
						mStatus = Status.WaitForHeader;
						mPacketSize = 0;
					}
					break;
				default:
					throw new RuntimeException("Unknown status while trying to parse");
			}
		}
	}

	private enum Status {
		WaitForHeader,
		WaitForPacketSize,
		WaitForTermination
	}

	public class ProcessReport {
		public int wastedBytes;
		public int consumedBytes;
		public List<byte[]> newPacketsFound = new ArrayList<>();
	}
}
