package com.coordclipper.utils;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Collection;

/**
 * Block position argument for Meteor versions without their own: three coordinates, each absolute or relative to the
 * player with ~.
 */
public class CoordsArgument implements ArgumentType<CoordsArgument.Coords> {
    private static final SimpleCommandExceptionType INCOMPLETE = new SimpleCommandExceptionType(Text.translatable("argument.pos3d.incomplete"));
    private static final Collection<String> EXAMPLES = Arrays.asList("0 0 0", "~ ~ ~", "~1 ~-2 ~3");

    public record Coords(boolean[] relative, double[] values) {
        public BlockPos resolve() {
            Vec3d player = Compat.pos(MinecraftClient.getInstance().player);
            double[] base = {player.x, player.y, player.z};
            double[] pos = new double[3];
            for (int i = 0; i < 3; i++) pos[i] = relative[i] ? base[i] + values[i] : values[i];
            return BlockPos.ofFloored(pos[0], pos[1], pos[2]);
        }
    }

    @Override
    public Coords parse(StringReader reader) throws CommandSyntaxException {
        boolean[] relative = new boolean[3];
        double[] values = new double[3];

        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                if (!reader.canRead() || reader.peek() != ' ') throw INCOMPLETE.createWithContext(reader);
                reader.skip();
            }
            if (!reader.canRead()) throw INCOMPLETE.createWithContext(reader);

            if (reader.peek() == '~') {
                reader.skip();
                relative[i] = true;
                values[i] = reader.canRead() && reader.peek() != ' ' ? reader.readDouble() : 0;
            }
            else values[i] = reader.readInt();
        }

        return new Coords(relative, values);
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
