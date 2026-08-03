# Kiwii-CraftRise-Jarload

<img src="https://i.imgur.com/fJGptBp.png" width="640">

---

A full-stack utility client suite for CraftRise (Rise Client 1.8.9) — ships a custom login launcher, a standalone injector, a native C++ DLL loader, and a Java agent that carries every module. The whole stack survives Rise's obfuscation churn through runtime structural mapping, so no rebuild is needed when Rise pushes a new build.

---

### 📦 What's In The Box

Three deliverables ship together, all cross-referencing the same output directory (`C:\kiwii\`):

- **Launcher** (`launcher/`) — A launcherless entry point. Skips the CraftRise launcher entirely: you type your CraftRise credentials into a native ImGui window, it authenticates against Rise's API, downloads/verifies the client, spawns `craftrise-x64.exe`, and **auto-injects `kiwii.dll` the moment the JVM is ready**. Single click from cold start to in-game with the cheat loaded.
- **Injector** (`kiwii/injector/`) — A standalone manual injector for the case where you want to open CraftRise the normal way and only attach Kiwii on demand. `inject.bat` finds the running `craftrise-x64.exe`, calls a PowerShell CreateRemoteThread + LoadLibrary payload, and pushes `kiwii.dll` into the process.
- **DLL + Agent** (`kiwii/cpp/` + `kiwii/java/`) — The native loader that installs hooks and attaches the JVM instrumentation agent, plus the Java agent that hosts every module, mapper, and renderer.

---

### 🗂️ Repository Layout

```
kiwii/                           ← cheat core
├── apache-maven-3.8.6/          bundled Maven, no system install required
├── build_all.cmd                builds jar + dll → C:\kiwii\
├── build_java.cmd               jar only
├── build_cpp.ps1                dll only (direct cl.exe, static CRT)
├── mappings.txt                 hardcoded fingerprint reference dump
│
├── cpp/                         native DLL (Kiwii.dll)
│   └── src/                     main.cpp + hook layer + JVM wire-up
│
├── java/                        Java agent (client.jar)
│   ├── pom.xml                  shade plugin, bundles ASM + Netty + LWJGL
│   └── src/
│       ├── me/kiwii/…           modules, mapping, ui, notifications
│       └── resources/fonts/     TTF baked into the jar
│
└── injector/                    standalone attach flow
    ├── Kiwii.dll                pre-built payload (also refreshed by build_cpp.ps1)
    ├── inject.bat               front-end, checks target proc, calls inject.ps1
    └── inject.ps1               P/Invoke: OpenProcess → VirtualAllocEx → CreateRemoteThread

launcher/                        launcherless login + auto-inject
├── build_launcher.ps1           cl.exe build with ImGui + DX11 backends
├── src/
│   ├── main.cpp                 DX11 swapchain, message loop, GUI mount
│   ├── mgui/                    ImGui elements + theme (colors, fonts, elements)
│   ├── auth/                    CraftRise API client + AES-encrypted payload builder
│   ├── crypto/                  AES-256, MD5, Base64 primitives
│   ├── inject/                  CreateRemoteThread injector triggered after JVM boot
│   ├── launch/                  CraftRise path discovery, java libs, game args, spawn
│   ├── net/                     TCP client used by the auth channel
│   └── util/                    logger, misc
├── vendor/imgui/                Dear ImGui + DX11 backend
├── bin/ • obj/ • launcher/      build artifacts
```

The two folders are independent — build them separately, ship them separately. They only share the `C:\kiwii\` output location at runtime.

---

## 🚀 Quick Start (Pre-Built Release)

The fastest path — grab the packaged zip, drop it on your C drive, done.

1. **Download** the latest release:
   [`kiwii.zip`](https://github.com/fantasywastaken/Kiwii-CraftRise-Jarload/releases/download/x/kiwii.zip)
2. **Extract** to `C:\` — the archive lays out `C:\kiwii\` with `client.jar`, `Kiwii.dll`, the launcher, and the injector already positioned:
   ```
   C:\kiwii\
   ├── client.jar
   ├── Kiwii.dll
   ├── launcher.exe        ← launcherless entry point
   ├── injector\
   │   ├── Kiwii.dll
   │   ├── inject.bat
   │   └── inject.ps1
   ├── fonts\              ← optional external font override
   └── logs\               ← generated at runtime (kiwii.log)
   ```
3. **Run** either:
   - `C:\kiwii\launcher.exe` — the launcherless option (login → auto-play → auto-inject)
   - `C:\kiwii\injector\inject.bat` — open CraftRise's own launcher, join a game, then run this to attach manually.

The zip is self-contained. No Visual C++ redistributable, no JDK, no Maven — everything the runtime needs is already bundled or statically linked.

---

## 🔨 Manual Build (From Source)

Rebuild the cheat core (DLL + agent jar):

```
cd kiwii
build_all.cmd
```

`build_all.cmd` will:

1. Auto-detect a JDK 8 from `%USERPROFILE%\.jdks\`, `Program Files\Java`, Corretto, Zulu, Adoptium — whichever it finds first.
2. Run `mvn clean package` via the bundled Maven — produces `C:\kiwii\client.jar` (~430 KB shaded jar with ASM, Netty, LWJGL, and the font atlas inside).
3. Invoke `build_cpp.ps1` — calls `cl.exe` directly (no vcxproj, no MSBuild dance), links with `/MT` static CRT, DEP + ASLR + high-entropy VA. Output: `C:\kiwii\Kiwii.dll`.
4. Copy both to `C:\kiwii\`.

For the launcher separately:

```
cd launcher
build_launcher.ps1
```

Which produces `launcher/launcher/launcher.exe` (bundled ImGui + DX11 backend, all resources embedded — no external DLLs, single binary).

---

### 🎛️ The Launcher (`launcher/`)

A **launcherless** entry point — you never open the CraftRise launcher when you use this. It replicates the auth + spawn logic in a single native binary.

**Flow:**

1. **UI** — Dear ImGui window (DirectX 11 backend) with fields for CraftRise username, password, RAM slider, and version selector. Full Turkish character support (Latin Extended-A range baked in), themed with the Kiwii palette.
2. **Auth** (`auth/launcher_api.hpp` + `auth/payload_builder.hpp`) — Builds the JSON handshake payload, AES-256 encrypts it with the shared launcher key, Base64 wraps it, sends over TCP (`net/tcp_client.hpp`) to the CraftRise auth endpoint, decodes the reply.
3. **Path Discovery** (`launch/craftrise_paths.hpp`) — Locates the CraftRise install directory, the bundled JRE, `javaw.exe`, `jvm.dll`, natives folder, libraries directory.
4. **Game Argument Build** (`launch/game_arguments.hpp` + `launch/java_libraries.hpp`) — Assembles the full `-Xmx`, classpath, natives, and Minecraft launch arguments identical to what the official launcher would spawn.
5. **Process Spawn** (`launch/rise_launch.hpp`) — Starts `craftrise-x64.exe` via `CreateProcessW` with a pipe on stdout so the launcher can monitor JVM boot progress.
6. **Auto-Inject** (`inject/dll_inject.hpp`) — Watches the target process for readiness (JVM up, world loaded), then triggers the injection: `OpenProcess` → `VirtualAllocEx` → `WriteProcessMemory(dll path)` → `CreateRemoteThread(LoadLibraryW)`. The DLL's `DllMain` fires, spins up its worker thread, and Kiwii is live.

**Result:** click *Login → Play* once, and roughly 4–6 seconds later you're in the game with the cheat already loaded. Zero extra steps.

**Credentials:** never persisted to disk in plaintext — the payload is built at send time, encrypted, and discarded from memory on completion. Nothing you type is logged.

---

### 💉 The Injector (`kiwii/injector/`)

For when you want to open CraftRise the traditional way and hand-attach Kiwii afterwards.

**`inject.bat`** — the front door. It:

- Resolves the DLL path next to itself
- Uses `tasklist` to confirm `craftrise-x64.exe` is running (early exit if not, with a clear message telling you to launch the game first)
- Hands off to `inject.ps1` with the DLL path and target process name

**`inject.ps1`** — the actual injector, pure PowerShell + P/Invoke:

- `Get-Process` to grab the PID and native handle
- Adds C# type at runtime with `Add-Type` for direct calls to `kernel32.dll`:
  - `OpenProcess(PROCESS_ALL_ACCESS, false, pid)`
  - `VirtualAllocEx(hProc, IntPtr.Zero, dllPathSize, MEM_COMMIT|MEM_RESERVE, PAGE_READWRITE)`
  - `WriteProcessMemory(hProc, allocAddr, dllPathBytes, size, out written)`
  - `GetProcAddress(GetModuleHandle("kernel32.dll"), "LoadLibraryW")`
  - `CreateRemoteThread(hProc, IntPtr.Zero, 0, loadLibraryAddr, allocAddr, 0, out threadId)`
  - `WaitForSingleObject(hThread, timeout)`
- Prints PID, handle, remote thread ID, and exit code on success

Exit codes are semantic — `0` OK, `2` DLL missing, `3` target process missing, non-zero for any Win32 error. `inject.bat` reads the exit code and prints a clean `INJECT OK` or `INJECT FAILED (n)` banner.

---

### 🧠 How It Works (Deep Dive)

**1. Native Loader (`kiwii/cpp/`)**

- `DllMain` on `DLL_PROCESS_ATTACH` calls `DisableThreadLibraryCalls` and spawns `MainThread` — real work happens off the loader lock to avoid LoaderLock deadlocks that plague DLL cheats.
- `MainThread` sleeps briefly to let the injector settle, then:
  - Wraps `AvamHook::Init()` in a SEH `__try/__except` — a bad init on some systems (older AMD CPUs mis-handling DR-register writes, for example) degrades gracefully instead of crashing the game.
  - Spins waiting for `jvm.dll` to be present in the process (module handle loop).
  - Starts a hardware-breakpoint refresh thread — every 1ms `AvamHook::RefreshHooks()` re-arms DR0–DR3 in case the JVM has swapped thread contexts. Wrapped in SEH so a stale context can't propagate a crash.
  - Waits for `opengl32.dll`, then initializes MinHook and installs the render entry point hook (`wglSwapBuffers`) — that's where every frame the DLL calls into the Java agent's HUD render path.
- `InitializeClient()` uses the JNI Invocation API to `AttachCurrentThread`, resolves `me/kiwii/loader/KiwiiInstrumentationAgent`, calls the `agentmain` variant, then invokes the Kiwii `Main.startClient` entry point.

**2. Instrumentation Agent (`me.kiwii.loader.KiwiiInstrumentationAgent`)**

- Manifest declares `Premain-Class` + `Agent-Class` — the same class can be used as a launch-time agent or a dynamic attach agent.
- Stores the `Instrumentation` reference the JVM hands over — later used by `ClassByteResolver` to walk loaded classes without needing them on disk.

**3. Class Byte Resolution (`me.kiwii.loader.ClassByteResolver`)**

Six-tier fallback chain, tried in order until one hits:

1. Kiwii's own byte cache (`ClassByteStore`)
2. Rise's patched loader — reflective `ClassLoader.getCustomClassByte(Class)` static method, if the target build patches its loader with that helper
3. Rise's patched loader map — reflective `ClassLoader.customClassBytes` static `Map<Class, byte[]>`
4. Standard `getResourceAsStream(className.class)`
5. `ProtectionDomain.getCodeSource()` — extract from the jar the class was loaded from
6. Known disk paths (`C:\kiwii\transformed`, `C:\kiwii\classes`, `C:\pulse\classes`) and known jars

**4. Runtime Mapping (`AutoMapper` + `MinecraftMapper` + `HardcodedUtils`)**

Rise renames every class, field, and method each build. Kiwii never hard-codes names — it fingerprints.

- Enumerates all `craftrise.*` / `crsecond.*` / `net.minecraft.*` classes.
- For each vanilla concept (e.g., `EntityPlayerSP`) it runs a structural test: superclass chain shape, exact field type counts, method return type + parameter type combinations, ASM bytecode probes for constant references. First match wins, gets cached in a `ConcurrentHashMap`, and every subsequent lookup is `O(1)`.
- `HardcodedUtils` provides a fallback: 240+ known-good instance fingerprints (`ClassX has 2 float fields adjacent to a String field`, etc.) applied in a background retry loop for 15 seconds after startup, catching classes that hadn't loaded during the initial ASM scan.

**5. Anti-Cheat Neutralization (`me.kiwii.Main.installAntiCheatBypass`)**

Rise ships a client-side tamper detector — a `Runnable` field on `ClientUtils` that a background thread invokes periodically. If tampering is detected, it writes `0xDEADBEEF` to a critical JVM address and takes the process down.

Kiwii discovers this field structurally (a `static final Runnable` on a class inside `crsecond.*` referenced from a specific caller chain), constructs a `Main.SAFE_RUNNABLE` no-op that satisfies the same contract, and swaps it in via reflection **before the halt-thread's first tick**. The check thread now runs harmlessly forever.

**6. Netty Packet Hook (`me.kiwii.packethook.PacketHook`)**

Once `NetworkManager` is mapped, a `ChannelOutboundHandlerAdapter` is injected into the discovered `channel` pipeline. Every outbound packet passes through `PacketHook.write()` — which dispatches to registered modules (e.g., `ReachModule.onPacketSend` for the `C0APacketAnimation` swing packet).

**7. Stealth Hooks (`AvamHook`)**

For entry points where a userland trampoline would be too loud, `AvamHook` uses hardware breakpoints:

- `AddVectoredExceptionHandler` registers a VEH filter.
- `NtGetContextThread` / `NtSetContextThread` (direct syscall stubs) writes DR0–DR3 to place breakpoints on target instruction addresses.
- When the CPU hits the breakpoint, `EXCEPTION_SINGLE_STEP` fires, the VEH handler adjusts `Rip` to the trampoline, and execution jumps into Kiwii's code.
- **No bytes are patched in the target's code pages** — a byte-scan integrity check by the AC finds nothing modified.

---

### 🎯 Modules

| Category | Module | Description |
|---|---|---|
| **Combat** | Reach | Extended attack reach with optional randomization between `Min Reach` and `Max Reach` |
| **Combat** | FastPlace | Removes the vanilla right-click block placement cooldown |
| **Player** | AutoRod | Instant fishing rod cast and reel on bite |
| **Player** | AutoFns | Automatic food or potion consumption at configurable health/hunger thresholds |
| **Player** | ChestStealer | Transfers all matching items out of an opened chest with humanized delay and jitter |
| **Movement** | SafeWalk | Auto-crouch at block edges to prevent falling |
| **Movement** | InvMove | Continues movement while a GUI (inventory, chest) is open |
| **Render** | HUD | Kiwii logo, ArrayList with sortable suffix, idle fade on inactivity |
| **Render** | ChestESP | 3D world-space box overlay on nearby chests with type-based color |
| **Render** | PlayerESP | 2D screen-projected bounding boxes with health/distance color modes |
| **Render** | Chams | Solid color player model rendered through walls via reflection-driven second render pass |
| **Render** | Tracers | Line from screen bottom-center to every visible player |
| **Render** | NameTags | Enhanced name label with health bar and distance |
| **Misc** | Notifications | Stack-based notification manager for module toggles, keybinds, and setting changes |

TabGUI is always mounted as the navigation surface — arrow keys walk categories → modules → per-option settings, `Enter` toggles or edits, and a keybind can be assigned per module from the settings panel.

---

### 🛠️ Technical

| Layer | Detail |
|---|---|
| **Languages** | C++17 (native loader + launcher + injector), Java 8 (agent) |
| **Build** | Direct `cl.exe` invocation with static CRT (no redistributable), Maven Shade Plugin for the agent |
| **Launcher GUI** | Dear ImGui + Direct3D 11 backend, single-binary output, embedded fonts |
| **Injection** | `KiwiiInstrumentationAgent` (`premain` / `agentmain`) attaches to the JVM once inside the process |
| **Mapping** | `AutoMapper` + `MinecraftMapper` + `HardcodedUtils` — structural fingerprinting, ASM bytecode analysis, fingerprint-driven runtime discovery |
| **Class Bytes** | `ClassByteResolver` walks Rise's patched loader map, `ProtectionDomain` code source, and known disk locations to reconstruct `.class` bytes for ASM |
| **Packet Hook** | Netty `ChannelOutboundHandlerAdapter` injected into the discovered `NetworkManager.channel` |
| **Userland Hooks** | MinHook trampoline for `wglSwapBuffers` — the render entry point |
| **Stealth Hooks** | `AvamHook` — VEH + DR0-DR3 hardware breakpoints, no bytes patched in target code pages |
| **Anti-Cheat** | Runtime discovery of `ClientUtils` tamper `Runnable`, swap with `Main.SAFE_RUNNABLE` before the halt-thread's first tick |
| **Rendering** | LWJGL 2.9.3 immediate-mode OpenGL rendered inline with Rise's own GL context, wrapped in `glPushAttrib` / `glPopAttrib` to leave state clean |
| **Font** | 32px TTF baked into a 1024-wide texture atlas on first draw (chars 32–1024 for full Latin + Turkish + Cyrillic coverage) with automatic disk-path fallback |
| **Auth Crypto** | AES-256-CBC for launcher handshake, MD5 for identifier hashing, Base64 for transport wrapping |

---

### 📊 Console Output (`C:\kiwii\logs\kiwii.log`)

```
[INFO] StartClient called from C++
[INFO] Loaded classes count: 7030
[INFO] MinecraftMapper input classes: 7030
[INFO] Class added: Minecraft -> craftrise.կ
[INFO] Class added: EntityPlayerSP -> craftrise.Օ
[INFO] Class added: FontRenderer -> craftrise.Ƞ
[INFO] Essential mappings: Minecraft=craftrise.կ, EntityPlayerSP=craftrise.Օ, FontRenderer=craftrise.Ƞ
[INFO] [HardcodedUtils] Applied 243 mappings (classes=49, fields+methods=194)
[INFO] [Main] anti-cheat bypass installed — AC Runnable neutralised
[INFO] ModuleManager: loaded 15 modules
[INFO] FontUtil loaded font from jar resource: /fonts/regular.ttf
[INFO] Kiwii initialized (15 modules)
```

If any mapping fails on the first pass, the 15-second `HardcodedUtils` retry loop reports its progress in the same file. If AvamHook trips an access violation on startup, the SEH wrapper logs `[-] AvamHook::Init access violation (continuing without it)` and Kiwii keeps running with the standard userland hooks only.

---

### ⚡ Performance

| Task | Cost |
|---|---|
| Launcher cold boot | ~1 s until UI is interactive |
| Launcher login → in-game | ~4–6 s (CraftRise auth + JVM boot dominates) |
| Cold-start mapping scan | ~2–3 s (once per session, off the render thread) |
| Per-frame HUD render | < 0.5 ms |
| Per-frame ESP + Chams + Tracers | ~1–2 ms with 8+ visible players |
| Netty packet interception | < 0.05 ms per packet |
| Runtime memory footprint | ~30–50 MB on top of Rise's baseline |

---

### 📋 Build Requirements

| Tool | Version | Purpose |
|---|---|---|
| Amazon Corretto JDK | 1.8 (Corretto 1.8.0_482 tested) | Java agent compile |
| Visual Studio Build Tools | 2022 / 2018 with MSVC 14.5x | DLL + launcher + injector compile |
| Windows 10 SDK | 10.0.26100 or newer | Native link |
| Maven (bundled) | 3.8.6 (ships in `kiwii/apache-maven-3.8.6/`) | Java packaging |

Nothing is required at runtime except Windows 10/11 x64 and the CraftRise install itself — the DLL links the CRT statically and the launcher embeds its GUI dependencies.

---

### 🙏 Special Thanks

- **[Ox85](https://github.com/Ox85)** — for continuous contributions and help throughout the project.
- **alperencontact** (Discord) — for guidance on hook protection and other essential internals.
- **CheatGlobal — [`craftrise-seiko-3-hitsiz-jarload`](https://cheatglobal.com/konu/craftrise-seiko-3-hitsiz-jarload.124493/)** — reference for the non-3-hit injector source.
- **CheatGlobal — [`pro-jar-source-developed-by-atapiro-coslant`](https://cheatglobal.com/konu/pro-jar-source-developed-by-atapiro-coslant.127797/)** — reference for defeating the 60 FPS anti-cheat throttle.

---

### ⚠️ Disclaimer

This project exists as educational research into JVM instrumentation, structural runtime mapping against obfuscated Java targets, native process injection, and userland / VEH-based hooking on Windows. Injecting third-party code into CraftRise violates its Terms of Service and will get your account permanently banned. Source is distributed as-is — the user who chooses to build, inject, or run it accepts full and sole responsibility for any account termination, sanction, or other consequence. The author and any contributors are not liable for how this code is used. Do not use it on any account you are not prepared to lose.
