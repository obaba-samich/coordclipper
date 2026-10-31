# Coord Clipper

A [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) addon for Minecraft 1.21.11 that pathfinds to
coordinates with a chain of vclips and hclips and lands you on the nearest solid ground.

## Usage

| Command                       | Description                                                                   |
|-------------------------------|-------------------------------------------------------------------------------|
| `.coordclip <x> <y> <z>`      | Clip to the coordinates. `~` relative coordinates work, e.g. `.coordclip ~ ~ ~100`. |
| `.coordclip stop`             | Stop clipping.                                                                |

## How it works

- **One movement packet per tick.** Like Meteor's `.hclip`, it only moves you client side and lets the game's own
  movement packet carry the move, so the server never sees extra packets.
- **Moves the server accepts.** Each move is at most 9.5 blocks (the server allows 10). The server replays a move
  vertically first, then horizontally, and ignores the vertical difference, so moves go through floors and ceilings,
  but the horizontal part has to be clear.
- **Ground to ground.** Every stop is on a solid block (underwater on the seabed is allowed on the way, the final landing
  is always dry). Fire, magma, campfires, cactus, berry bushes, wither roses, cobwebs, powder snow and lava are avoided.
- **Pathfinding.** A* over standing spots in loaded chunks, spread over several ticks so the game doesn't freeze.
  Only the chunk of the next move has to be loaded; for far targets it heads towards them as far as the loaded chunks
  reach and searches again as more load. Within 64 blocks of the landing spot it searches every route and can climb
  walls with short air stops, kept under the server's 80 tick floating limit.
- **No stopping.** Moves go out one per tick (the server allows one movement packet per tick, up to 10 blocks), staying
  close to the straight line to the target. The next part of the route is planned while the current one is
  walked and grows as more chunks load, so it goes as far as your render distance allows without pausing. Setbacks
  are caught as they arrive; it holds still where the server put you and retries, up to 5 times in a row.

## Troubleshooting

Problems are summarised in chat. Setbacks and other server problems are explained in detail in the game log (`.minecraft/logs/latest.log`), with lines
prefixed `[CoordClip]`. For setbacks the log shows the move, where the server put you, ping, server TPS and the likely
cause: a move over the distance limit, a block in the way, a blocked destination, server lag, or, when every vanilla
check passes, a plugin or anticheat.

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. Put it in your `mods` folder next to Meteor Client.
