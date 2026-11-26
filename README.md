# Oxidyn

A NeoForge 1.21.1 mod that moves a few of Minecraft's heaviest CPU paths into
Rust. Java calls the Rust code through the Foreign Function & Memory API
(`java.lang.foreign`, Project Panama). There is no JNI anywhere.

The mod doesn't add, remove or change any gameplay. Worlds generate exactly
the same as without it, and the files it writes are normal vanilla files.

## Real-world results

Pregenerating 1,681 fresh overworld chunks with NeoForge's
`/neoforge generate` on a dedicated server (Java 22, 8 vCPU cloud VM). The
same seed was used each time, two runs with Oxidyn and two without,
alternating:

| | Vanilla | Oxidyn | |
|---|---:|---:|---:|
| Server limited to 2 cores, wall time | 63.3 s | 47.5 s | **1.33x faster** |
| Server limited to 2 cores, CPU time | 87.4 s | 70.4 s | **24% less CPU** |
| All 8 cores, wall time | 28.4 s | 27.0 s | 1.05x |
| All 8 cores, CPU time | 90.1 s | 76.0 s | 16% less CPU |

With plenty of cores, the pregenerator is paced by NeoForge's scheduling: it
keeps at most 32 chunks in flight and routes each step through the server
thread. Saving CPU barely changes wall time there, but it leaves the CPU free
for everything else the server does. When CPU is the limit, as on a typical
2-core host, the saving shows up as speed.

Almost all of the gain comes from the native chunk fill (below). The noise
fill step alone runs about 2.1x faster than vanilla in isolation
(`ChunkFillBench`: 13.4 ms per chunk against 6.4 ms). The remaining time in
chunk generation is mostly surface rules, features and lighting, which are
still vanilla.

## What it replaces

| Area | Side | Vanilla | Oxidyn |
|---|---|---|---|
| **Chunk noise fill** (terrain shape, caves, aquifers, ore veins, structure terrain adaptation) | server / singleplayer | `NoiseChunk` evaluates the density-function tree block by block, one virtual call per node | the dimension's whole noise router is compiled once into a flat program. Rust then fills each chunk in one call, a whole array of points per node, and Java just writes the blocks |
| Worldgen noise (`noise`, `shifted_noise`, `BlendedNoise`) | server / singleplayer | `fillArray` samples one point at a time | the whole array goes to Rust in one call, 4 points at a time |
| Translucent resorting (water, glass, ice...) | client | float[] + boxed-comparator merge sort + 6 `memPut` calls per quad | stable radix sort that writes the index buffer straight into native memory |
| Region file compression (deflate, the default) | server / singleplayer | `java.util.zip` streams | libdeflate, whole buffer at once |
| Packet compression | both | `Deflater`/`Inflater` in 8 KB steps through byte[] copies | libdeflate, straight from one ByteBuf's memory to the other's |

Microbenchmarks of the individual pieces, from `./gradlew test -Pbench
-Prun_jdk=25` on the same VM. These are hand-rolled benchmarks, not JMH.
Treat the numbers as ballpark.

| Benchmark | Vanilla | Oxidyn | Speedup |
|---|---:|---:|---:|
| noise fill step, one overworld chunk | 13.4 ms | 6.4 ms | 2.1x |
| normal noise, 9 octaves, 4 columns of cell corners | 78 µs | 51 µs | 1.5x |
| blended noise, 4 columns of cell corners | 121 µs | 103 µs | 1.2x |
| translucent resort, 4096 quads | 116 µs | 13 µs | 8.6x |
| zlib compress, 180 KB chunk-like data | 1342 µs | 396 µs | 3.4x |
| zlib decompress, same data | 237 µs | 67 µs | 3.6x |

The noise speedups are modest, and the reason is worth knowing. C2 compiles
the vanilla noise code very well. A straight scalar Rust port was *no faster*,
and for blended noise it was actually slower. The gain only shows up once
four points are evaluated side by side, so the long chains of dependent loads
and lerps can overlap. Chunk generation does plenty of other work besides
noise. That is why the chunk fill moves the whole step into Rust rather than
just the noise calls.

