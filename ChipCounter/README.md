# Tuned Counter RoCC Accelerator 

## Purpose of Accelerator

Efficiently count occurrences of a character in a string using a custom hardware accelerator, with tunable chunk size for performance tuning.

## Setup Steps

1. Follow the initial [Chipyard repository setup guide](https://chipyard.readthedocs.io/en/stable/Chipyard-Basics/Initial-Repo-Setup.html).`
> **Note:** If you do not require FireSim or FireMarshal, you can skip steps 6–9 to speed up setup:
>
> ```sh
> ./build-setup.sh riscv-tools -s 6 -s 7 -s 8 -s 9   # or esp-tools -s 6 -s 7 -s 8 -s 9
> ```
2. Copy the files in 'CounterFiles' into your Chipyard repository:
  - Copy the entire `rocc-tuned-counter` folder from this repo into the `generators` directory of your Chipyard repo:
      ```
      /path/to/chipyard/generators/
      ```
  - Copy the config file(s) `TunedCounterRocketConfigs.scala` into the `generators/chipyard/src/main/scala/config` directory of your Chipyard repo:
      ```
      /path/to/chipyard/generators/chipyard/src/main/scala/
      ```
  - Copy `charcount_tuned.c` into the `tests` directory of your Chipyard repo:
      ```
      /path/to/chipyard/tests/
      ```
  Replace `/path/to/chipyard/` with the actual path to your Chipyard repository.
  
3. Register your accelerator in Chipyard's build system:
   - Open the `build.sbt` file in the root of your Chipyard repository.
   - Add a new project definition for your accelerator after the other accelerator projects in "// -- Chipyard-managed External Projects --":
     ```scala
     lazy val rocc_tuned_counter = (project in file("generators/rocc-tuned-counter"))
       .dependsOn(rocketchip)
       .settings(libraryDependencies ++= rocketLibDeps.value)
       .settings(commonSettings)
     ```
   - Add `rocc_tuned_counter` to the `.dependsOn(...)` list in the `lazy val chipyard` project definition.
   - Save the file.
4. Add the new C test to the Makefile in the `tests` folder of your Chipyard repository:
   - Open `/path/to/chipyard/tests/Makefile`.
   - Find the `PROGRAMS = ...` line in "# SoC Settings"
   - Add `charcount_tuned` to the list, so it looks like:
     ```
     PROGRAMS = pwm blkdev accum charcount ... charcount_tuned
     ```
   - Save the file.

## Running The Accelerator (always do `source env.sh` in root before doing other commands)
1. Make the c test files
```
cd test/
make
```
2. Make the configs file and run the accelerator
```
cd sims/verilator/ # from chipyard root
make CONFIG=TunedCounterRocketConfig
make run-binary BINARY=../../tests/charcount_tuned.riscv
``` 
> ***Note*** You can combine make config and run-binary into one command: 
> ```
> make run-binary CONFIG=TunedCounterRocketConfig BINARY=../../tests/charcount_tuned.riscv
> ```

## Resources
- [Chipyard Document (Stable Version)](https://chipyard.readthedocs.io/en/stable/index.html)
> ***Note:*** Sections Helpful for this Counter<br>
> [6. Customization](https://chipyard.readthedocs.io/en/stable/Customization/index.html)<br>
> [6.6. Adding a RoCC Accelerator](https://chipyard.readthedocs.io/en/stable/Customization/RoCC-Accelerators.html)<br>
> [6.12 Memory Hierarchy](https://chipyard.readthedocs.io/en/stable/Customization/Memory-Hierarchy.html)
- [Chipyard Basics Tutorial Video 2023](https://m.youtube.com/watch?v=EXbs5VSv19c)
- [Chipyard Google Group](https://groups.google.com/u/2/g/chipyard)
