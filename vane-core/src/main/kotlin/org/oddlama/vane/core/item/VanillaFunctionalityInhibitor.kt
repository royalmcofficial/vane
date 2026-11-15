package org.oddlama.vane.core.item

import net.minecraft.core.component.DataComponents
import org.bukkit.Keyed
import org.bukkit.Material
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.inventory.PrepareSmithingEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemMendEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.SmithingRecipe
import org.oddlama.vane.core.Core
import org.oddlama.vane.core.Listener
import org.oddlama.vane.core.item.api.CustomItem
import org.oddlama.vane.core.item.api.InhibitBehavior
import org.oddlama.vane.core.module.Context
import org.oddlama.vane.util.MaterialUtil.isTillable

/**
 * Cancels vanilla behaviors for custom items based on their inhibited behavior flags.
 *
 * @param context listener context.
 */
class VanillaFunctionalityInhibitor(context: Context<Core?>?) : Listener<Core?>(context) {
    /**
     * Returns whether a custom item should inhibit a specific behavior.
     */
    private fun inhibit(customItem: CustomItem?, behavior: InhibitBehavior?): Boolean =
        customItem != null && customItem.enabled() && customItem.inhibitedBehaviors().contains(behavior)

    /**
     * Prevents tempting pathfinding when matching custom items inhibit `TEMPT`.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPathfind(event: EntityTargetEvent) {
        if (event.reason != EntityTargetEvent.TargetReason.TEMPT) return
        val player = event.target as? Player ?: return
        val registry = module!!.itemRegistry() ?: return
        if (inhibit(registry.get(player.inventory.itemInMainHand), InhibitBehavior.TEMPT) ||
            inhibit(registry.get(player.inventory.itemInOffHand), InhibitBehavior.TEMPT)
        ) {
            event.isCancelled = true
        }
    }

    /**
     * Prevents tilling with custom items that inhibit `HOE_TILL`.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerHoeRightClickBlock(event: PlayerInteractEvent) {
        if (!event.hasBlock() || event.action != Action.RIGHT_CLICK_BLOCK) return
        if (!isTillable(event.clickedBlock!!.type)) return
        val item = event.player.equipment.getItem(event.hand!!)
        if (inhibit(module!!.itemRegistry()?.get(item), InhibitBehavior.HOE_TILL)) {
            event.isCancelled = true
        }
    }

    /**
     * Blocks vanilla crafting recipes when inhibited custom items are used as ingredients.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPrepareItemCraft(event: PrepareItemCraftEvent) {
        val recipe = event.recipe
        if (recipe !is Keyed || recipe.key.namespace != "minecraft") return
        if (event.inventory.matrix.any {
                inhibit(
                    module!!.itemRegistry()?.get(it),
                    InhibitBehavior.USE_IN_VANILLA_RECIPE
                )
            }) {
            event.inventory.result = null
        }
    }

    /**
     * Blocks vanilla smithing recipes when inhibited custom items are used as input.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPrepareSmithing(event: PrepareSmithingEvent) {
        val recipe = event.inventory.recipe
        if (recipe !is Keyed || recipe.key.namespace != "minecraft") return
        if (inhibit(
                module!!.itemRegistry()?.get(event.inventory.inputEquipment),
                InhibitBehavior.USE_IN_VANILLA_RECIPE
            )
        ) {
            event.result = null
        }
    }

    /**
     * Reapplies custom-item metadata after smithing recipes that copy NBT.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPrepareSmithingCopyNbt(event: PrepareSmithingEvent) {
        if (event.result == null) return
        val recipe = event.inventory.recipe
        if (recipe !is SmithingRecipe || !recipe.willCopyNbt()) return

        val registry = module!!.itemRegistry() ?: return
        val customItemResult = registry.get(recipe.result) ?: return
        val inputStack = event.inventory.inputEquipment ?: return
        val customItemInput = registry.get(inputStack)
        val nmsInput = CraftItemStack.asNMSCopy(inputStack)
        val nmsResult = CraftItemStack.asNMSCopy(recipe.result)

        if (customItemInput == null || customItemInput.key() == customItemResult.key()) {
            nmsResult.applyComponents(nmsInput.components)
        } else {
            // A different custom-item tier owns its material, attributes, model and item name.
            // Copy mutable properties from the base without bringing along those tier defaults.
            val inputComponents = nmsInput.componentsPatch.forget { component ->
                component == DataComponents.ATTRIBUTE_MODIFIERS ||
                    component == DataComponents.MAX_DAMAGE ||
                    component == DataComponents.ITEM_NAME ||
                    component == DataComponents.ITEM_MODEL ||
                    component == DataComponents.CUSTOM_MODEL_DATA
            }
            nmsResult.applyComponents(inputComponents)

            val inputMaxDamage = nmsInput.maxDamage
            val resultMaxDamage = nmsResult.maxDamage
            val inputDamage = nmsInput.damageValue
            val targetDurability = customItemResult.durability()
            if (targetDurability > 0 && inputMaxDamage > 0 && resultMaxDamage > 0) {
                // Let the durability updater scale source damage into the configured target max.
                nmsResult.set(DataComponents.MAX_DAMAGE, inputMaxDamage)
            } else if (
                targetDurability == 0 && inputMaxDamage > 0 && resultMaxDamage > 0 &&
                inputDamage >= resultMaxDamage
            ) {
                val scaledDamage = DurabilityManager.scaleDamage(
                    inputDamage,
                    inputMaxDamage,
                    resultMaxDamage
                ).coerceIn(0, resultMaxDamage - 1)
                nmsResult.damageValue = scaledDamage
            }
        }

        val convertedStack = customItemResult.convertExistingStack(CraftItemStack.asBukkitMirror(nmsResult))
        event.result = convertedStack
    }

    /**
     * Restricts invalid anvil combinations and disallowed anvil enchant modifications.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val a = event.inventory.firstItem
        val b = event.inventory.secondItem
        if (a != null && b != null && a.type == b.type) {
            val customItemA = module!!.itemRegistry()?.get(a)
            val customItemB = module!!.itemRegistry()?.get(b)
            if (customItemA != null && customItemA !== customItemB) {
                event.result = null
                return
            }
        }

        val r = event.inventory.result ?: return
        val customItemR = module!!.itemRegistry()?.get(r)
        r.editMeta { meta ->
            if (a != null && inhibit(customItemR, InhibitBehavior.NEW_ENCHANTS)) {
                r.enchantments.keys
                    .filter { !a.enchantments.containsKey(it) }
                    .forEach { meta.removeEnchant(it) }
            }
            if (inhibit(customItemR, InhibitBehavior.MEND)) meta.removeEnchant(Enchantment.MENDING)
        }
        event.result = r
    }

    /**
     * Cancels mending for items that inhibit `MEND`.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onItemMend(event: PlayerItemMendEvent) {
        if (inhibit(module!!.itemRegistry()?.get(event.item), InhibitBehavior.MEND)) {
            event.isCancelled = true
        }
    }

    /**
     * Prevents fire and lava destruction for items that inhibit `ITEM_BURN`.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onItemBurn(event: EntityDamageEvent) {
        if (event.entity.type != EntityType.ITEM) return
        when (event.cause) {
            DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA -> {}
            else -> return
        }
        val item = event.entity as? Item ?: return
        if (inhibit(module!!.itemRegistry()?.get(item.itemStack), InhibitBehavior.ITEM_BURN)) {
            event.isCancelled = true
        }
    }

    /**
     * Denies off-hand usage when the main-hand custom item inhibits `USE_OFFHAND`.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerRightClick(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.OFF_HAND) return
        val mainItem = event.player.equipment.getItem(EquipmentSlot.HAND)
        if (inhibit(module!!.itemRegistry()?.get(mainItem), InhibitBehavior.USE_OFFHAND)) {
            event.setUseItemInHand(Event.Result.DENY)
        }
    }

    /**
     * Cancels dispenser usage for items that inhibit `DISPENSE`.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDispense(event: BlockDispenseEvent) {
        if (event.block.type != Material.DISPENSER) return
        if (inhibit(module!!.itemRegistry()?.get(event.item), InhibitBehavior.DISPENSE)) {
            event.isCancelled = true
        }
    }
}
