/*
 * This file is part of OrionAlpha, a MapleStory Emulator Project.
 * Copyright (C) 2018 Eric Smith <notericsoft@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package network.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.Charset;
import java.util.List;
import network.security.XORCrypter;
import util.FileTime;
import util.Pointer;
import util.Utilities;

/**
 *
 * @author Eric
 */
public class OutPacket {
    private static final int DEFAULT_BUFFER_ALLOC = 0x100;
    private static final Charset OUT_ENCODING = Charset.forName("MS932");
    
    private final ByteBuf sendBuff;
    
    /**
     * Construct a new OutPacket with a default size. 
     * 
     * @param type The operational code of this packet
     */
    public OutPacket(int type) {
        this(type, DEFAULT_BUFFER_ALLOC);
    }
    
    /**
     * Constructs a new OutPacket with a fixed size total.
     * 
     * @param type The operational code of this packet
     * @param bufferAlloc The size of the overall packet
     */
    public OutPacket (int type, int bufferAlloc) {
        this.sendBuff = Unpooled.buffer(bufferAlloc);
        this.init(type);
    }
    
    /**
     * Initializes the packet with the given packet header.
     * If the <code>type</code> is equal to <b>Integer.MAX_VALUE</b>,
     * then the packet header is ignored.
     * 
     * @param type The header of the packet
     */
    private void init(int type) {
        if (type != Integer.MAX_VALUE) {
            initByte(type);
        }
    }
    
    /**
     * Initializes the byte header of this packet.
     * In KMS Alpha, packet headers are written as bytes and not shorts.
     * 
     * @param type The packet header to encode
     */
    private void initByte(int type) {
        if (type != Integer.MAX_VALUE) {
            encodeByte(type);
        }
    }
    
    /**
     * Retrieve the current index of the overall buffer's writer.
     * 
     * @return The current index of the writer
     */
    public int getOffset() {
        return sendBuff.writerIndex();
    }
    
    /**
     * Using the raw <code>seqBase</code> and the crypter's <code>seqKey</code>,
     * this method will perform bitwise operation which will result in encoding
     * the final sequence base containing both the version of the game, and the
     * client's current sequence.
     * 
     * This method is designed to encode a sequence that determines if the packet 
     * is both correct, and that the server's version matches the client's version.
     * 
     * @param seqBase The sequence base (or, version) of the game
     * @param seqKey The sequencing key of the client's crypter
     * 
     * @return The encoded sequence base of the packet
     */
    public int encodeSeqBase(int seqBase, int seqKey) {
        if (seqBase != 0) {
            seqBase = (short) ((((0xFFFF - seqBase) >> 8) & 0xFF) | (((0xFFFF - seqBase) << 8) & 0xFF00));
        }
        
        if (seqKey != 0) {
            // ((unsigned __int16)(*puSeqKey >> 16) >> 8) ^ uSeqBase;
            seqKey = (short) ((((seqKey >> 24) & 0xFF) | (((seqKey >> 16) << 8) & 0xFF00)) ^ seqBase);
        } else {
            seqKey = seqBase;
        }
        return seqKey;
    }
    
    /**
     * 
     * 
     * @param l The list of buffer blocks to be flushed to socket
     * @param seqBase The sequence base (a.k.a the game version)
     * @param cipher The client's crypter
     */
    public void makeBufferList(List<byte[]> l, int seqBase, XORCrypter cipher) {
        // The source buffer to encrypt - do NOT encrypt directly;
        // all output of encrypted buffer should be in pDes, not pSrc.
        byte[] src = toArray();
        
        // The total remaining bytes to be written to the destination
        // buffer. We additionally encode the 4-byte TCP packet header.
        int remain = src.length + 4;
        
        // Calculate the length of the remaining buffer to form the
        // length of the block. 
        int lenBlock = Math.min(remain, 0x5B4);
        
        // Allocate a new destination block of empty bytes, used as the
        // new pDes buffer which will hold the encrypted packet.
        byte[] block = new byte[lenBlock];
        
        // pDes is a pointer to the pBlock buffer, however we use pDes
        // to store the current write offset of the destination buffer,
        // as the reason this is used is to get the current address of
        // the pDes pointer.
        int des = 0;
        // pDesEnd is a pointer to the end address of pDes (aka &pDes[uLenBlock]),
        // but the way we will use it is just to store uLenBlock which means the
        // end of the destination buffer. These two offsets are used to calculate
        // the total length of the packet to be encrypted.
        int desEnd = lenBlock;
        // pSrc0 is the pointer of pSrc, and holds the current address of pSrc.
        // This is used to know the current write offset of pSrc in case the
        // buffer exceeds block length and requires additional looping.
        int src0 = 0;
        
        // Encode the sequence base and write it to the first 2 bytes of the
        // TCP packet header.
        int rawSeq = encodeSeqBase(seqBase, cipher.getSeqSnd());
        byte[] dest = new byte[4];
        //*(unsigned __int16 *)pDes = uRawSeq;
        dest[des++] = (byte) ((rawSeq >>> 8) & 0xFF);
        dest[des++] = (byte) (rawSeq & 0xFF);
        //pDes += 2;

        // Encode the encrypted data length and write it to the last 2 bytes
        // of the TCP packet header.
        int dataLen = (((src.length << 8) & 0xFF00) | (src.length >>> 8));
        //*(unsigned __int16 *)pDes = uDataLen;
        dest[des++] = (byte) ((dataLen >>> 8) & 0xFF);
        dest[des++] = (byte) (dataLen & 0xFF);
        //pDes += 2;
        
        // Encrypt the first block and append to the buffer list.
        System.arraycopy(src, src0, block, des, desEnd - des);
        block = cipher.encrypt(block);
        // Copy the packet header after encrypting block
        System.arraycopy(dest, 0, block, 0, dest.length);
        src0 += desEnd - des;
        l.add(block);
        
        // Encrypt remaining blocks and continue appending to buffer list.
        while (true) {
            remain -= lenBlock;
            if (remain == 0)
                break;
            lenBlock = Math.min(remain, 0x5B4);
            block = new byte[lenBlock];
            des = 0;
            desEnd = lenBlock;
            
            System.arraycopy(src, src0, block, des, desEnd - des);
            block = cipher.encrypt(block);
            
            System.arraycopy(dest, 0, block, 0, dest.length);
            
            src0 += desEnd - des;
            l.add(block);
        }
    }
    
