# Contributing to Oxidyn

Thanks for wanting to help. Bug reports, test results from hardware I don't
have, and pull requests are all welcome.

## Reporting bugs

Open an issue and include:

- your `logs/latest.log`, or at least every line containing `Oxidyn`
- your OS, CPU and Java version (the third line of the log has the Java one)
- your mod list
- for worldgen issues: the seed, the coordinates (F3), and whether it still
  happens with `nativeChunkFill = false` in `config/oxidyn-common.toml`

If the game crashes, attach the crash report from `crash-reports/`.

Security problems go through [SECURITY.md](SECURITY.md), not public issues.

## Building

You need:

- JDK 21 to run Gradle. JDK 22+ is also needed for `src/panama`; Gradle
  downloads it if it can't find one.
- Stable Rust, plus a C compiler for libdeflate (build-essential, Xcode
  command line tools or MSVC).

```sh
./gradlew build                  # builds the Rust library for your platform and bundles it
./gradlew test -Prun_jdk=22      # tests need a 22+ runtime, otherwise they're skipped
cd native && cargo test          # Rust-side tests
./gradlew runClient -Prun_jdk=22
```

A local build only bundles the library for your own platform. CI builds all
five.

## The one hard rule: output must match vanilla exactly

Oxidyn's whole promise is that a world generated with it is identical to one
generated without it. Any change that touches noise, density functions,
aquifers, ore veins or the beardifier has to keep that true, bit for bit.

That means:

- Same floating point operations, in the same order, as the Java code. No
  reassociating, no `mul_add`, no "equivalent" rewrites.
- Java semantics for the awkward bits: `(int)` casts, integer overflow,
  `Math.min`/`Math.max` with `-0.0` and NaN, float constants promoted to
  double.
- If vanilla caches something in a way that changes results, either
  reproduce it or reject that case so it falls back to vanilla.

`ChunkFillParityTest` and `NoiseParityTest` compare against vanilla's own
code. They must pass, and if you add a new path, add a parity test for it.
"Close enough" doesn't count.

Faster but different is a bug. Slower but correct is a starting point.

## Performance claims

If a PR says something is faster, include how you measured it (machine,
Java version, which benchmark) and the before and after numbers.
`./gradlew test -Pbench -Prun_jdk=22` runs the existing benchmarks, and
`/oxidyn stats` measures the chunk fill in game. Scalar Rust is often *not*
faster than what the JIT makes of the same Java, so please measure rather
than assume.

## Native code

- Everything Java calls lives behind `NativeBackend`. Only `src/panama` may
  use `java.lang.foreign`, because the main source set has to stay loadable
  on Java 21.
- If you change an exported function's signature, bump `ABI_VERSION` in both
  `native/src/lib.rs` and `Natives.java`.
- `unsafe` Rust needs a `# Safety` comment saying what the caller guarantees.
  Java must validate lengths before a downcall; Rust trusts what it's given.
- Use `Linker.Option.critical` only for calls that finish in microseconds.

## Commits and pull requests

- Keep commits small and focused, one logical change each.
- Write commit messages like a person would: a short summary line, and a body
  explaining *why* if it isn't obvious.
- Comments should explain what the code can't say by itself. Skip the ones
  that just restate the next line.
- Run the tests before opening the PR, and say in the description what you
  tested and on what platform.

## Using AI tools

AI assistants are welcome here. A lot of this codebase is exactly the kind of
careful, tedious porting they can help with. But you're the author of
whatever you submit, so use them responsibly:

- **Understand every line you submit.** If you can't explain why a change is
  correct, it isn't ready.
- **Verify, don't trust.** AI tools happily produce code that looks right and
  is subtly off, which is the worst possible bug in a project built on
  bit-exact parity. The parity tests are the bar, not the model's confidence.
- **Never submit benchmark numbers you didn't measure yourself.** Made-up
  numbers or "should be faster" claims will be closed.
- **Check licenses.** Don't paste in code from other projects unless its
  license allows it, and keep the attribution it requires.
- **Keep it reviewable.** A huge AI-generated diff nobody has read is harder
  to review than to write. Smaller PRs get merged faster.
- **No AI-written issue spam.** Reports should describe something that
  actually happened, with the log.

You don't have to announce that you used AI. You do have to stand behind the
result.

## License

By contributing you agree that your contribution is licensed under the
project's [MIT License](LICENSE).
