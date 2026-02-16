# Gemmini Accelerator Learning Roadmap

## Phase 1: Fundamentals 
1. **Understand what Gemmini is**
   - **Systolic array basics**: A 2D array of processing elements (PEs) that perform multiply-accumulate operations
   - **RoCC interface**: Custom instruction extension mechanism in RISC-V processors
   - **Key operations**: Matrix multiplication (GEMM), convolutions, pooling for DNNs
   - **Read**: "["Gemmini: Enabling Systematic Deep-Learning Architecture Evaluation via Full-Stack Integration" paper](https://ieeexplore.ieee.org/document/9586216)
   - **Understand**: Why systolic arrays are efficient for matrix multiplications (data reuse, regular dataflow)

2. **Watch the 2022 tutorial**
   - [Gemmini Tutorial 2022 - Looks for recording and slides](https://sites.google.com/berkeley.edu/gemmini-tutorial-mlsys-2022/tutorial?authuser=0)
   - Take notes on:
     - Architecture diagram (scratchpad, accumulator, systolic array)
     - Dataflow modes: output-stationary (OS) vs weight-stationary (WS)
     - Custom RoCC instructions and their encoding
   - Pay attention to software/hardware interface boundary

3. **Read Chipyard Gemmini documentation**
   - **Location**: `generators/gemmini/README.md` in your Chipyard repo
   - **Key sections to focus on**:
     - Configuration parameters table (DIM, SP_CAPACITY, DATAFLOW, etc.)
     - Memory hierarchy: DRAM → Scratchpad → Accumulator → Systolic Array
     - ISA instruction list: CONFIG, PRELOAD, COMPUTE_AND_FLIP, MVOUT

## Phase 2: Hands-On Setup 
4. **Get Gemmini running in Chipyard**
   - **Navigate to**: `cd chipyard/sims/verilator`
   - **Build command**: `make CONFIG=GemminiRocketConfig`
   - **What happens**: 
     - Generates Verilog from Chisel (Scala hardware description)
     - Compiles with Verilator (C++ simulator)
     - Takes 1-3 hours on first build
   - **Troubleshooting**:
     - If out of memory: reduce parallelism with `-j4` flag
     - Check `generated-src/` for generated Verilog
     - Verify `simulator-chipyard.harness-GemminiRocketConfig` binary is created

5. **Run basic tests**
   - **Navigate to test directory**: `cd generators/gemmini/software/gemmini-rocc-tests`
   - **Build tests**: `./build.sh`
   - **Run simple test**:
     ```bash
     cd ../../sims/verilator
     make run-binary CONFIG=GemminiRocketConfig BINARY=../../generators/gemmini/software/gemmini-rocc-tests/bareMetalC/tiled_matmul_ws
     ```
   - **What to look for in output**:
     - "Passed" or "Failed" message
     - Cycle count: total simulation cycles
     - Compare against expected results
   - **Try these tests in order**:
     1. `mvin_mvout` - Basic memory movement
     2. `matmul_os` - Output-stationary matrix multiply
     3. `tiled_matmul_ws` - Weight-stationary tiled multiply

## Phase 3: Deep Dive 
6. **Study the architecture in detail**
   - **Dataflow comparison**:
     - **Output-stationary (OS)**: Partial sums stay in PEs, inputs flow through
       - Better for memory bandwidth
       - Default in many configurations
     - **Weight-stationary (WS)**: Weights stay in PEs, activations flow
       - Better for weight reuse
   - **Memory hierarchy** (read `generators/gemmini/src/main/scala/gemmini/Scratchpad.scala`):
     - **DRAM**: Main memory (slow, large)
     - **Scratchpad**: On-chip SRAM for input matrices
     - **Accumulator**: On-chip SRAM for output accumulation
     - **Systolic Array**: Compute fabric with PE registers
   - **DMA flow**: Understand how data moves from DRAM to scratchpad using MVIN/MVOUT instructions

7. **Explore the software stack**
   - **Key files to read**:
     - `generators/gemmini/software/gemmini-rocc-tests/include/gemmini.h` - High-level API
     - `generators/gemmini/software/gemmini-rocc-tests/include/gemmini_params.h` - Configuration constants
     - `bareMetalC/matmul.c` - Simple matrix multiply example
   - **Understand API functions**:
     - `gemmini_config_ld()` - Configure load settings
     - `gemmini_mvin()` - Move data from DRAM to scratchpad
     - `gemmini_preload()` - Preload weights into systolic array
     - `gemmini_compute_accumulated()` - Trigger computation
     - `gemmini_mvout()` - Move results back to DRAM
   - **Exercise**: Write pseudocode for a 16x16 matrix multiply using these functions

8. **Trace execution**
   - **Enable waveforms** (WARNING: large files):
     ```bash
     make run-binary CONFIG=GemminiRocketConfig BINARY=<test> debug-flags="-v output.vcd"
     ```
   - **View with GTKWave**: `gtkwave output.vcd`
   - **Signals to trace**:
     - `GemminiModule.io.cmd` - RoCC command interface
     - `Scratchpad.io` - Scratchpad read/write
     - `MeshWithMemoryBuffers` - Systolic array activity
   - **Use printfs**: Add `printf()` in test code to track execution
   - **Cycle-accurate analysis**: Count cycles between MVIN and MVOUT to understand latency

## Phase 4: Customization
9. **Modify Gemmini parameters**
   - **Array dimensions**:
     - `DIM = 16` (default) creates 16×16 systolic array
     - Try `DIM = 8` (smaller, faster builds) or `DIM = 32` (more parallelism)
     - Trade-off: larger array = more compute, but longer critical path
   - **Scratchpad capacity**:
     - `SP_CAPACITY = 256 * 1024` (256KB default)
     - Controls max matrix size before tiling needed
   - **Accumulator capacity**:
     - `ACC_CAPACITY = 64 * 1024` (64KB default)
   - **Data types**:
     - `inputType = SInt(8.W)` for INT8 quantized inference
     - `outputType = SInt(32.W)` for accumulated results
     - Can experiment with FP16, BF16 for training
   - **Dataflow**: `dataflow = Dataflow.OS` or `Dataflow.WS`

10. **Create custom config**
    - **File**: `generators/chipyard/src/main/scala/config/GemminiConfigs.scala`
    - **Template**:
      ```scala
      class MyGemminiConfig extends Config(
        new gemmini.DefaultGemminiConfig ++
        new freechips.rocketchip.subsystem.WithNBigCores(1) ++
        new chipyard.config.AbstractConfig
      )
      ```
    - **With custom parameters**:
      ```scala
      class SmallGemminiConfig extends Config(
        new gemmini.GemminiArrayConfig(
          DIM = 8,
          SP_CAPACITY = 64 * 1024,
          dataflow = gemmini.Dataflow.WS
        ) ++
        new freechips.rocketchip.subsystem.WithNBigCores(1) ++
        new chipyard.config.AbstractConfig
      )
      ```
    - **Rebuild**: `make CONFIG=MyGemminiConfig` to test your config

11. **Write custom tests**
    - **Location**: Create `generators/gemmini/software/gemmini-rocc-tests/bareMetalC/my_test.c`
    - **Example test ideas**:
      - Non-square matrices (M×K × K×N)
      - Batched matrix multiplies
      - Convolution using im2col transformation
      - Performance test with large matrices
    - **Benchmarking**:
      - Extract cycle count from simulator output
      - Calculate: `GOPS = (2 * M * N * K) / cycles / frequency`
      - Use `gemmini_fence()` to separate test phases
    - **Compare configs**: Run same test on different DIM values, plot performance

## Quick Start Action Items (Do These First!)

### Phase 1 Checklist:
- [ ] **Setup environment**:
  ```bash
  # Clone Chipyard if you haven't
  git clone https://github.com/ucb-bar/chipyard.git
  cd chipyard
  ./build-setup.sh riscv-tools
  source env.sh
  ```
- [ ] **Read documentation** (2-3 hours):
  - [ ] `generators/gemmini/README.md`
  - [ ] Gemmini paper (search "Gemmini: Enabling Systematic Deep-Learning Architecture Evaluation")
  - [ ] Systolic array basics (Google: "Why Systolic Architectures?" by H.T. Kung)
- [ ] **Find and watch Gemmini tutorial** recording (1-2 hours)
- [ ] **Build GemminiRocketConfig** (2-4 hours):
  ```bash
  cd sims/verilator
  make CONFIG=GemminiRocketConfig
  # Go get coffee, this takes a while!
  ```
- [ ] **Run first test** (30 minutes):
  ```bash
  cd generators/gemmini/software/gemmini-rocc-tests
  ./build.sh
  cd ../../sims/verilator
  make run-binary CONFIG=GemminiRocketConfig BINARY=../../generators/gemmini/software/gemmini-rocc-tests/bareMetalC/tiled_matmul_ws
  ```
- [ ] **Verify success**: Look for "Passed!" in terminal output

### Phase 2 Deep Dive:
- [ ] Read `gemmini.h` API and understand each function
- [ ] Run all basic tests: `mvin_mvout`, `matmul_os`, `matmul_ws`, `conv`
- [ ] Generate waveform for one test and explore in GTKWave
- [ ] Read Scratchpad.scala and understand memory hierarchy

### Phase 3 Customization:
- [ ] Create custom config with `DIM=8`
- [ ] Write a simple custom test (e.g., 32×32 matrix multiply)
- [ ] Benchmark: compare cycle counts between `DIM=8` and `DIM=16`
- [ ] Experiment with `Dataflow.OS` vs `Dataflow.WS`

---

## Common Issues & Troubleshooting

### Build Problems:
- **Out of memory during build**: Use `make -j4 CONFIG=...` to limit parallelism
- **Missing dependencies**: Re-run `./build-setup.sh riscv-tools` and `source env.sh`
- **Stale builds**: `make clean` or delete `generated-src/` and `output/` directories

### Test Failures:
- **Spike not found**: Ensure `riscv-tools` is built and env.sh is sourced
- **Wrong results**: Check if CONFIG matches the test expectations (DIM, dataflow)
- **Segmentation fault**: May need to increase memory limits or check test matrix sizes

### Performance Issues:
- **Slow simulation**: Use VCS instead of Verilator, or try FireSim for FPGA-based simulation
- **Not seeing speedup**: Check utilization - may be memory-bound, try larger matrices

### Where to Get Help:
- **Chipyard Gemmini GitHub**: https://github.com/ucb-bar/gemmini/issues
- **Chipyard Google Group**: https://groups.google.com/g/chipyard

---

## Useful Resources

### Documentation:
- Gemmini GitHub: https://github.com/ucb-bar/gemmini
- Chipyard Docs: https://chipyard.readthedocs.io/
- RoCC Interface: https://chipyard.readthedocs.io/en/stable/Customization/RoCC-Accelerators.html

### Code References:
- `generators/gemmini/src/main/scala/gemmini/` - All RTL
- `generators/gemmini/software/gemmini-rocc-tests/` - Software examples
- `generators/chipyard/src/main/scala/config/` - Configuration examples
