Download the jar for your Minecraft version and put it in your `mods` folder next to Meteor Client for the same version.

**Settings:** change them with `.settings coord-clipper <setting> <value>`, e.g. `.settings coord-clipper max-move 9.5`.

**Versions:** 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.10, 1.21.11, 26.1.2, 26.2, 26.3 (26.x needs Java 25)

### Changes since 0.2.0
- Builds for Minecraft 1.21.4 – 1.21.11 and 26.1.2 – 26.3
- `max-move` defaults to 100 blocks (set it to 9.5 on vanilla servers, which only allow 10 per move)
- No fall damage from clipping down or being held in mid-air (`no-fall-damage`, on by default)
- Fixed the game freezing near the target with long moves
- Fixed getting stuck hovering in mid-air
- Stops after `stuck-timeout` seconds without getting closer, and drops to the ground instead of leaving you in mid-air
- Moves that turn out to be impossible are explained in the log
- `use-doors` is now off by default
