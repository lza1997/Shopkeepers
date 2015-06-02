package com.nisovin.shopkeepers.ui.defaults;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.nisovin.shopkeepers.Log;
import com.nisovin.shopkeepers.Settings;
import com.nisovin.shopkeepers.Shopkeeper;
import com.nisovin.shopkeepers.ShopkeepersAPI;
import com.nisovin.shopkeepers.ShopkeepersPlugin;
import com.nisovin.shopkeepers.Utils;
import com.nisovin.shopkeepers.compat.NMSManager;
import com.nisovin.shopkeepers.events.OpenTradeEvent;
import com.nisovin.shopkeepers.events.ShopkeeperTradeCompletedEvent;
import com.nisovin.shopkeepers.events.ShopkeeperTradeEvent;
import com.nisovin.shopkeepers.shoptypes.PlayerShopkeeper;
import com.nisovin.shopkeepers.ui.UIHandler;
import com.nisovin.shopkeepers.ui.UIType;

public class TradingHandler extends UIHandler {

	public TradingHandler(UIType uiType, Shopkeeper shopkeeper) {
		super(uiType, shopkeeper);
	}

	@Override
	protected boolean canOpen(Player player) {
		assert player != null;
		return Utils.hasPermission(player, ShopkeepersAPI.TRADE_PERMISSION);
	}

	@Override
	protected boolean openWindow(Player player) {
		OpenTradeEvent event = new OpenTradeEvent(player, shopkeeper);
		Bukkit.getPluginManager().callEvent(event);
		if (event.isCancelled()) {
			Log.debug("Trade window not opened: cancelled by another plugin");
			return false;
		}
		// open trading window:
		return NMSManager.getProvider().openTradeWindow(shopkeeper, player);
	}

	@Override
	public boolean isWindow(Inventory inventory) {
		return inventory != null && inventory.getName().equals("mob.villager");
	}

	@Override
	protected void onInventoryClose(InventoryCloseEvent event, Player player) {
		// nothing to do by default
	}

