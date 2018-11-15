package org.apache.spark.network.pmof;

import com.intel.hpnl.core.EqService;
import org.apache.spark.network.buffer.ManagedBuffer;
import org.apache.spark.unsafe.memory.MemoryBlock;
import org.apache.spark.unsafe.memory.UnsafeMemoryAllocator;
import sun.nio.ch.FileChannelImpl;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class ShuffleBuffer extends ManagedBuffer {
    private MemoryBlock memoryBlock;
    private final UnsafeMemoryAllocator unsafeAlloc = new UnsafeMemoryAllocator();
    private final long length;
    private final long address;
    private long lengthAligned;
    private long offsetAligned;
    private final EqService service;
    private int rdmaBufferId;
    private long rkey;
    private ByteBuffer byteBuffer;
    private Method mmap = null;
    private Method unmmap = null;

    public ShuffleBuffer(long length, EqService service) throws IOException {
        this.length = length;
        memoryBlock = unsafeAlloc.allocate(this.length);
        this.address = memoryBlock.getBaseOffset();
        this.service = service;
        this.byteBuffer = convertToByteBuffer();
        this.byteBuffer.limit((int)length);
    }

    public ShuffleBuffer(long offset, long length, FileChannel channel, EqService service) throws IOException {
        try {
            mmap = FileChannelImpl.class.getDeclaredMethod("map0", int.class, long.class, long.class);
            mmap.setAccessible(true);
            unmmap = FileChannelImpl.class.getDeclaredMethod("unmap0", long.class, long.class);
            unmmap.setAccessible(true);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
        long distanceFromOffset = offset%4096;
        this.offsetAligned = offset-distanceFromOffset;
        this.lengthAligned = (length+distanceFromOffset + 0xfffL) & ~0xfffL;
        long addressTmp = 0;
        try {
            assert mmap != null;
            addressTmp = (Long)mmap.invoke(channel, 1, this.offsetAligned, this.lengthAligned);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        this.length = length;
        this.address = addressTmp+distanceFromOffset;
        this.service = service;
        this.byteBuffer = convertToByteBuffer();
        this.byteBuffer.limit((int)length);
    }

    public long getLength() {
        return this.length;
    }

    public long size() {
        return this.length;
    }

    public ByteBuffer nioByteBuffer() {
        return byteBuffer;
    }

    public long getAddress() {
        return this.address;
    }

    public void setRdmaBufferId(int rdmaBufferId) {
        this.rdmaBufferId = rdmaBufferId;
    }

    public int getRdmaBufferId() {
        return this.rdmaBufferId;
    }

    public void setRkey(long rkey) {
        this.rkey = rkey;
    }

    public long getRkey() {
        return this.rkey;
    }

    public ManagedBuffer release() {
        if (unmmap != null) {
            try {
                unmmap.invoke(null, this.offsetAligned, this.lengthAligned);
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }
        } else {
            service.unregRmaBuffer(this.rdmaBufferId);
            unsafeAlloc.free(memoryBlock);
        }
        return this;
    }

    public Object convertToNetty() {
        return null;
    }

    public InputStream createInputStream() {
        return new ShuffleBufferInputStream(this);
    }

    public ManagedBuffer retain() {
        return null;
    }

    private ByteBuffer convertToByteBuffer() throws IOException {
        Class<?> classDirectByteBuffer;
        try {
            classDirectByteBuffer = Class.forName("java.nio.DirectByteBuffer");
        } catch (ClassNotFoundException e) {
            throw new IOException("java.nio.DirectByteBuffer class not found");
        }
        Constructor<?> constructor;
        try {
            constructor = classDirectByteBuffer.getDeclaredConstructor(long.class, int.class);
        } catch (NoSuchMethodException e) {
            throw new IOException("java.nio.DirectByteBuffer constructor not found");
        }
        constructor.setAccessible(true);
        ByteBuffer byteBuffer;
        try {
            byteBuffer = (ByteBuffer)constructor.newInstance(address, (int)length);
        } catch (Exception e) {
            throw new IOException("java.nio.DirectByteBuffer exception: " + e.toString());
        }

        return byteBuffer;
    }
}