    /**
     * Encodes a single byte from a boolean.
     * 
     * @param b The value of the boolean to encode
     */
    public void encodeBool(boolean b) {
        sendBuff.writeBoolean(b);
    }
    
    /**
     * Encodes a single byte.
     * 
     * @param b The value of the byte to encode
     */
    public void encodeByte(byte b) {
        sendBuff.writeByte(b);
    }
    
    /**
     * Encodes a single byte (or, unsigned byte).
     * 
     * @param b The value of the byte (or, unsigned byte) to encode
     */
    public void encodeByte(int b) {
        sendBuff.writeByte(b);
    }
    
    /**
     * Encodes a 2-byte short.
     * 
     * @param n The value of the short to encode
     */
    public void encodeShort(short n) {
        sendBuff.writeShortLE(n);
    }
    
    /**
     * Encodes a 2-byte short with any datatype up to an integer.
     * 
     * @param n The value of the short to encode
     */
    public void encodeShort(int n) {
        sendBuff.writeShortLE(n);
    }
    
    /**
     * Encodes a 4-byte integer.
     * 
     * @param n The value of the integer to encode
     */
    public void encodeInt(int n) {
        sendBuff.writeIntLE(n);
    }
    
    /**
     * Encodes a 8-byte long.
     * 
     * @param l The value of the long to encode
     */
    public void encodeLong(long l) {
        sendBuff.writeLongLE(l);
    }
    
    /**
     * Encodes a 4-byte float.
     * 
     * @param f The value of the float to encode
     */
    public void encodeFloat(float f) {
        sendBuff.writeFloatLE(f);
    }
    
    /**
     * Encodes a 8-byte double.
     * 
     * @param d The value of the double to encode
     */
    public void encodeDouble(double d) {
        sendBuff.writeDoubleLE(d);
    }
    
    /**
     * Encodes a MapleStory String.
     * A MapleStory String is encoded as follows:
     *      [01 00] -> The size of the string
     *      [00] -> The <code>OUT_ENCODING</code> character(s) to encode
     * 
     * @param str The string to encode
     */
    public void encodeString(String str) {
        byte[] src = str.getBytes(OUT_ENCODING);
        
        encodeShort(src.length);
        encodeBuffer(src);
    }
    
    /**
     * Encodes a fixed-length string.
     * A fixed-length is a length that the MapleStory client requires.
     * If the string is shorter than <code>size</code>, then it will append
     * null-termination characters to fill the remaining length.
     * 
     * @param str The string
     * @param size The required length of the buffer
     */
    public void encodeString(String str, int size) {
        byte[] src = str.getBytes(OUT_ENCODING);
        
        for (int i = 0; i < size; i++) {
            if (i >= src.length) {
                encodeByte('\0');
            } else {
                encodeByte(src[i]);
            }
        }
    }
    
    /**
     * Encodes a 8-byte FileTime buffer.
     * 
     * @param ft The FileTime object to encode
     */
    public void encodeFileTime(FileTime ft) {
        encodeInt(ft.getLowDateTime());
        encodeInt(ft.getHighDateTime());
    }
    
    /**
     * Encodes an array of bytes.
     * The amount of bytes written in total length will be equivalent to the 
     * total size of the array.
     * 
     * @param buffer The array of bytes to encode
     */
    public void encodeBuffer(byte[] buffer) {
        sendBuff.writeBytes(buffer);
    }
    
    /**
     * Encodes the remaining bytes left within the buffer of <code>packet</code>.
     * 
     * @param packet The InPacket buffer to copy bytes from
     */
    public void encodePacket(InPacket packet) {
        packet.copyTo(this);
    }
    
    /**
     * This method will simply encode zeroes <code>count</code> amount of times.
     * 
     * While this should never actually be relied on, this will help with packets
     * that contain unknown lengths or are crashing from lack of data. Using this
     * should help identify the remaining bytes and how many are missing.
     * 
     * @param count The amount of bytes to pad
     */
    public void encodePadding(int count) {
        for (int i = 0; i < count; i++) {
            encodeByte(0);
        }
    }
    
    /**
     * Transforms the current send buffer into an array of bytes.
     * 
     * @return This packet's buffer represented as an array of bytes
     */
    public byte[] toArray() {
        byte[] src = new byte[sendBuff.writerIndex()];
        sendBuff.readBytes(src);
        sendBuff.resetReaderIndex();
        return src;
    }
    
    /**
     * Constructs a readable string displaying the original bytes of the packet.
     * 
     * @return A readable string containing the bytes of this packet 
     */
    public String dumpString() {
        return Utilities.toHexString(sendBuff.array());
    }
    
    /**
     * Dumps the packet data into a readable string.
     * 
     * @see dumpString()
     * @return A readable string displaying the bytes of the packet
     */
    @Override
    public String toString() {
        return dumpString();
    }
}
