# Tuned Counter RoCC Accelerator

## Overview
The TunedCounter is a RoCC (Rocket Custom Coprocessor) accelerator designed to efficiently count how many times a specific character (needle) appear in a string. It operates as a finite state machine with memory interface capabilities, with tunable processing in chunks of data from 1 to 8 bytes at a time.

The accelerator connects to the Rocket core through the LazyRoCC interface and implements a custom instruction that takes:
- rs1: Base address of the string to search
- rs2[7:0]: Character to search for (needle)  
- rs2[11:8]: Chunk size (1-8 characters to process per memory request)
- rd: Destination register for the result count

## Architecture

### State Machine
The accelerator implements a 4-state FSM:
1. **idle**: Waiting for commands, decodes instruction parameters
2. **fetch**: Issues memory requests to read string data
3. **count**: Processes fetched data and counts character matches
4. **done**: Returns result to the CPU

### Memory Interface
- Uses TileLink memory interface for data access
- Supports configurable chunk sizes (1, 2, 4, or 8 bytes per request)
- Implements proper privilege checking and exception handling
- Uses memory tags for request/response matching\

#### Memory Size Calculation
The accelerator uses a helper function to convert chunk sizes to TileLink size encoding:

```scala
def minPow2(x: UInt): UInt = {
  Mux(x <= 1.U, 0.U,            // If 1 char get 2^0 = 1 byte
  Mux(x <= 2.U, 1.U,            // Else if 2 chars get 2 bytes
  Mux(x <= 4.U, 2.U, 3.U)))     // Else if 3,4 chars get 4 bytes, else (5-7 chars) get 8 bytes
}
```

This function maps chunk sizes to power-of-2 memory request sizes required by the TileLink protocol:
- chunk_size 1 → size 0 (2^0 = 1 byte)
- chunk_size 2 → size 1 (2^1 = 2 bytes)
- chunk_size 3-4 → size 2 (2^2 = 4 bytes)
- chunk_size 5-8 → size 3 (2^3 = 8 bytes)

### Parallel Processing Optimization
The counting logic uses parallel processing to handle variable-length strings efficiently:
- Simultaneously computes match counts for all possible null terminator positions
- Uses priority encoding to select the correct result based on actual data
- Minimizes pipeline stalls through speculative computation

## Key Wires and Signals

### Control Signals
- **state**: 4-state FSM register (idle, fetch, count, done)
- **cmd**: Queued command interface from CPU
- **io.busy**: Indicates accelerator is processing
- **io.interrupt**: Interrupt signal (unused, tied to false)

### Data Registers
- **needle**: 8-bit register holding the character to search for
- **chunk_size**: 4-bit register specifying bytes to read per request (1-8)
- **stringAddr**: 64-bit current memory address being processed
- **charCount**: 64-bit accumulator for total character matches found
- **rd**: 5-bit destination register identifier
- **iterCount**: 64-bit iteration counter for timeout protection

### Memory Interface Wires
- **io.mem.req**: Memory request interface
  - **valid/ready**: Handshake signals for memory requests
  - **addr**: Target memory address
  - **size**: Request size (log2 of bytes)
  - **cmd**: Memory command (M_XRD for reads)
  - **tag**: Request identifier for response matching
- **io.mem.resp**: Memory response interface
  - **valid**: Response data available
  - **data**: 64-bit data payload from memory
  - **tag**: Response tag matching request
- **io.mem.s2_xcpt**: Memory exception signals

### Response Interface
- **io.resp.valid**: Result ready signal
- **io.resp.bits.rd**: Destination register
- **io.resp.bits.data**: Final character count or error code

### Internal Processing Wires
- **bytes**: 8-element vector extracting individual bytes from memory response
- **matchCounts**: 9-element vector storing parallel match calculations
- **shouldStop**: 9-element vector indicating stop conditions for each scenario
- **nullPositions**: Vector tracking null character locations
- **selectedMatchCount**: Final selected match count based on actual data

## Error Handling
- Returns 0xFFFFFFFFFFFFFFFF for invalid chunk sizes (0 or >8)
- Returns 0xFFFFFFFFFFFFFFFF for memory exceptions
- Implements maximum iteration limit (1000) to prevent infinite loops
- Proper null terminator detection for string boundaries

## Performance Features
- Configurable chunk processing (1-8 bytes) for memory bandwidth optimization
- Parallel computation of all possible outcomes to minimize decision latency
- Single-cycle character matching using hardware comparison
- Efficient priority encoding for null detection

## AI Prompts Used

### 1. Register Bit Packing
**Prompt:** "How to pack 2 values into one register"
- **Solution:** Used bit-level operations to pack needle and chunk_size into rs2
- **Implementation:** `unsigned long rs2_value = ((unsigned long)needle) | (((unsigned long)chunk_size & 0x7) << 8);`

### 2. C-Hardware Interface
**Prompt:** "Is it possible to have both character and integer values in the same RS2 bits?"
- **Answer:** Yes, using bitwise operations for bit field packing
- **Explanation:** Hardware sees rs2 as 64-bit integer, software assigns meaning to different bit ranges


### 3. Parallel Processing Optimization
**Prompt:** "How to implement parallel counting for variable string lengths"
- **Solution:** Compute match counts for all possible null terminator positions simultaneously
- **Benefit:** Eliminates pipeline stalls and reduces processing latency
- **Implementation:** Use vector operations and priority encoding for result selection