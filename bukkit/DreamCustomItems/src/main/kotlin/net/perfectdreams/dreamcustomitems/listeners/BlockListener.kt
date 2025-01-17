package net.perfectdreams.dreamcustomitems.listeners

import net.perfectdreams.dreamcore.utils.extensions.meta
import net.perfectdreams.dreamcore.utils.extensions.rightClick
import net.perfectdreams.dreamcustomitems.DreamCustomItems
import org.bukkit.Material
import org.bukkit.block.data.Levelled
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

class BlockListener(val m: DreamCustomItems) : Listener {
    @EventHandler
    fun onInteract(e: PlayerInteractEvent) {
        if (!e.rightClick)
            return

        val clickedBlock = e.clickedBlock ?: return
        val heldItem = e.item ?: return

        if (clickedBlock.type == Material.CAULDRON) {
            val state = clickedBlock.blockData as Levelled

            if (state.level == 0)
                return

            if (heldItem.type == Material.BAKED_POTATO) {
                heldItem.amount -= 1
                e.player.inventory.addItem(
                    ItemStack(Material.BAKED_POTATO)
                        .meta<ItemMeta> {
                            this.setCustomModelData(1)
                        }
                )
                state.level -= 1
                clickedBlock.blockData = state
            }
        }
    }
}