	@Override
	protected void onInventoryClick(InventoryClickEvent event, Player player) {
		assert event != null && player != null;
		if (event.isCancelled()) return;
		int rawSlot = event.getRawSlot();

		// result slot clicked?
		if (rawSlot != 2) {
			return;
		}

		String playerName = player.getName();
		Inventory inventory = event.getInventory();

		ItemStack resultItem = inventory.getItem(2);
		if (resultItem == null || resultItem.getType() == Material.AIR) {
			return; // no trade available
		}

		// prevent unwanted special clicks:
		boolean unwantedSpecialClick = false;
		InventoryAction action = event.getAction();
		if (action == InventoryAction.COLLECT_TO_CURSOR) {
			unwantedSpecialClick = true;
		} else if (event.getRawSlot() == 2) {
			// special clicks on result slot:
			if (!event.isLeftClick() || (event.isShiftClick() && !this.isShiftTradeAllowed(event))) {
				unwantedSpecialClick = true;
			}
		}
		if (unwantedSpecialClick) {
			event.setCancelled(true);
			Utils.updateInventoryLater(player);
			return;
		}

		ItemStack item1 = inventory.getItem(0);
		ItemStack item2 = inventory.getItem(1);
		// minecraft is also allowing the trade, if the second offered item matches the first required one and the first slot is empty:
		// so let's as well assume the item in slot 2 would be in the currently empty slot 1
		if (item1 == null || item1.getType() == Material.AIR) {
			item1 = item2;
			item2 = null;
		}

		// find the recipe minecraft is using for the trade:
		ItemStack[] usedRecipe = NMSManager.getProvider().getUsedTradingRecipe(inventory);

		if (usedRecipe == null) {
			// this shouldn't happen..
			Log.debug("Invalid trade by " + playerName + " with shopkeeper at " + shopkeeper.getPositionString() + ": "
					+ "Minecraft offered a trade, but we didn't find the used recipe!");
			event.setCancelled(true);
			Utils.updateInventoryLater(player);
			return;
		}

		if (Settings.useStrictItemComparison) {
			// verify the recipe items are perfectly matching:
			if (!this.isStrictMatchingRecipe(usedRecipe, item1, item2)) {
				if (Log.isDebug()) { // additional check so we don't do the item comparisons if not really needed
					Log.debug("Invalid trade by " + playerName + " with shopkeeper at " + shopkeeper.getPositionString() + " using strict item comparison:");
					Log.debug("Used recipe: " + Utils.getSimpleRecipeInfo(usedRecipe));
					Log.debug("Recipe item 1: " + (Utils.isSimilar(usedRecipe[0], item1) ? "similar" : "not similar"));
					Log.debug("Recipe item 2: " + (Utils.isSimilar(usedRecipe[1], item2) ? "similar" : "not similar"));
				}
				event.setCancelled(true);
				Utils.updateInventoryLater(player);
				return;
			}
		}

		ItemStack cursor = event.getCursor();
		if (cursor != null && cursor.getType() != Material.AIR) {
			// minecraft doesn't handle the trading in case the cursor cannot hold the resulting items
			// so we have to make sure that our trading logic is as well not run:
			if (!cursor.isSimilar(resultItem) || cursor.getAmount() + resultItem.getAmount() > cursor.getMaxStackSize()) {
				Log.debug("Skip trade by " + playerName + " with shopkeeper at " + shopkeeper.getPositionString() + ": the cursor cannot carry the resulting items");
				event.setCancelled(true); // making sure minecraft really doesn't process the trading
				return;
			}
		}

		// call trade event, giving other plugins a chance to cancel the trade before the shopkeeper processes it:
		ShopkeeperTradeEvent tradeEvent = new ShopkeeperTradeEvent(shopkeeper, player, event);
		Bukkit.getPluginManager().callEvent(tradeEvent);
		if (tradeEvent.isCancelled()) {
			assert event.isCancelled();
			Log.debug("Trade was cancelled by some other plugin.");
			return;
		}

		// let shopkeeper handle the purchase:
		this.onPurchaseClick(event, player, usedRecipe, item1, item2);

		// log purchase:
		if (Settings.enablePurchaseLogging && !event.isCancelled()) {
			// TODO maybe move this somewhere else:
			try {
				String owner = (shopkeeper instanceof PlayerShopkeeper) ? ((PlayerShopkeeper) shopkeeper).getOwnerAsString() : "[Admin]";
				File file = new File(ShopkeepersPlugin.getInstance().getDataFolder(), "purchases-" + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + ".csv");
				boolean isNew = !file.exists();
				BufferedWriter writer = new BufferedWriter(new FileWriter(file, true));
				if (isNew) writer.append("TIME,PLAYER,SHOP TYPE,SHOP POS,OWNER,ITEM TYPE,DATA,QUANTITY,CURRENCY 1,CURRENCY 2\n");
				writer.append("\"" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "\",\"" + playerName + "\",\"" + shopkeeper.getType().getIdentifier()
						+ "\",\"" + shopkeeper.getPositionString() + "\",\"" + owner + "\",\"" + resultItem.getType().name() + "\",\"" + resultItem.getDurability()
						+ "\",\"" + resultItem.getAmount() + "\",\"" + (item1 != null ? item1.getType().name() + ":" + item1.getDurability() : "")
						+ "\",\"" + (item2 != null ? item2.getType().name() + ":" + item2.getDurability() : "") + "\"\n");
				writer.close();
			} catch (IOException e) {
				Log.severe("IO exception while trying to log purchase");
			}
		}

		// call trade-completed event:
		ShopkeeperTradeCompletedEvent tradeCompletedEvent = new ShopkeeperTradeCompletedEvent(shopkeeper, player, event);
		Bukkit.getPluginManager().callEvent(tradeCompletedEvent);
	}

	// whether or not the player can buy via shift click on the result slot:
	protected boolean isShiftTradeAllowed(InventoryClickEvent event) {
		return false; // not allowed by default, just in case
	}

	/**
	 * Called when a player is trying to trade.
	 * Note: The offered items are the items the trading player provided. They can slightly differ from the items from the trading recipe,
	 * depending on item comparison of minecraft and shopkeeper settings.
	 * 
	 * @param event
	 * @param player
	 * @param usedRecipe
	 *            The recipe minecraft is using for the trade.
	 * @param offered1
	 *            The first offered item. If the first slot was empty, this is the item from the second slot.
	 * @param offered2
	 *            The second offered item. If the first slot was empty, this is null.
	 */
	protected void onPurchaseClick(InventoryClickEvent event, Player player, ItemStack[] usedRecipe, ItemStack offered1, ItemStack offered2) {
		// nothing to do by default
	}

	protected int getAmountAfterTaxes(int amount) {
		if (Settings.taxRate == 0) return amount;
		int taxes = 0;
		if (Settings.taxRoundUp) {
			taxes = (int) Math.ceil((double) amount * (Settings.taxRate / 100F));
		} else {
			taxes = (int) Math.floor((double) amount * (Settings.taxRate / 100F));
		}
		return amount - taxes;
	}

	private boolean isStrictMatchingRecipe(ItemStack[] recipe, ItemStack offered1, ItemStack offered2) {
		assert recipe != null;
		if (!Utils.isSimilar(recipe[0], offered1)) return false;
		if (!Utils.isSimilar(recipe[1], offered2)) return false;
		return true;
	}
}