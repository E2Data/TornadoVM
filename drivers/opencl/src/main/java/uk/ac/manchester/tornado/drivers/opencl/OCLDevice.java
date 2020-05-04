/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2020, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
 * Copyright (c) 2013-2020, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Authors: James Clarkson
 *
 */
package uk.ac.manchester.tornado.drivers.opencl;

import static uk.ac.manchester.tornado.drivers.opencl.OpenCL.CL_TRUE;
import static uk.ac.manchester.tornado.runtime.common.RuntimeUtilities.humanReadableByteCount;
import static uk.ac.manchester.tornado.runtime.common.RuntimeUtilities.humanReadableFreq;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import uk.ac.manchester.tornado.api.TornadoTargetDevice;
import uk.ac.manchester.tornado.drivers.opencl.enums.OCLDeviceInfo;
import uk.ac.manchester.tornado.drivers.opencl.enums.OCLDeviceType;
import uk.ac.manchester.tornado.drivers.opencl.enums.OCLLocalMemType;
import uk.ac.manchester.tornado.runtime.common.RuntimeUtilities;
import uk.ac.manchester.tornado.runtime.common.TornadoLogger;

public class OCLDevice extends TornadoLogger implements TornadoTargetDevice {

    private final long id;
    private final int index;

    private final ByteBuffer buffer;
    private String name;
    private int deviceEndianLittle;
    private String openCLVersion;
    private int maxComputeUnits;
    private long maxAllocationSize;
    private long globalMemorySize;
    private long localMemorySize;
    private int maxWorkItemDimensions;
    private long[] maxWorkItemSizes;
    private long maxWorkGroupSize;
    private long maxConstantBufferSize;
    private long doubleFPConfig;
    private long singleFPConfig;
    private String version;
    private OCLDeviceType deviceType;

    private static final int INIT_VALUE = -1;

    public OCLDevice(int index, long id) {
        this.index = index;
        this.id = id;
        this.buffer = ByteBuffer.allocate(8192);
        this.buffer.order(OpenCL.BYTE_ORDER);
        this.openCLVersion = null;
        this.deviceEndianLittle = INIT_VALUE;
        this.maxComputeUnits = INIT_VALUE;
        this.maxAllocationSize = INIT_VALUE;
        this.globalMemorySize = INIT_VALUE;
        this.localMemorySize = INIT_VALUE;
        this.maxWorkItemDimensions = INIT_VALUE;
        this.maxWorkGroupSize = INIT_VALUE;
        this.maxConstantBufferSize = INIT_VALUE;
        this.doubleFPConfig = INIT_VALUE;
        this.singleFPConfig = INIT_VALUE;
        this.maxWorkItemSizes = null;
        this.name = null;
        this.version = null;
        this.deviceType = OCLDeviceType.Unknown;
    }

    native static void clGetDeviceInfo(long id, int info, byte[] buffer);

    public long getId() {
        return id;
    }

    public int getIndex() {
        return index;
    }

    public OCLDeviceType getDeviceType() {
        if (deviceType != OCLDeviceType.Unknown) {
            return deviceType;
        }
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_TYPE.getValue(), buffer.array());

        long type = buffer.getLong();
        deviceType = OCLDeviceType.toDeviceType(type);

