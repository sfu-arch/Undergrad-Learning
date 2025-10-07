package mytunedcounter

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.rocket._
import freechips.rocketchip.diplomacy.LazyModule

// LazyRoCC definition (connects to CPU)
class TunedCounter(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  override lazy val module = new TunedCounterModule(this)
}

// The implementation
class TunedCounterModule(outer: TunedCounter)(implicit p: Parameters) extends LazyRoCCModuleImp(outer) {
  val cmd = Queue(io.cmd)
         
  // States for our accelerator FSM
  val idle :: fetch :: count :: done :: Nil = Enum(4)
  val state = RegInit(idle)
  
  // Registers to hold working data
  val needle = Reg(UInt(8.W))         // Character to search for
  val chunk_size = Reg(UInt(4.W))     // From 1 to 8 characters
  val stringAddr = Reg(UInt(64.W))    // Current address being read
  val charCount = Reg(UInt(64.W))     // Count of matches found
  val rd = Reg(UInt(5.W))             // Destination register
  val maxIters = 1000.U               // Maximum iterations to prevent infinite loops
  val iterCount = Reg(UInt(64.W))     // Iteration counter
  
  // Tag for memory requests (allows matching responses)
  val memRespTag = 0.U(6.W)

  // Setup default signals
  io.busy := state =/= idle
  io.interrupt := false.B
  
  // Memory interface defaults
  io.mem.req.valid := false.B
  io.mem.req.bits.addr := stringAddr
  io.mem.req.bits.tag := memRespTag
  io.mem.req.bits.cmd := M_XRD // Read command
  io.mem.req.bits.signed := false.B
  io.mem.req.bits.dprv := PRV.M.U  // Supervisor privilege level
  io.mem.req.bits.phys := false.B
  io.mem.req.bits.no_alloc := false.B
  io.mem.req.bits.no_xcpt := false.B
  io.mem.s1_kill := false.B
  io.mem.s1_data.data := DontCare
  io.mem.s2_kill := false.B
  io.mem.keep_clock_enabled := true.B
  // Calculate memory request size (power of two covering chunk_size)
  def minPow2(x: UInt): UInt = {
    Mux(x <= 1.U, 0.U,            // If 1 char get 2^0 = 1 byte
    Mux(x <= 2.U, 1.U,            // Else if 2 chars get 2 bytes
    Mux(x <= 4.U, 2.U, 3.U)))     // Else if 4 chars get 4 bytes, else get 8 bytes
  }
  val memReqSize = minPow2(chunk_size)
  io.mem.req.bits.size := memReqSize

  // Response defaults
  io.resp.valid := false.B
  io.resp.bits.rd := rd
  io.resp.bits.data := charCount
  cmd.ready := state === idle
  