## Things it deliberately doesn't do

Crossing into native code costs a few nanoseconds per call. That's nothing
for a batch of 200 noise samples, but it would eat the whole gain on
something like a single block-state lookup. So only work that can be batched
went native. The lighting engine, pathfinding and entity collisions are big
CPU users too, but they are tangled up with live game objects in ways that
don't batch well. They are not part of this mod.

## Correctness

- **The chunk fill is bit-for-bit identical.** It reproduces how vanilla's
  `NoiseChunk` evaluates density functions in each phase: cell corners,
  per-cell cache, per-block loop and single points. That includes the two
  different interpolation orders, which round differently. Tests compare
  every block and fluid-update flag against vanilla's own `NoiseChunk` for
  overworld, large biomes, amplified, nether, caves and floating islands,
  near spawn and out to 28.8 million blocks, with and without structure
  terrain adaptation.
- **Each dimension is checked again at runtime.** The first time a dimension
  uses the native fill, one chunk is generated both ways and compared block
  by block. On any difference that dimension stays on vanilla and an error is
  logged. This covers whatever datapacks and mods change.
- **Unknown worldgen falls back.** Density-function types Oxidyn doesn't
  know, cache placements whose result would depend on evaluation order,
  blending next to pre-1.18 chunks, and the End's islands (not ported yet)
  all stay on vanilla.
- **Noise is bit-for-bit identical.** The Rust code performs the same
  floating point operations in the same order as the Java code. It matches
  Java's `(int)` cast and integer overflow behaviour and uses no FMA. The
  same seed gives the same world with or without the mod.
- **Startup self-test.** When the mod loads, it runs each native path against
  vanilla on a few thousand inputs: noise compared bit for bit, sort order
  compared exactly, zlib round-tripped in both directions. Any feature that
  doesn't match gets switched off with an error in the log.
- **Fallbacks everywhere.** On a native zlib error, the code drops back to
  `java.util.zip` for that one buffer. On unusual packets (oversized,
  composite buffers, bad streams), vanilla's own code handles them, so vanilla
  error messages are kept.
- **Compatible output.** Region files are plain zlib, so you can remove the
  mod at any time. Compressed packets are plain zlib too, so a modded server
  talks fine to vanilla clients and proxies, and the other way round.

## Requirements

- NeoForge 21.1.x for Minecraft 1.21.1
- **Java 22 or newer** for the native code. The FFM API was still a preview
  feature in Java 21, the version Minecraft ships with. On Java 21 the mod
  loads, logs a warning and does nothing. Most launchers let you pick the
  Java installation per profile.
- Bundled libraries for Windows x86_64, Linux x86_64/aarch64 and macOS
  x86_64/aarch64 (CI builds). A jar you build locally only contains your own
  platform.

On Java 22+ the JDK prints this once at startup:

```
WARNING: A restricted method in java.lang.foreign.SymbolLookup has been called
```

That is expected. Loading a native library is a "restricted" operation. The
`--enable-native-access=<module>` flag the warning suggests doesn't reach mods,
because FML loads them into their own module layer.

## Usage

Check that it's active with `/oxidyn`:

```
Oxidyn native library: loaded liboxidyn.so (abi 1, Java 22)
Active: noise=true chunkFill=true translucentSort=true regionZlib=true (level 6) networkZlib=true (level 6)
```

The config lives in `config/oxidyn-common.toml`:

```toml
[worldgen]
    nativeNoise = true
    nativeChunkFill = true
[rendering]
    nativeTranslucentSort = true
[compression]
    nativeRegionCompression = true
    regionCompressionLevel = 6      # 1-12, vanilla zlib uses 6
    nativeNetworkCompression = true
    networkCompressionLevel = 6
```

System properties, mostly for debugging:

- `-Doxidyn.disable=true` never loads the native library.
- `-Doxidyn.library=/path/to/liboxidyn.so` loads that file instead of the
  bundled one.

