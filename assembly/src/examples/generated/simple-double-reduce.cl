void reduceOCL(__global double* input,
               __global double* output,
               __local double localSums[]) {
	size_t idx = get_global_id(0);
	size_t localIdx = get_local_id(0);
	size_t group_size = get_local_size(0);
	size_t groupID = get_group_id(0);
	int myID = get_global_id(0);

	localSums[localIdx] = input[idx];
	for (size_t stride = group_size / 2; stride > 0; stride /=2) {
		barrier(CLK_LOCAL_MEM_FENCE);
		if (localIdx < stride) {
            localSums[localIdx] += localSums[localIdx + stride];
		}
	}

	if (localIdx == 0) {
		output[groupID] = localSums[localIdx];
	}
}

#pragma OPENCL EXTENSION cl_khr_fp64 : enable
__kernel void reduceDouble(__global uchar *_heap_base,
                     ulong _frame_base,
                    __constant uchar *_constant_region,
                    __local uchar *_local_region,
                    __global int *_atomics) {

    __global ulong *frame = (__global ulong *) &_heap_base[_frame_base];
    ulong4 args = vload4(0, &frame[3]);
    __global double* input =  (__global double *) (args.x + 24);
    __global double* output = (__global double *) (args.y + 24);

    size_t idx = get_global_id(0);
    __local double localSums[1024];

    reduceOCL(input, output,  localSums);
}