  // State machine implementation
  switch(state) {

    /*---------------------------------------------------------------------------------*/
    // Idle
    is(idle) {
      when(cmd.valid) {
        // Decode the instruction
        rd := cmd.bits.inst.rd
        stringAddr := cmd.bits.rs1
        needle := cmd.bits.rs2(7, 0) // We only need the lower 8 bits for a char
        chunk_size := cmd.bits.rs2(11, 8) // Get chunk size (1-8 characters)

        // Reset counters
        charCount := 0.U
        iterCount := 0.U
        
        // Check for invalid chunk_size
        when(cmd.bits.rs2(11, 8) === 0.U || cmd.bits.rs2(11, 8) > 8.U) {
          charCount := "hFFFFFFFFFFFFFFFF".U // Return all-ones for error (negative 1)
          state := done
        }.otherwise {
          state := fetch
        }
      }
    }
    
    /*---------------------------------------------------------------------------------*/
    // Fetch next byte from memory
    is(fetch) {
      // Check iteration limit
      when(iterCount >= maxIters) {
        state := done
      }.otherwise {
        // Request to read a byte from memory
        io.mem.req.valid := true.B
        when(io.mem.req.ready) {
          // Wait for response next cycle
          state := count
        }
      }
    }
    
    /*---------------------------------------------------------------------------------*/
    // Logic to count occurrences of the needle in the fetched data
    is(count) {
      // Increment iteration counter 
      iterCount := iterCount + 1.U
      
      when(io.mem.resp.valid && io.mem.resp.bits.tag === memRespTag) {
        // Check if there was an exception
        when(io.mem.s2_xcpt.asUInt.orR) {
          // Memory exception occurred, abort and return current count
          charCount := "hFFFFFFFFFFFFFFFF".U // Return all-ones for error (negative 1)
          state := done
        }.otherwise {
          // Get the chunk of data read from memory
          val data = io.mem.resp.bits.data
          val bytesToProcess = chunk_size 
          // Extract the relevant bytes from the response
          val bytes = Wire(Vec(8, UInt(8.W)))
          for (i <- 0 until 8) {
            bytes(i) := data((i+1)*8-1, i*8)
          }

          // =================================================================
          // 1. Parrarell Processing: Compute ALL possible outcomes simultaneously
          // =================================================================
          
          // Compute match count for each possible scenario:
          // - Process 0 chars (null at position 0)
          // - Process 1 char  (null at position 1) 
          // - Process 2 chars (null at position 2)
          // - etc.

          val matchCounts = Wire(Vec(9, UInt(4.W))) // Max 8 possible chunk sizes
          val shouldStop = Wire(Vec(9, Bool()))

          for (processCount <- 0 until 9) {
            // Use a Wire to hold the match count for this process count
            val matches = Wire(UInt(4.W)) // 4 bits for count 
            
            // Count all possible matches for this process count
            val mathVector = VecInit((0 until 8).map {  i =>
              val withinProcessCount =  if (processCount == 0) false.B else i.U < processCount.U
              val withinChunk = i.U < bytesToProcess
              val isMatch = bytes(i) === needle
              withinProcessCount & withinChunk & isMatch // Use Chisel & operator
            })

            // Sum the matches for this process count
            matches := PopCount(mathVector.asUInt)
            matchCounts(processCount) := matches // Store the count

            // Should we stop processing?
            if (processCount == 0) {
              shouldStop(processCount) := bytes(0) === 0.U // Stop if first byte is null
            } else {
              val nullAtThisPos = (processCount <= 8).B && (bytes(processCount-1) === 0.U)
              val reachedChunkLimit = processCount.U >= bytesToProcess
              shouldStop(processCount) := nullAtThisPos || reachedChunkLimit
            }
          }

          // =================================================================
          // 2. Mux Selection: Choose the correct outcome based on actual data
          // =================================================================
          
          // Find where the first null character is (if any)
          val nullPositions = VecInit(
            (0 until 8).map(i => 
            (bytes(i) === 0.U) & (i.U < bytesToProcess))
            )
          
          val hasNull = nullPositions.asUInt.orR // Check if any null found
          val firstNullPos = PriorityEncoder(nullPositions) // Get first null position

          // Select the appropriate result
          val selectedMatchCount = Mux(
            hasNull,
            matchCounts(firstNullPos), // Only process up to first null
            matchCounts(bytesToProcess) // Process full chunk size for match count
          )

          // Stopping
          val selectedShouldStop = hasNull

          // =====================================================
          // 3. Update State: Based on selected result
          // =====================================================
          charCount := charCount + selectedMatchCount // Update total count

          when(selectedShouldStop) {
            state := done // Stop processing if null found or limit reached
          }.otherwise {
            // Prepare for next fetch
            stringAddr := stringAddr + bytesToProcess // Move to next chunk
            state := fetch // Go back to fetch next chunk
          }
        } 
      }
    }

/*---------------------------------------------------------------------------------*/
    // Command is complete, send response
    is(done) {
      // Send response back to CPU
      io.resp.valid := true.B

      when(io.resp.ready) {
        // Command is complete, return to idle
        state := idle
      }
    }
  }
}