# Security Policy

Oxidyn loads a native library into the game and hands it data from region
files and network packets, so memory-safety bugs here can matter more than
in a typical Java mod. Reports are taken seriously.

## Supported versions

Oxidyn is in alpha. Only the latest release gets fixes.

| Version | Supported |
|---|---|
| latest 0.1.x alpha | yes |
| anything older | no |

## Reporting a vulnerability

**Please don't open a public issue for security problems.**

Report privately through GitHub instead: go to the repository's **Security**
tab and choose **Report a vulnerability**. Include:

- what the problem is and what an attacker could do with it
- the Oxidyn, Minecraft, NeoForge and Java versions, and your OS
- steps to reproduce, ideally with a sample file or packet
- whether the issue still happens with the related feature switched off in
  `config/oxidyn-common.toml`

This is a one-person project, so I can't promise a fixed turnaround, but
I'll acknowledge the report as soon as I can and keep you updated. Once a
fix is released, I'm happy to credit you in the release notes if you'd like.

## What's in scope

The things I'm most interested in:

- **Memory safety in the native library.** Any input that makes the Rust
  code read or write out of bounds, crash, or hang.
- **Untrusted input:** region files (`.mca`, `.mcc`) and compressed network
  packets reaching the native zlib code. For example, decompression bombs
  that get past the size limits, or malformed streams that crash instead of
  falling back to vanilla.
- **Library loading.** The bundled library is extracted to
  `.oxidyn/natives/<hash>/` in the game directory. Issues with that, like
  loading a library from somewhere it shouldn't, are in scope.
- **The `-Doxidyn.library` override** being reachable by anything other than
  the person launching the game.

## What's out of scope

- Bugs that need someone to already control your game directory, JVM
  arguments or mods folder. At that point they can run any code anyway.
- Worldgen that differs from vanilla. That's a correctness bug, so please
  report it as a normal issue.
- Problems in Minecraft, NeoForge or other mods that also happen without
  Oxidyn installed.

## Verifying downloads

Only download Oxidyn from the GitHub releases page or the official Modrinth
project. Release jars are built by GitHub Actions from the tagged commit, and
the source for everything bundled in the jar, including the native library,
is in this repository. If you'd rather not trust a prebuilt binary, build it
yourself with `./gradlew build`.
