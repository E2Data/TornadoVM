#pragma OPENCL EXTENSION cl_khr_fp64 : enable  
__kernel void reductionAddDoubles(__global uchar *_heap_base, ulong _frame_base, __constant uchar *_constant_region, __local uchar *_local_region, __global uchar *_private_region)
{
  ulong ul_11, ul_37, ul_1, ul_0, ul_3; 
  bool z_23, z_31; 
  int i_12, i_6, i_38, i_7, i_4, i_5, i_30, i_33, i_28, i_29, i_22, i_25, i_18, i_19, i_20, i_21, i_14, i_15, i_16, i_17; 
  double d_13, d_32, d_26, d_27, d_24; 
  long l_34, l_35, l_10, l_8, l_9, l_36; 

  __global ulong *_frame = (__global ulong *) &_heap_base[_frame_base];


  // BLOCK 0
  ul_0  =  (ulong) _frame[6];
  ul_1  =  (ulong) _frame[7];
  __local double ul_2[256];
  ul_3  =  ul_1 + 24L;
  *((__global double *) ul_3)  =  0.0;
  i_4  =  get_global_id(0);
  // BLOCK 1 MERGES [0 7 ]
  i_5  =  i_4;
  for(;i_5 < 8192;)  {
    // BLOCK 2
    i_6  =  get_local_id(0);
    i_7  =  get_local_size(0);
    l_8  =  (long) i_5;
    l_9  =  l_8 << 3;
    l_10  =  l_9 + 24L;
    ul_11  =  ul_0 + l_10;
    i_12  =  get_group_id(0);
    d_13  =  *((__global double *) ul_11);
    ul_2[i_6]  =  d_13;
    i_14  =  i_7 >> 31;
    i_15  =  i_14 >> 31;
    i_16  =  i_15 + i_7;
    i_17  =  i_16 >> 1;
    // BLOCK 3 MERGES [2 11 ]
    i_18  =  i_17;
    for(;i_18 >= 1;)    {
      // BLOCK 8
      barrier(CLK_LOCAL_MEM_FENCE);
      i_19  =  i_18 >> 31;
      i_20  =  i_19 >> 31;
      i_21  =  i_20 + i_18;
      i_22  =  i_21 >> 1;
      z_23  =  i_6 < i_18;
      if(z_23)
      {
        // BLOCK 9
        d_24  =  ul_2[i_6];
        i_25  =  i_18 + i_6;
        d_26  =  ul_2[i_25];
        d_27  =  d_24 + d_26;
        ul_2[i_6]  =  d_27;
      }
      else
      {
        // BLOCK 10
      }
      // BLOCK 11 MERGES [10 9 ]
      i_28  =  i_22;
      i_18  =  i_28;
    }
    // BLOCK 4
    barrier(CLK_GLOBAL_MEM_FENCE);
    i_29  =  get_global_size(0);
    i_30  =  i_29 + i_5;
    z_31  =  i_6 == 0;
    if(z_31)
    {
      // BLOCK 5
      d_32  =  ul_2[0];
      i_33  =  i_12 + 1;
      l_34  =  (long) i_33;
      l_35  =  l_34 << 3;
      l_36  =  l_35 + 24L;
      ul_37  =  ul_1 + l_36;
      *((__global double *) ul_37)  =  d_32;
    }
    else
    {
      // BLOCK 6
    }
    // BLOCK 7 MERGES [6 5 ]
    i_38  =  i_30;
    i_5  =  i_38;
  }
  // BLOCK 12
  return;
}
