#pragma OPENCL EXTENSION cl_khr_fp64 : enable  
__kernel void reductionAddDoubles(__global uchar *_heap_base, ulong _frame_base, __constant uchar *_constant_region, __local uchar *_local_region, __global uchar *_private_region)
{
  ulong ul_11, ul_37, ul_1, ul_0, ul_3; 
  bool z_23, z_31; 
  int i_12, i_6, i_38, i_7, i_4, i_5, i_30, i_33, i_28, i_29, i_22, i_25, i_18, i_19, i_20, i_21, i_14, i_15, i_16, i_17; 
  long d_32, d_26, d_27, d_24; 
  long l_34, l_35, l_10, l_8, l_9, l_36; 
  long d_13;

  __global ulong *_frame = (__global ulong *) &_heap_base[_frame_base];


  // BLOCK 0
  ul_0  =  (ulong) _frame[6];
  ul_1  =  (ulong) _frame[7];
  ulong ul_4  =  (ulong) _frame[8];
  ulong ul_7  =  (ulong) _frame[9];

  __local long ul_2[256];   // local memory for variable 1
  __local long ul_5[256];   // local memory for variable 2
  __local long ul_6[256];   // local memory for variable 2

  ul_3  =  ul_1 + 24L;
  *((__global long *) ul_3)  =  0;
  ul_3  =  ul_4 + 24L;
  *((__global long *) ul_3)  =  0;
  ul_3  =  ul_7 + 24L;
  *((__global long *) ul_3)  =  0;
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
    d_13  =  *((__global long *) ul_11);

    ul_2[i_6]  =  d_13;  // load from global to local array 1
    ul_5[i_6]  =  d_13;  // load from global to local array 2
    ul_6[i_6]  =  d_13;  // load from global to local array 2

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
        ul_5[i_6]  =  d_27;
        ul_6[i_6]  =  d_27;
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

      // store 1
      d_32  =  ul_2[0];
      i_33  =  i_12 + 1;
      l_34  =  (long) i_33;
      l_35  =  l_34 << 3;
      l_36  =  l_35 + 24L;
      ul_37  =  ul_1 + l_36;
      *((__global long *) ul_37)  =  d_32;


      // store 2
      d_32  =  ul_5[0];
      i_33  =  i_12 + 1;
      l_34  =  (long) i_33;
      l_35  =  l_34 << 3;
      l_36  =  l_35 + 24L;
      ul_37  =  ul_4 + l_36;
      *((__global long *) ul_37)  =  d_32;

      // store 3
      d_32  =  ul_6[0];
      i_33  =  i_12 + 1;
      l_34  =  (long) i_33;
      l_35  =  l_34 << 3;
      l_36  =  l_35 + 24L;
      ul_37  =  ul_7 + l_36;
      *((__global long *) ul_37)  =  d_32;

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
