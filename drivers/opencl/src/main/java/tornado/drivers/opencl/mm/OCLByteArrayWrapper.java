/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornado
 *
 * Copyright (c) 2013-2017 APT Group, School of Computer Science, 
 * The University of Manchester
 *
 * This work is partially supported by EPSRC grants:
 * Anyscale EP/L000725/1 and PAMELA EP/K008730/1.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Authors: James Clarkson
 *
 */
package tornado.drivers.opencl.mm;

import jdk.vm.ci.meta.JavaKind;
import tornado.drivers.opencl.OCLDeviceContext;

public class OCLByteArrayWrapper extends OCLArrayWrapper<byte[]> {

	public OCLByteArrayWrapper(OCLDeviceContext device) {
		this(device, false);
	}

	public OCLByteArrayWrapper(OCLDeviceContext device,boolean isFinal) {
		super(device, JavaKind.Byte, isFinal);
	}

	@Override
	protected void readArrayData(long bufferId, long offset, long bytes,
			byte[] value, int[] waitEvents) {
		deviceContext.readBuffer(bufferId, offset, bytes, value, waitEvents);
	}

	@Override
	protected void writeArrayData(long bufferId, long offset, long bytes,
			byte[] value, int[] waitEvents) {
		deviceContext.writeBuffer(bufferId, offset, bytes, value, waitEvents);
	}

	@Override
	protected int enqueueReadArrayData(long bufferId, long offset,
			long bytes, byte[] value, int[] waitEvents) {
		return deviceContext.enqueueReadBuffer(bufferId, offset, bytes, value, waitEvents);
	}

	@Override
	protected int enqueueWriteArrayData(long bufferId, long offset,
			long bytes, byte[] value, int[] waitEvents) {
		return deviceContext.enqueueWriteBuffer(bufferId, offset, bytes, value, waitEvents);
	}


}