package com.coordclipper.modules;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.List;

/**
 * Settings for .coordclip. The command reads them every time it runs; the module itself doesn't need to be enabled.
 */
public class CoordClipSettings extends Module {
    private final SettingGroup sgMovement = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgAir = settings.createGroup("Air");
    private final SettingGroup sgHunger = settings.createGroup("Hunger");

    // Movement

    public final Setting<Double> maxHop = sgMovement.add(new DoubleSetting.Builder()
        .name("max-move")
        .description("Longest single move in blocks. Vanilla servers put you back for moves over 10 blocks, so lower it to 9.5 if you keep getting put back.")
        .defaultValue(100)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    public final Setting<Boolean> unlimitedVertical = sgMovement.add(new BoolSetting.Builder()
        .name("unlimited-vertical")
        .description("Clip straight up or down any distance in one move, then across. Turn off if the server puts you back after long vertical clips.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> moveDelay = sgMovement.add(new IntSetting.Builder()
        .name("move-delay")
        .description("Ticks between moves. 1 is fastest; raise it if the server puts you back for moving too fast.")
        .defaultValue(1)
        .range(1, 10)
        .sliderRange(1, 10)
        .build()
    );

    public final Setting<Integer> landingRadius = sgMovement.add(new IntSetting.Builder()
        .name("landing-radius")
        .description("How far from the target to look for solid ground to land on.")
        .defaultValue(100)
        .range(1, 200)
        .sliderRange(1, 200)
        .build()
    );

    public final Setting<Integer> maxAirStops = sgMovement.add(new IntSetting.Builder()
        .name("max-air-stops")
        .description("Most stops in a row in mid-air, used to clip over trees, walls and canopies. The server kicks you for floating for 4 seconds.")
        .defaultValue(10)
        .range(0, 40)
        .sliderRange(0, 40)
        .build()
    );

    // Safety

    public final Setting<Boolean> noFallDamage = sgSafety.add(new BoolSetting.Builder()
        .name("no-fall-damage")
        .description("Keeps clips down and being held in mid-air from doing fall damage.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> maxSetbacks = sgSafety.add(new IntSetting.Builder()
        .name("max-setbacks")
        .description("Give up after the server puts you back this many times in a row.")
        .defaultValue(5)
        .range(1, 50)
        .sliderRange(1, 20)
        .build()
    );

    public final Setting<Integer> stuckTimeout = sgSafety.add(new IntSetting.Builder()
        .name("stuck-timeout")
        .description("Give up after this many seconds without finding a way closer.")
        .defaultValue(5)
        .range(1, 120)
        .sliderRange(1, 30)
        .build()
    );

    // Air

    public final Setting<Boolean> drownProtection = sgAir.add(new BoolSetting.Builder()
        .name("drown-protection")
        .description("When you're almost out of air, clip to open air and wait there until your air is full again.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> airBubbles = sgAir.add(new IntSetting.Builder()
        .name("air-bubbles")
        .description("Find air when you're down to this many bubbles.")
        .defaultValue(1)
        .range(1, 9)
        .sliderRange(1, 9)
        .visible(drownProtection::get)
        .build()
    );

    public final Setting<Boolean> useDoors = sgAir.add(new BoolSetting.Builder()
        .name("use-doors")
        .description("If there's no open air in reach, place a door from your inventory where you stand and breathe inside it.")
        .defaultValue(false)
        .visible(drownProtection::get)
        .build()
    );

    // Hunger

    public final Setting<Boolean> autoEat = sgHunger.add(new BoolSetting.Builder()
        .name("auto-eat")
        .description("Stop and eat when you get hungry, then keep going.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> hungerBars = sgHunger.add(new IntSetting.Builder()
        .name("hunger-bars")
        .description("Eat when you're down to this many hunger bars.")
        .defaultValue(1)
        .range(1, 9)
        .sliderRange(1, 9)
        .visible(autoEat::get)
        .build()
    );

    public final Setting<Integer> eatUntil = sgHunger.add(new IntSetting.Builder()
        .name("eat-until")
        .description("Keep eating until you have this many hunger bars, or run out of food.")
        .defaultValue(10)
        .range(2, 10)
        .sliderRange(2, 10)
        .visible(autoEat::get)
        .build()
    );

    public final Setting<Boolean> stopWithoutFood = sgHunger.add(new BoolSetting.Builder()
        .name("stop-without-food")
        .description("Stop clipping if you're hungry and have nothing to eat.")
        .defaultValue(true)
        .visible(autoEat::get)
        .build()
    );

    public final Setting<List<Item>> foodBlacklist = sgHunger.add(new ItemListSetting.Builder()
        .name("food-blacklist")
        .description("Food it won't eat.")
        .defaultValue(
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.GOLDEN_APPLE,
            Items.CHORUS_FRUIT,
            Items.POISONOUS_POTATO,
            Items.PUFFERFISH,
            Items.CHICKEN,
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.SUSPICIOUS_STEW
        )
        .filter(item -> item.getComponents().get(DataComponentTypes.FOOD) != null)
        .visible(autoEat::get)
        .build()
    );

    public CoordClipSettings() {
        super(Categories.Movement, "coord-clipper", "Settings for .coordclip. Doesn't need to be enabled. Change them here or with .settings coord-clipper <setting> <value>.");
    }

    @Override
    public void onActivate() {
        info("This module only holds the settings for .coordclip, it doesn't need to be enabled. Change them with (highlight).settings coord-clipper <setting> <value>(default).");
        toggle();
    }

    public static CoordClipSettings get() {
        return Modules.get().get(CoordClipSettings.class);
    }
}
