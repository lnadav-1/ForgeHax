package dev.fiki.forgehax.main.mods.player;

import dev.fiki.forgehax.main.Common;
import dev.fiki.forgehax.main.util.cmd.settings.BooleanSetting;
import dev.fiki.forgehax.main.util.cmd.settings.IntegerSetting;
import dev.fiki.forgehax.main.util.entity.LocalPlayerInventory;
import dev.fiki.forgehax.main.util.mod.Category;
import dev.fiki.forgehax.main.util.mod.ToggleMod;
import dev.fiki.forgehax.main.util.modloader.RegisterMod;
import dev.fiki.forgehax.main.util.task.TaskChain;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CClickWindowPacket;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Comparator;
import java.util.List;

@RegisterMod(
    name = "AutoHotbarReplenish",
    description = "Will replenish tools or block stacks automatically",
    category = Category.PLAYER
)
public class AutoHotbarReplenish extends ToggleMod {

  private final IntegerSetting durability_threshold = newIntegerSetting()
      .name("durability-threshold")
      .description("Will auto replace tools when they hit this damage value")
      .defaultTo(5)
      .min(0)
      .max((int) Short.MAX_VALUE)
      .build();

  private final IntegerSetting stack_threshold = newIntegerSetting()
      .name("stack-threshold")
      .description("Will replace stacks when there only remains this many")
      .defaultTo(10)
      .min(1)
      .max((int) Short.MAX_VALUE)
      .build();

  private final IntegerSetting tick_delay = newIntegerSetting()
      .name("tick-delay")
      .description("Number of ticks between each window click packet. 0 will have no limit and a negative value will send n packets per tick")
      .defaultTo(1)
      .build();

  private final BooleanSetting no_gui = newBooleanSetting()
      .name("no-gui")
      .description("Don't run when a gui is open")
      .defaultTo(true)
      .build();

  private TaskChain<Runnable> tasks = TaskChain.empty();
  private long tickCount = 0;

  private boolean processing(int index) {
    if (tick_delay.getValue() == 0) {
      return true; // process all
    } else if (tick_delay.getValue() < 0) {
      return index < Math.abs(tick_delay.getValue()); // process n tasks per tick
    } else {
      return index == 0 && tickCount % tick_delay.getValue() == 0;
    }
  }

  private boolean isMonitoring(LocalPlayerInventory.InvItem item) {
    return item.isItemDamageable() || item.isStackable();
  }

  private boolean isAboveThreshold(LocalPlayerInventory.InvItem item) {
    return item.isItemDamageable()
        ? item.getDurability() > durability_threshold.getValue()
        : item.getStackCount() > stack_threshold.getValue();
  }

  private int getDamageOrCount(LocalPlayerInventory.InvItem item) {
    return item.isNull()
        ? 0
        : item.isItemDamageable() ? item.getDurability() : item.getStackCount();
  }

  private void tryPlacingHeldItem() {
    LocalPlayerInventory.InvItem holding = LocalPlayerInventory.getMouseHeld();

    if (holding.isEmpty()) // all is good
    {
      return;
    }

    LocalPlayerInventory.InvItem item;
    if (holding.isDamageable()) {
      item =
          LocalPlayerInventory.getSlotStorageInventory()
              .stream()
              .filter(LocalPlayerInventory.InvItem::isNull)
              .findAny()
              .orElse(LocalPlayerInventory.InvItem.EMPTY);
    } else {
      item =
          LocalPlayerInventory.getSlotStorageInventory()
              .stream()
              .filter(inv -> inv.isNull() || holding.isItemsEqual(inv))
              .filter(inv -> inv.isNull() || !inv.isStackMaxed())
              .max(Comparator.comparing(LocalPlayerInventory.InvItem::getStackCount))
              .orElse(LocalPlayerInventory.InvItem.EMPTY);
    }

    if (item == LocalPlayerInventory.InvItem.EMPTY) {
      click(holding, 0, ClickType.PICKUP);
    } else {
      click(item, 0, ClickType.PICKUP);
      if (LocalPlayerInventory.getMouseHeld().nonEmpty()) {
        throw new RuntimeException();
      }
    }
  }

