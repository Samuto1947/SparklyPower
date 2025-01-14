package net.perfectdreams.dreamcustomitems.listeners

import net.perfectdreams.dreamcustomitems.DreamCustomItems
import org.bukkit.Keyed
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.CraftItemEvent

class BlockCraftListener(val m: DreamCustomItems) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(e: CraftItemEvent) {
        val recipe = e.recipe
        if (recipe is Keyed) {
			val recipeKey = when(recipe.key.key) {
				"microwave" -> true
				"superfurnace" -> true
				else -> false				
			}

            if (recipeKey && e.inventory.any { it.type == Material.PRISMARINE_SHARD && (!it.itemMeta.hasCustomModelData() || it.itemMeta.customModelData != 1) })
                e.isCancelled = true
        }
    }
}
