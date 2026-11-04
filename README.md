# Coord Clipper

A [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) addon that pathfinds to coordinates with a chain
of vclips and hclips and lands you on the nearest solid ground.

Built for Minecraft 1.21.11.

## Usage

| Command                  | Description                                                                         |
|--------------------------|-------------------------------------------------------------------------------------|
| `.coordclip <x> <y> <z>` | Clip to the coordinates. `~` relative coordinates work, e.g. `.coordclip ~ ~ ~100`. |
| `.coordclip stop`        | Stop clipping.                                                                      |

Settings are in the **coord-clipper** module (Movement category). The module doesn't need to be enabled.

## How it works

- **One movement packet per tick.** Like Meteor's `.hclip`, it only moves you client side and lets the game's own
  movement packet carry the move, so the server never sees extra packets.
- **Moves the server accepts.** Each move is at most 9.5 blocks (the server allows 10). The server replays a move
  vertically first, then horizontally, and ignores the vertical difference, so moves go through floors and ceilings,
  but the horizontal part has to be clear. With **unlimited-vertical** on, clips straight up or down can be any length,
  so it clips to the right height in one move and then across.
- **Ground to ground.** Stops are on something solid, including partial blocks like mud, soul sand, slabs, farmland
  and snow layers. Underwater on the seabed is fine on the way, the final landing is always dry. Fire, magma,
  campfires, cactus, berry bushes, wither roses, cobwebs, powder snow and lava are avoided.
- **Over obstacles.** Short chains of stops in mid-air take it over trees, canopies and walls: up above everything
  nearby, across, and back down. They're kept short so the server doesn't kick you for floating.
- **Pathfinding.** A* over standing spots in loaded chunks, planned while the current route is walked. Far targets are
  approached along the straight line, as far as your loaded chunks reach. If the quick search finds nothing it searches
  every route; if it's left hanging in mid-air it drops to the ground below.
- **Setbacks.** If the server puts you back it holds still and retries from there, up to **max-setbacks** times in a
  row. It gives up after **stuck-timeout** seconds without finding a way closer.
- **Air.** When you're down to **air-bubbles** bubbles underwater, it clips to open air (the water surface or dry ground)
  and waits until your air is full. If there's none in reach and **use-doors** is on, it places a door from your
  inventory where you stand: doors can't hold water, so you can breathe inside it.
- **Hunger.** When you're down to **hunger-bars** bars, it stops and eats until **eat-until** bars, skipping the
  **food-blacklist**. Without food it stops, unless **stop-without-food** is off.

## Settings

| Setting              | Default | Description                                                    |
|----------------------|---------|----------------------------------------------------------------|
| `max-move`           | 9.5     | Longest single move in blocks.                                 |
| `unlimited-vertical` | on      | Clip straight up or down any distance in one move.             |
| `move-delay`         | 1       | Ticks between moves.                                           |
| `landing-radius`     | 100     | How far from the target to look for ground to land on.         |
| `max-air-stops`      | 10      | Most stops in a row in mid-air.                                |
| `max-setbacks`       | 5       | Give up after this many setbacks in a row.                     |
| `stuck-timeout`      | 5       | Give up after this many seconds without a way closer.          |
| `drown-protection`   | on      | Find air when you're about to drown.                           |
| `air-bubbles`        | 1       | Bubbles left when it goes for air.                             |
| `use-doors`          | on      | Place a door to breathe in when there's no open air in reach.  |
| `auto-eat`           | on      | Stop and eat when hungry.                                      |
| `hunger-bars`        | 1       | Hunger bars left when it eats.                                 |
| `eat-until`          | 10      | Hunger bars to eat up to.                                      |
| `stop-without-food`  | on      | Stop if you're hungry and have no food.                        |
| `food-blacklist`     | ...     | Food it won't eat (golden apples, chorus fruit, rotten flesh…). |

## Troubleshooting

Problems are summarised in chat. Setbacks and other server problems are explained in detail in the game log
(`.minecraft/logs/latest.log`), with lines prefixed `[CoordClip]`. For setbacks the log shows the move, where the server
put you, ping, server TPS and the likely cause: a move over the distance limit (including long vertical clips, if the
server limits those too), a block in the way, a blocked destination, server lag, a plugin teleport, or, when every
vanilla check passes, a plugin or anticheat.

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. Put it in your `mods` folder next to Meteor Client.
