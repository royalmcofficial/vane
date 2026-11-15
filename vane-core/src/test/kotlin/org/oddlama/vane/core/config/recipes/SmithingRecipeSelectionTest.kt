package org.oddlama.vane.core.config.recipes

import com.mojang.serialization.Lifecycle
import net.minecraft.SharedConstants
import net.minecraft.core.MappedRegistry
import net.minecraft.core.RegistrationInfo
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack as NmsItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeManager
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.SmithingRecipeInput
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.craftbukkit.CraftRegistry
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.craftbukkit.inventory.CraftSmithingTransformRecipe
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.SmithingTransformRecipe
import org.bukkit.persistence.PersistentDataType
import org.oddlama.vane.core.item.CustomItemHelper
import org.oddlama.vane.core.item.CustomItemRegistry as RuntimeCustomItemRegistry
import org.oddlama.vane.core.item.api.CustomItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmithingRecipeSelectionTest {
    @Test
    fun `registered sickle base is selected with or without copy NBT while vanilla hoe remains usable`() {
        initializePaper()

        val customItem = DiamondSickleFixture()
        val registry = RuntimeCustomItemRegistry().apply { register(customItem) }
        val outputKey = NamespacedKey("vane_trifles", "netherite_sickle")
        val customKey = NamespacedKey("vane_trifles", "netherite_sickle.generic")
        val vanillaKey = NamespacedKey.minecraft("netherite_hoe_smithing")
        val damagedNamedInput = CraftItemStack.asNMSCopy(customItem.newStack()).apply {
            damageValue = 39
            set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("My sickle"))
        }

        for (copyNbt in listOf(false, true)) {
            val definition = RecipeDefinition.fromDict(
                "generic",
                mutableMapOf(
                    "Base" to "vane_trifles:diamond_sickle",
                    "Addition" to "minecraft:netherite_ingot",
                    "Result" to "minecraft:netherite_hoe",
                    "CopyNbt" to copyNbt,
                    "Type" to "smithing",
                ),
            ) as SmithingRecipeDefinition
            val configuredRecipe = definition.toRecipe(outputKey, registry) as SmithingTransformRecipe
            val customRecipe = CraftSmithingTransformRecipe.fromBukkitRecipe(configuredRecipe).toMinecraftRecipe()
            val vanillaRecipe = CraftSmithingTransformRecipe(
                vanillaKey,
                org.bukkit.inventory.ItemStack(Material.NETHERITE_HOE),
                RecipeChoice.MaterialChoice(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                RecipeChoice.MaterialChoice(Material.DIAMOND_HOE),
                RecipeChoice.MaterialChoice(Material.NETHERITE_INGOT),
                true,
            ).toMinecraftRecipe()

            val recipeRegistry = MappedRegistry<Recipe<*>>(Registries.RECIPE, Lifecycle.stable())
            recipeRegistry.register(vanillaRecipe.id(), vanillaRecipe.value(), RegistrationInfo.BUILT_IN)
            recipeRegistry.freeze()
            val manager = RecipeManager(RegistryAccess.ImmutableRegistryAccess(listOf(recipeRegistry)))
            manager.recipes.addRecipe(customRecipe)

            val template = NmsItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)
            val ingot = NmsItemStack(Items.NETHERITE_INGOT)
            val customInput = SmithingRecipeInput(template, damagedNamedInput, ingot)
            val selectedCustom = manager.getRecipeFor(RecipeType.SMITHING, customInput, ignoredLevel()).orElseThrow()
            assertEquals(customKey.toString(), selectedCustom.id().identifier().toString())
            val customResult = selectedCustom.value().assemble(customInput)
            assertEquals(Items.NETHERITE_HOE, customResult.item)
            if (copyNbt) {
                assertEquals(39, customResult.damageValue)
                assertEquals("My sickle", customResult.get(DataComponents.CUSTOM_NAME)?.string)
            } else {
                assertEquals(0, customResult.damageValue)
                assertNull(customResult.get(DataComponents.CUSTOM_NAME))
            }

            val vanillaInput = SmithingRecipeInput(template, NmsItemStack(Items.DIAMOND_HOE), ingot)
            val selectedVanilla = manager.getRecipeFor(RecipeType.SMITHING, vanillaInput, ignoredLevel()).orElseThrow()
            assertEquals(vanillaKey.toString(), selectedVanilla.id().identifier().toString())
        }
    }

    private fun initializePaper() {
        val originalOut = System.out
        val originalErr = System.err
        try {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }

        if (CraftRegistry.getMinecraftRegistry() == null) {
            CraftRegistry.setMinecraftRegistry(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY))
        }
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
            .build(VanillaRegistries.createWorldLookup())
            .forEach { it.apply() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ignoredLevel(): T = null as T

    private class DiamondSickleFixture : CustomItem {
        override fun key() = NamespacedKey("vane_trifles", "diamond_sickle")
        override fun enabled() = true
        override fun version() = 1
        override fun baseMaterial() = Material.DIAMOND_HOE
        override fun customModelData() = 1
        override fun displayName() = null
        override fun durability() = 0

        override fun newStack(): org.bukkit.inventory.ItemStack =
            CraftItemStack.asBukkitMirror(NmsItemStack(Items.DIAMOND_HOE)).apply {
                editPersistentDataContainer {
                    it.set(CustomItemHelper.CUSTOM_ITEM_IDENTIFIER, PersistentDataType.STRING, key().toString())
                    it.set(CustomItemHelper.CUSTOM_ITEM_VERSION, PersistentDataType.INTEGER, version())
                }
            }
    }
}