The bundled library is extracted to `.oxidyn/natives/<hash>/` in the game
directory.

## Compatibility notes

- **Sodium / Embeddium** replace vanilla chunk rendering, so the translucency
  hook just never fires. Nothing breaks.
- **Other worldgen optimisation mods** (C2ME and friends) may hook the same
  noise classes. If another mod also adds `fillArray` to `BlendedNoise`, mixin
  will complain at startup.
- **Proxies** (Velocity etc.) don't care. The packets are standard zlib.

## Building

You need:

- JDK 21 to run Gradle. JDK 22 is also needed for `src/panama`; Gradle
  downloads it on its own if it can't find one (`panama_jdk` in
  `gradle.properties`).
- A stable Rust toolchain, plus a C compiler for libdeflate (comes with
  Xcode CLT / build-essential / MSVC).

```sh
./gradlew build                  # builds the Rust lib for your platform and bundles it
./gradlew build -PskipCargo      # use whatever is already in native/dist/<os>-<arch>/
./gradlew test -Prun_jdk=22      # tests need a 22+ runtime, otherwise they're skipped
./gradlew test -Pbench -Prun_jdk=22 --tests dev.oxidyn.Bench
./gradlew runClient -Prun_jdk=22
cd native && cargo test          # the Rust-side tests
```

The JUnit tests run inside FML with the mixins applied. They call the real
mixin'd methods and compare against vanilla.

## How it's put together

```
native/                      Rust cdylib
  src/noise.rs               ImprovedNoise/PerlinNoise/NormalNoise/BlendedNoise port + 4-lane versions
  src/gen/                   native doFill: density program, batch evaluator, aquifer, beardifier, ore veins
  src/sort.rs                translucent quad sort + index buffer writer
  src/deflate.rs             zlib via libdeflate
src/main/java/dev/oxidyn/    Java 21: mixins, config, glue (no java.lang.foreign here)
  ffi/NativeBackend.java     the interface everything else talks to
  ffi/Natives.java           extracts the library, checks the Java version, loads the backend
src/panama/java/             Java 22: the only code that touches java.lang.foreign
  PanamaBackend.java         Linker.nativeLinker() + downcallHandle for every export
```

Some details:

- **Why two source sets?** Minecraft classes are Java 21, and on 21 the
  `java.lang.foreign` API is preview-only. The panama source set is compiled
  with `--release 22`. `Natives` loads it reflectively, and only after it has
  checked `Runtime.version()`, so Java 21 never sees a class file it can't
  read.
- **Noise descriptions** are flattened once per `NormalNoise`/`BlendedNoise`
  into an `Arena.ofAuto()` segment (permutation tables, offsets, per-octave
  factors). The segment is freed when the noise object is garbage-collected.
- **Noise and sort calls** use `Linker.Option.critical(true)`. They pass the
  Java `double[]`/`float[]` arrays as heap segments, with no copying. That's
  only OK because those calls take microseconds. zlib can take milliseconds,
  so it uses normal downcalls and off-heap buffers instead (direct ByteBufs
  are passed straight through, heap arrays get copied).
- **The chunk fill** gets the noise router as a stream of words, built by
  `DensityCompiler` from vanilla's density-function records (read through
  reflection, since most are package-private). Rust checks the graph,
  evaluates each node over a whole array of points, and keeps vanilla's lazy
  branches (`range_choice`, `mul` by zero, `min`/`max` bounds, spline
  segments) with selection vectors. One downcall per chunk returns a byte per
  block. Java writes those blocks in vanilla's exact loop order, so
  heightmaps and post-processing marks come out the same.
- **Batching worldgen noise** doesn't walk `NoiseChunk`'s context provider
  itself. It runs the provider's normal `fillAllDirectly` loop over a fake
  density function that only records coordinates, so all of `NoiseChunk`'s
  internal counters end up in exactly the state vanilla leaves them in.

## License

MIT, see [LICENSE](LICENSE).
