# Kinetics

Kinetics is a rigid-body physics plugin for Paper, built on Jolt. It has a small API for putting displays and mobs into bounded scenes that collide with the Minecraft world.

This is still a work in progress. Expect the API to move around.

## Demonstrations

<p align="center">
  <img width="49%" alt="spinning-slimeball" src="https://github.com/user-attachments/assets/82bbd6e4-88d8-4690-a7f9-6c8951f613fd" />
  <img width="49%" alt="impact" src="https://github.com/user-attachments/assets/d1e3191e-0f94-4e1d-bf37-fc69cffaeda4" />
</p>



## Requirements

- Java 21
- Paper 1.21.11
- PacketEvents 2.13.0 or newer
- x86-64 Windows or Linux

Kinetics only ships with Jolt natives for x86-64 Windows and Linux right now. It will not load on other platforms.

## Building

```sh
./gradlew build
```

Gradle puts the finished plugin jar in `kinetics-plugin/build/libs`. Drop it into the server's `plugins` directory alongside PacketEvents, then restart the server.

For a local test server, run:

```sh
./gradlew :kinetics-plugin:runServer
```

That task downloads Paper and PacketEvents for the local server.

## Trying the demo

The demo is off by default. Set `demo: true` in `plugins/Kinetics/config.yml`, restart the server, and run one of these commands as an operator:

```text
/kinetics demo sampler
/kinetics demo spectacle
/kinetics demo stop
```

## Code

Other plugins compile against `kinetics-api`. The Paper integration and Jolt runtime live in `kinetics-plugin`.
