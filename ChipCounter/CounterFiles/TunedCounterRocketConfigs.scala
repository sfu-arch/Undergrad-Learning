package chipyard

import org.chipsalliance.cde.config.{Config, Parameters}
import mytunedcounter._ // Import your own RoCC accelerator
import freechips.rocketchip.tile.{BuildRoCC, OpcodeSet}
import freechips.rocketchip.diplomacy._ 
import chisel3.experimental.SourceInfo
import chisel3.internal.sourceinfo.UnlocatableSourceInfo

class WithTunedCounter extends Config((site, here, up) => {
  case BuildRoCC => Seq((p: Parameters) =>
    LazyModule(new TunedCounter(OpcodeSet.custom2)(p))(ValName("TunedCounter"), UnlocatableSourceInfo)
  )
})

class TunedCounterRocketConfig extends Config(
  new WithTunedCounter ++ 
  new freechips.rocketchip.subsystem.WithNBigCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig
)