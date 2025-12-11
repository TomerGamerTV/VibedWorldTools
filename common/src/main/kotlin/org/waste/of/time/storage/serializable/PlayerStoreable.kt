package org.waste.of.time.storage.serializable

import net.minecraft.entity.player.PlayerEntity
import net.minecraft.inventory.Inventories
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtIo
import net.minecraft.text.MutableText
import net.minecraft.util.Util
import net.minecraft.util.WorldSavePath
import net.minecraft.util.collection.DefaultedList
import net.minecraft.world.level.storage.LevelStorage.Session
import org.waste.of.time.Utils.asString
import org.waste.of.time.WorldTools
import org.waste.of.time.WorldTools.config
import org.waste.of.time.manager.MessageManager.translateHighlight
import org.waste.of.time.manager.StatisticManager
import org.waste.of.time.storage.Cacheable
import org.waste.of.time.storage.CustomRegionBasedStorage
import org.waste.of.time.storage.Storeable
import org.waste.of.time.storage.cache.HotCache
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

data class PlayerStoreable(
    val player: PlayerEntity
) : Cacheable, Storeable() {
    override fun shouldStore() = config.general.capture.players

    override val verboseInfo: MutableText
        get() = translateHighlight(
            "worldtools.capture.saved.player",
            player.name,
            player.pos.asString(),
            player.world.registryKey.value.path
        )

    override val anonymizedInfo: MutableText
        get() = translateHighlight(
            "worldtools.capture.saved.player.anonymized",
            player.name,
            player.world.registryKey.value.path
        )

    override fun cache() {
        HotCache.players.add(this)
    }

    override fun flush() {
        HotCache.players.remove(this)
    }

    override fun store(session: Session, cachedStorages: MutableMap<String, CustomRegionBasedStorage>) {
        savePlayerData(player, session)
        session.createSaveHandler()
        StatisticManager.players++
        StatisticManager.dimensions.add(player.world.registryKey.value.path)
    }

    private fun savePlayerInventory(player: PlayerEntity, playerTag: NbtCompound) {
        val inventory = player.inventory
        val registryManager = player.world.registryManager
        val inventoryList = net.minecraft.nbt.NbtList()
        val registryOps = registryManager.getOps(net.minecraft.nbt.NbtOps.INSTANCE)

        // Save main inventory using ItemStack.OPTIONAL_CODEC for storage format
        for (i in 0 until inventory.size()) {
            val stack = inventory.getStack(i)
            if (!stack.isEmpty) {
                // Try OPTIONAL_CODEC which might produce storage format
                val encoded = ItemStack.OPTIONAL_CODEC.encodeStart(registryOps, stack)
                encoded.result().ifPresent { nbtElement ->
                    if (nbtElement is NbtCompound) {
                        nbtElement.putByte("Slot", i.toByte())
                        inventoryList.add(nbtElement)
                    }
                }
            }
        }
        playerTag.put("Inventory", inventoryList)

        // Save enderchest
        val enderChest = player.enderChestInventory
        WorldTools.LOG.info("Saving enderchest for ${player.name.string}: size=${enderChest.size()}")

        var savedCount = 0
        val enderList = net.minecraft.nbt.NbtList()
        for (i in 0 until enderChest.size()) {
            val stack = enderChest.getStack(i)
            WorldTools.LOG.info("  Slot $i: ${if (stack.isEmpty) "empty" else "${stack.count}x ${stack.item}"}")
            if (!stack.isEmpty) {
                // Try OPTIONAL_CODEC which might produce storage format
                val encoded = ItemStack.OPTIONAL_CODEC.encodeStart(registryOps, stack)
                encoded.result().ifPresent { nbtElement ->
                    if (nbtElement is NbtCompound) {
                        nbtElement.putByte("Slot", i.toByte())
                        enderList.add(nbtElement)
                        savedCount++
                    }
                }
            }
        }
        playerTag.put("EnderItems", enderList)

        WorldTools.LOG.info("Saved $savedCount enderchest items to NBT")
    }

    private fun savePlayerData(player: PlayerEntity, session: Session) {
        try {
            val playerDataDir = session.getDirectory(WorldSavePath.PLAYERDATA).toFile()
            playerDataDir.mkdirs()

            val newPlayerFile = File.createTempFile(player.uuidAsString + "-", ".dat", playerDataDir).toPath()
            val playerTag = NbtCompound()

            WorldTools.LOG.info("Saving player ${player.name.string} (type: ${player.javaClass.simpleName})")

            // Save base entity data manually
            val uuid = player.uuid
            playerTag.putIntArray("UUID", intArrayOf(
                (uuid.mostSignificantBits shr 32).toInt(),
                uuid.mostSignificantBits.toInt(),
                (uuid.leastSignificantBits shr 32).toInt(),
                uuid.leastSignificantBits.toInt()
            ))

            playerTag.put("Pos", net.minecraft.nbt.NbtList().apply {
                add(net.minecraft.nbt.NbtDouble.of(player.x))
                add(net.minecraft.nbt.NbtDouble.of(player.y))
                add(net.minecraft.nbt.NbtDouble.of(player.z))
            })

            playerTag.put("Rotation", net.minecraft.nbt.NbtList().apply {
                add(net.minecraft.nbt.NbtFloat.of(player.yaw))
                add(net.minecraft.nbt.NbtFloat.of(player.pitch))
            })

            playerTag.put("Motion", net.minecraft.nbt.NbtList().apply {
                add(net.minecraft.nbt.NbtDouble.of(player.velocity.x))
                add(net.minecraft.nbt.NbtDouble.of(player.velocity.y))
                add(net.minecraft.nbt.NbtDouble.of(player.velocity.z))
            })

            playerTag.putString("Dimension", player.world.registryKey.value.toString())
            playerTag.putFloat("Health", player.health)
            playerTag.putInt("foodLevel", player.hungerManager.foodLevel)
            playerTag.putFloat("foodSaturationLevel", player.hungerManager.saturationLevel)
            playerTag.putInt("XpLevel", player.experienceLevel)
            playerTag.putFloat("XpP", player.experienceProgress)
            playerTag.putInt("XpTotal", player.totalExperience)
            playerTag.putInt("playerGameType", 0)

            // Critical fields for Minecraft 1.21.10
            playerTag.putInt("DataVersion", 4556) // Minecraft 1.21.10 data version
            playerTag.putShort("Air", player.air.toShort())
            playerTag.putBoolean("OnGround", player.isOnGround)
            playerTag.putInt("SelectedItemSlot", player.inventory.selectedSlot)
            playerTag.putShort("Fire", player.fireTicks.toShort())
            playerTag.putFloat("fall_distance", player.fallDistance.toFloat())

            // Additional required fields from vanilla format
            playerTag.putInt("Score", player.score)
            playerTag.putInt("XpSeed", 0)
            playerTag.putBoolean("seenCredits", false)
            playerTag.putInt("PortalCooldown", player.portalCooldown)
            playerTag.putFloat("AbsorptionAmount", player.absorptionAmount)
            playerTag.putBoolean("Invulnerable", player.isInvulnerable)
            playerTag.putBoolean("FallFlying", false)
            playerTag.putShort("SleepTimer", player.sleepTimer.toShort())
            playerTag.putInt("HurtByTimestamp", 0)
            playerTag.putShort("DeathTime", player.deathTime.toShort())
            playerTag.putShort("HurtTime", player.hurtTime.toShort())
            playerTag.putFloat("foodExhaustionLevel", 0f)
            playerTag.putInt("foodTickTimer", 0)
            playerTag.putBoolean("ignore_fall_damage_from_current_explosion", false)
            playerTag.putBoolean("spawn_extra_particles_on_fall", false)
            playerTag.putInt("current_impulse_context_reset_grace_time", 0)

            // Brain compound (empty but required)
            val brain = NbtCompound()
            val memories = NbtCompound()
            brain.put("memories", memories)
            playerTag.put("Brain", brain)

            // Recipe book (empty but required)
            val recipeBook = NbtCompound()
            recipeBook.put("recipes", net.minecraft.nbt.NbtList())
            recipeBook.put("toBeDisplayed", net.minecraft.nbt.NbtList())
            playerTag.put("recipeBook", recipeBook)

            // Warden spawn tracker
            val wardenTracker = NbtCompound()
            wardenTracker.putInt("warning_level", 0)
            wardenTracker.putInt("ticks_since_last_warning", 0)
            wardenTracker.putInt("cooldown_ticks", 0)
            playerTag.put("warden_spawn_tracker", wardenTracker)

            // Abilities with mayBuild (critical!)
            val abilities = NbtCompound()
            abilities.putBoolean("invulnerable", player.abilities.invulnerable)
            abilities.putBoolean("flying", player.abilities.flying)
            abilities.putBoolean("mayfly", player.abilities.allowFlying)
            abilities.putBoolean("instabuild", player.abilities.creativeMode)
            abilities.putBoolean("mayBuild", true) // Critical: must be true or player can't break blocks
            abilities.putFloat("flySpeed", player.abilities.flySpeed)
            abilities.putFloat("walkSpeed", player.abilities.walkSpeed)
            playerTag.put("abilities", abilities)

            // Save inventory and enderchest
            savePlayerInventory(player, playerTag)

            // Save equipment (armor and offhand) - separate from inventory
            val equipment = NbtCompound()
            val registryManager = player.world.registryManager
            val registryOps = registryManager.getOps(net.minecraft.nbt.NbtOps.INSTANCE)

            // Head (slot 39 in inventory)
            val helmet = player.inventory.getStack(39)
            if (!helmet.isEmpty) {
                val encoded = ItemStack.OPTIONAL_CODEC.encodeStart(registryOps, helmet)
                encoded.result().ifPresent { nbtElement ->
                    if (nbtElement is NbtCompound) {
                        equipment.put("head", nbtElement)
                    }
                }
            }

            // Feet (slot 36 in inventory)
            val boots = player.inventory.getStack(36)
            if (!boots.isEmpty) {
                val encoded = ItemStack.OPTIONAL_CODEC.encodeStart(registryOps, boots)
                encoded.result().ifPresent { nbtElement ->
                    if (nbtElement is NbtCompound) {
                        equipment.put("feet", nbtElement)
                    }
                }
            }

            // Chest (slot 38 in inventory)
            val chestplate = player.inventory.getStack(38)
            if (!chestplate.isEmpty) {
                val encoded = ItemStack.OPTIONAL_CODEC.encodeStart(registryOps, chestplate)
                encoded.result().ifPresent { nbtElement ->
                    if (nbtElement is NbtCompound) {
                        equipment.put("chest", nbtElement)
                    }
                }
            }

            // Legs (slot 37 in inventory)
            val leggings = player.inventory.getStack(37)
            if (!leggings.isEmpty) {
                val encoded = ItemStack.OPTIONAL_CODEC.encodeStart(registryOps, leggings)
                encoded.result().ifPresent { nbtElement ->
                    if (nbtElement is NbtCompound) {
                        equipment.put("legs", nbtElement)
                    }
                }
            }

            // Offhand (slot 40 in inventory)
            val offhand = player.inventory.getStack(40)
            if (!offhand.isEmpty) {
                val encoded = ItemStack.OPTIONAL_CODEC.encodeStart(registryOps, offhand)
                encoded.result().ifPresent { nbtElement ->
                    if (nbtElement is NbtCompound) {
                        equipment.put("offhand", nbtElement)
                    }
                }
            }

            playerTag.put("equipment", equipment)

            WorldTools.LOG.info("Final player NBT has ${playerTag.keys.size} keys")

            // Log first item structure for debugging
            if (playerTag.contains("Inventory")) {
                val invList = playerTag.getList("Inventory")
                if (invList.isPresent && invList.get().size > 0) {
                    WorldTools.LOG.info("Sample inventory item NBT: ${invList.get().get(0)}")
                }
            }
            if (playerTag.contains("EnderItems")) {
                val enderList = playerTag.getList("EnderItems")
                if (enderList.isPresent && enderList.get().size > 0) {
                    WorldTools.LOG.info("Sample enderchest item NBT: ${enderList.get().get(0)}")
                }
            }

            if (config.entity.censor.lastDeathLocation) {
                playerTag.remove("LastDeathLocation")
            }
            NbtIo.writeCompressed(playerTag, newPlayerFile)
            val currentFile = File(playerDataDir, player.uuidAsString + ".dat").toPath()
            val backupFile = File(playerDataDir, player.uuidAsString + ".dat_old").toPath()
            Util.backupAndReplace(currentFile, newPlayerFile, backupFile)
        } catch (e: Exception) {
            WorldTools.LOG.warn("Failed to save player data for {}", player.name.string)
        }
    }
}
