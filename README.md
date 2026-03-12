# OBD Sound

Android app that generates real-time engine audio from OBD-II vehicle data or a built-in demo simulation.

Physics-based engine model produces realistic RPM behavior including gear shifts, engine braking, startup/shutdown sequences, and progressive throttle/brake response.

## Cars

| Car | Engine | Gears | Transmission |
|-----|--------|-------|--------------|
| BAC Mono | 2.5L Turbo I4 | 6 | Hewland sequential |
| Ferrari 458 | 4.5L NA V8 | 7 | Getrag DCT |
| BMW M1 Procar | 3.5L NA I6 | 5 | ZF dog-engagement |

Each car has its own idle RPM, torque curve, engine braking characteristics, rev limiter, and gear ratios.

## Features

- **Real-time engine audio** — 4-layer sample crossfading with per-sample pitch interpolation
- **Physics simulation** — Position-based dynamics at 120Hz with 20 substeps per frame
- **Progressive controls** — Hold longer for more throttle/brake, quick tap for a blip
- **Startup/shutdown** — Cranking sequence with compression wobble, audible engine die-down
- **Gear shifting** — Throttle cut on upshift, rev-match blip on downshift, clutch bite on neutral engagement
- **OBD-II mode** — Connect to an ELM327 Bluetooth adapter to drive the sound from real vehicle RPM/throttle
- **Demo mode** — On-screen gas, brake, and shift controls

## Building

Open in Android Studio or build from the command line:

```
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Requires Android SDK 31+ and JDK 17.

## Credits

Engine audio simulation based on [markeasting/engine-audio](https://github.com/markeasting/engine-audio) (MIT License).

## License

MIT — see [LICENSE](LICENSE).