  @Override
  protected void onDisabled() {
    Common.addScheduledTask(() -> {
      tasks = TaskChain.empty();
      tickCount = 0;
    });
  }

  @SubscribeEvent
  public void onTick(TickEvent.ClientTickEvent event) {
    if (!TickEvent.Phase.START.equals(event.phase) || Common.getLocalPlayer() == null) {
      return;
    }

    // only process when a gui isn't opened by the player
    if (Common.getDisplayScreen() != null && no_gui.getValue()) {
      return;
    }

    if (tasks.isEmpty()) {
      final List<LocalPlayerInventory.InvItem> slots = LocalPlayerInventory.getSlotStorageInventory();

      tasks = LocalPlayerInventory.getHotbarInventory()
          .stream()
          .filter(LocalPlayerInventory.InvItem::nonNull)
          .filter(this::isMonitoring)
          .filter(item -> !isAboveThreshold(item))
          .filter(item -> slots.stream()
              .filter(this::isMonitoring)
              .filter(inv -> !inv.isItemDamageable() || isAboveThreshold(inv))
              .anyMatch(item::isItemsEqual))
          .max(Comparator.comparingInt(LocalPlayerInventory::getHotbarDistance))
          .map(hotbarItem ->
              TaskChain.<Runnable>builder()
                  .then(() -> {
                    // pick up item
                    verifyHotbar(hotbarItem);
                    click(
                        slots.stream()
                            .filter(LocalPlayerInventory.InvItem::nonNull)
                            .filter(this::isMonitoring)
                            .filter(hotbarItem::isItemsEqual)
                            .filter(inv -> !inv.isDamageable() || isAboveThreshold(inv))
                            .max(Comparator.comparingInt(this::getDamageOrCount))
                            .orElseThrow(RuntimeException::new),
                        0,
                        ClickType.PICKUP);
                  })
                  .then(() -> {
                    // place item into hotbar

                    verifyHotbar(hotbarItem);
                    click(hotbarItem, 0, ClickType.PICKUP);
                  })
                  .then(this::tryPlacingHeldItem)
                  .build())
          .orElse(TaskChain.empty());
    }

    // process the next click task
    int n = 0;
    while (processing(n++) && tasks.hasNext()) {
      try {
        tasks.next().run();
      } catch (Throwable t) {
        tasks = TaskChain.singleton(this::tryPlacingHeldItem);
      }
    }

    ++tickCount;
  }

  //
  //
  //

  private static void verifyHotbar(LocalPlayerInventory.InvItem hotbarItem) {
    LocalPlayerInventory.InvItem current = LocalPlayerInventory.getHotbarInventory().get(hotbarItem.getIndex());
    if (!hotbarItem.isItemsEqual(current)) {
      throw new IllegalArgumentException();
    }
  }

  private static void verifyHeldItem(LocalPlayerInventory.InvItem staticItem) {
  }

  private static void clickWindow(
      int slotIdIn, int usedButtonIn, ClickType modeIn, ItemStack clickedItemIn) {
    Common.sendNetworkPacket(new CClickWindowPacket(
        0,
        slotIdIn,
        usedButtonIn,
        modeIn,
        clickedItemIn,
        LocalPlayerInventory.getOpenContainer().getNextTransactionID(LocalPlayerInventory.getInventory())));
  }

  private static ItemStack click(LocalPlayerInventory.InvItem item, int usedButtonIn, ClickType modeIn) {
    if (item.getIndex() == -1) {
      throw new IllegalArgumentException();
    }
    ItemStack ret;
    clickWindow(item.getSlotNumber(), usedButtonIn, modeIn,
        ret = LocalPlayerInventory.getOpenContainer().slotClick(item.getSlotNumber(), usedButtonIn,
            modeIn, Common.getLocalPlayer()));
    return ret;
  }
}