        return deviceType;
    }

    public int getDeviceVendorId() {
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_VENDOR_ID.getValue(), buffer.array());

        return buffer.getInt();
    }

    public int getDeviceMemoryBaseAlignment() {
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_MEM_BASE_ADDR_ALIGN.getValue(), buffer.array());

        return buffer.getInt();
    }

    public boolean isDeviceAvailable() {
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_AVAILABLE.getValue(), buffer.array());

        return buffer.getInt() == 1;
    }

    @Override
    public String getDeviceName() {
        if (name == null) {
            Arrays.fill(buffer.array(), (byte) 0);
            buffer.clear();

            clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_NAME.getValue(), buffer.array());
            try {
                name = new String(buffer.array(), "ASCII").trim();
            } catch (UnsupportedEncodingException e) {
                name = "unknown";
            }
        }

        return name;
    }

    public String getDeviceVendor() {
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();

        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_VENDOR.getValue(), buffer.array());
        String name;
        try {
            name = new String(buffer.array(), "ASCII");
        } catch (UnsupportedEncodingException e) {
            name = "unknown";
        }
        return name.trim();
    }

    public String getDriverVersion() {
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();

        clGetDeviceInfo(id, OCLDeviceInfo.CL_DRIVER_VERSION.getValue(), buffer.array());
        String name;
        try {
            name = new String(buffer.array(), "ASCII");
        } catch (UnsupportedEncodingException e) {
            name = "unknown";
        }
        return name.trim();
    }

    public String getDeviceVersion() {
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_VERSION.getValue(), buffer.array());
        String name;
        try {
            name = new String(buffer.array(), "ASCII");
        } catch (UnsupportedEncodingException e) {
            name = "unknown";
        }
        return name.trim();
    }

    public String getDeviceOpenCLCVersion() {
        if (openCLVersion == null) {
            Arrays.fill(buffer.array(), (byte) 0);
            buffer.clear();
            clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_OPENCL_C_VERSION.getValue(), buffer.array());
            try {
                openCLVersion = new String(buffer.array(), "ASCII").trim();
            } catch (UnsupportedEncodingException e) {
                openCLVersion = "unknown";
            }
        }
        return openCLVersion;
    }

    public String getDeviceExtensions() {
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_EXTENSIONS.getValue(), buffer.array());
        String name;
        try {
            name = new String(buffer.array(), "ASCII");
        } catch (UnsupportedEncodingException e) {
            name = "unknown";
        }
        return name.trim();
    }

    @Override
    public int getDeviceMaxComputeUnits() {
        if (maxComputeUnits != -1) {
            return maxComputeUnits;
        }

        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_MAX_COMPUTE_UNITS.getValue(), buffer.array());

        maxComputeUnits = buffer.getInt();

        return maxComputeUnits;
    }

    @Override
    public int getDeviceMaxClockFrequency() {
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_MAX_CLOCK_FREQUENCY.getValue(), buffer.array());

        return buffer.getInt();
    }

    @Override
    public long getDeviceMaxAllocationSize() {
        if (maxAllocationSize != -1) {
            return maxAllocationSize;
        }

        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_MAX_MEM_ALLOC_SIZE.getValue(), buffer.array());

        maxAllocationSize = buffer.getLong();
        return maxAllocationSize;
    }

    @Override
    public long getDeviceGlobalMemorySize() {
        if (globalMemorySize != -1) {
            return globalMemorySize;
        }

        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_GLOBAL_MEM_SIZE.getValue(), buffer.array());

        globalMemorySize = buffer.getLong();
        return globalMemorySize;
    }

    @Override
    public long getDeviceLocalMemorySize() {
        if (localMemorySize != -1) {
            return localMemorySize;
        }

        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_LOCAL_MEM_SIZE.getValue(), buffer.array());

        localMemorySize = buffer.getLong();
        return localMemorySize;
    }

    public int getDeviceMaxWorkItemDimensions() {
        if (maxWorkItemDimensions != -1) {
            return maxWorkItemDimensions;
        }

        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();

        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_MAX_WORK_ITEM_DIMENSIONS.getValue(), buffer.array());

        maxWorkItemDimensions = buffer.getInt();
        return maxWorkItemDimensions;
    }

    @Override
    public long[] getDeviceMaxWorkItemSizes() {
        if (maxWorkItemSizes != null) {
            return maxWorkItemSizes;
        }

        final int elements = getDeviceMaxWorkItemDimensions();

        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();

        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_MAX_WORK_ITEM_SIZES.getValue(), buffer.array());

        buffer.rewind();

        maxWorkItemSizes = new long[elements];
        for (int i = 0; i < elements; i++) {
            maxWorkItemSizes[i] = buffer.getLong();
        }

        return maxWorkItemSizes;
    }

    public long getDeviceMaxWorkGroupSize() {
        if (maxWorkGroupSize != -1) {
            return maxWorkGroupSize;
        }
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();

        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_MAX_WORK_GROUP_SIZE.getValue(), buffer.array());

        maxWorkGroupSize = buffer.getLong();
        return maxWorkGroupSize;
    }

    @Override
    public long getDeviceMaxConstantBufferSize() {
        if (maxConstantBufferSize != -1) {
            return maxConstantBufferSize;
        }
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();

        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_MAX_CONSTANT_BUFFER_SIZE.getValue(), buffer.array());

        maxConstantBufferSize = buffer.getLong();
        return maxConstantBufferSize;
    }

    public long getDeviceDoubleFPConfig() {
        if (doubleFPConfig != -1) {
            return doubleFPConfig;
        }
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_DOUBLE_FP_CONFIG.getValue(), buffer.array());
        doubleFPConfig = buffer.getLong();
        return doubleFPConfig;
    }

    public long getDeviceSingleFPConfig() {
        if (singleFPConfig != -1) {
            return singleFPConfig;
        }

        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();

        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_SINGLE_FP_CONFIG.getValue(), buffer.array());

        singleFPConfig = buffer.getLong();
        return singleFPConfig;
    }

    public int getDeviceAddressBits() {
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();

        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_ADDRESS_BITS.getValue(), buffer.array());
        return buffer.getInt();
    }

    public boolean hasDeviceUnifiedMemory() {
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_HOST_UNIFIED_MEMORY.getValue(), buffer.array());
        return buffer.getInt() == OpenCL.CL_TRUE;
    }

    public OCLLocalMemType getDeviceLocalMemoryType() {
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();
        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_LOCAL_MEM_TYPE.getValue(), buffer.array());
        return OCLLocalMemType.toLocalMemType(buffer.getInt());
    }

    boolean isLittleEndian() {
        if (deviceEndianLittle != -1) {
            return deviceEndianLittle == CL_TRUE;
        }
        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();

        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_ENDIAN_LITTLE.getValue(), buffer.array());
        deviceEndianLittle = buffer.getInt();

        return deviceEndianLittle == CL_TRUE;
    }

    public int getWordSize() {
        return getDeviceAddressBits() >> 3;
    }

    public ByteOrder getByteOrder() {
        return isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("id=0x%x, name=%s, type=%s, available=%s", id, getDeviceName(), getDeviceType().toString(), isDeviceAvailable()));
        return sb.toString();
    }

    @Override
    public Object getDeviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("id=0x%x, name=%s, type=%s, available=%s\n", id, getDeviceName(), getDeviceType().toString(), isDeviceAvailable()));
        sb.append(String.format("freq=%s, max compute units=%d\n", humanReadableFreq(getDeviceMaxClockFrequency()), getDeviceMaxComputeUnits()));
        sb.append(String.format("global mem. size=%s, local mem. size=%s\n", RuntimeUtilities.humanReadableByteCount(getDeviceGlobalMemorySize(), false),
                humanReadableByteCount(getDeviceLocalMemorySize(), false)));
        sb.append(String.format("extensions:\n"));
        for (String extension : getDeviceExtensions().split(" ")) {
            sb.append("\t" + extension + "\n");
        }
        sb.append(String.format("unified memory   : %s\n", hasDeviceUnifiedMemory()));
        sb.append(String.format("device vendor    : %s\n", getDeviceVendor()));
        sb.append(String.format("device version   : %s\n", getDeviceVersion()));
        sb.append(String.format("driver version   : %s\n", getDriverVersion()));
        sb.append(String.format("OpenCL C version : %s\n", getDeviceOpenCLCVersion()));
        sb.append(String.format("Endianess        : %s\n", isLittleEndian() ? "little" : "big"));
        sb.append(String.format("address size     : %d\n", getDeviceAddressBits()));
        sb.append(String.format("single fp config : 0x%x\n", getDeviceSingleFPConfig()));
        sb.append(String.format("double fp config : 0x%x\n", getDeviceDoubleFPConfig()));
        return sb.toString();
    }

    public String getVersion() {
        if (version != null) {
            return version;
        }

        Arrays.fill(buffer.array(), (byte) 0);
        buffer.clear();

        clGetDeviceInfo(id, OCLDeviceInfo.CL_DEVICE_VERSION.getValue(), buffer.array());
        try {
            version = new String(buffer.array(), "ASCII").trim();
        } catch (UnsupportedEncodingException e) {
            version = "OpenCL 0.0";
        }
        return version;
    }
}
