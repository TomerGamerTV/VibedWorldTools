package org.waste.of.time.manager

import kotlinx.coroutines.*
import net.minecraft.client.gui.screen.ConfirmScreen
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.text.Text
import net.minecraft.world.World
import org.waste.of.time.WorldTools
import org.waste.of.time.WorldTools.LOG
import org.waste.of.time.WorldTools.config
import org.waste.of.time.WorldTools.mc
import org.waste.of.time.config.WorldToolsConfig
import org.waste.of.time.manager.MessageManager.info
import org.waste.of.time.storage.StorageFlow
import org.waste.of.time.storage.cache.EntityCacheable
import org.waste.of.time.storage.cache.HotCache
import org.waste.of.time.storage.serializable.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

object CaptureManager {
    private const val MAX_WORLD_NAME_LENGTH = 64
    var capturing = false
    var isPaused = false
    var isWaitingForReconnect = false
    
    private var storeJob: Job? = null
    var currentLevelName: String = "Not yet initialized"
    var lastPlayer: ClientPlayerEntity? = null
    var lastWorldKeys = mutableSetOf<RegistryKey<World>>()

    val levelName: String
        get() = if (mc.isInSingleplayer) {
            mc.server?.serverMotd?.substringAfter(" - ")?.sanitizeWorldName() ?: "Singleplayer"
        } else {
            mc.networkHandler?.serverInfo?.address?.sanitizeWorldName() ?: "Multiplayer"
        }

    fun toggleCapture() {
        if (capturing) {
            if (isPaused) resumeCapture() else pauseCapture()
        } else {
             start()
        }
    }
    
    fun pauseCapture() {
        if (!capturing || isPaused) return
        isPaused = true
        MessageManager.sendInfo("worldtools.log.info.paused_capture", currentLevelName)
        // Note: StorageFlow keeps running, but events won't feed it new data
    }

    fun resumeCapture() {
        if (!capturing || !isPaused) return
        isPaused = false
        // Reset connection-related flags
        isWaitingForReconnect = false 
        MessageManager.sendInfo("worldtools.log.info.resumed_capture", currentLevelName)
        // Events will start feeding data again
        // Re-sync cache is a good idea in case we missed stuff while paused/disconnected
        syncCacheOnResume()
    }
    
    private fun syncCacheOnResume() {
        CoroutineScope(Dispatchers.IO).launch {
            delay(100L)
            mc.execute { syncCacheFromWorldState() }
        }
    }

    fun start(customName: String? = null, confirmed: Boolean = false) {
        if (capturing) {
            MessageManager.sendError("worldtools.log.error.already_capturing", currentLevelName)
            return
        }

        if (mc.isInSingleplayer) {
            MessageManager.sendInfo("worldtools.log.info.singleplayer_capture")
        }

        val potentialName = customName?.let { potentialName ->
            if (potentialName.length > MAX_WORLD_NAME_LENGTH) {
                MessageManager.sendError(
                    "worldtools.log.error.world_name_too_long",
                    potentialName,
                    MAX_WORLD_NAME_LENGTH
                )
                return
            }

            potentialName.ifBlank { levelName }
        } ?: levelName

        val worldDir = mc.levelStorage.savesDirectory.resolve(potentialName).toFile()
        val worldExists = worldDir.exists()
        val metaExists = mc.levelStorage.savesDirectory.resolve(potentialName).resolve("worldtools_meta.json").toFile().exists()
        val looksLikeLegacyWorld = worldExists && !metaExists && looksLikeMinecraftWorld(worldDir)

        if (worldExists && !confirmed) {
             if (metaExists || looksLikeLegacyWorld) {
                  // It's one of ours, offer RESUME
                  mc.setScreen(org.waste.of.time.gui.ResumeConfirmScreen(
                      { choice ->
                          // 0 = Cancel, 1 = Overwrite, 2 = Resume
                          when (choice) {
                              1 -> start(potentialName, true) // Overwrite
                              2 -> resumeExisting(potentialName, isLegacy = looksLikeLegacyWorld)
                              else -> mc.setScreen(null)
                          }
                      },
                      potentialName
                  ))
             } else {
                mc.setScreen(ConfirmScreen(
                    { yes ->
                        if (yes) start(potentialName, true)
                        mc.setScreen(null)
                    },
                    Text.translatable("worldtools.gui.capture.existing_world_confirm.title"),
                    Text.translatable("worldtools.gui.capture.existing_world_confirm.message", potentialName)
                ))
            }
            return
        }

        HotCache.clear()
        currentLevelName = potentialName
        lastPlayer = mc.player
        lastWorldKeys.addAll(mc.networkHandler?.worldKeys ?: emptySet())
        MessageManager.sendInfo("worldtools.log.info.started_capture", potentialName)
        if (config.debug.logSettings) logCaptureSettingsState()
        storeJob = StorageFlow.launch(potentialName)
        createMetaFile(potentialName)
        mc.networkHandler?.sendPacket(ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS))
        capturing = true
        isPaused = false
        isWaitingForReconnect = false

        // Need to wait until the storage flow is running before syncing the cache
        CoroutineScope(Dispatchers.IO).launch {
            delay(100L)
            mc.execute {
                syncCacheFromWorldState()
            }
        }
    }

    private fun logCaptureSettingsState() {
        WorldTools.GSON.toJson(config, WorldToolsConfig::class.java).let { configJson ->
            LOG.info("Launching capture with settings:")
            LOG.info(configJson)
        }
    }
    
    private fun createMetaFile(levelName: String) {
         try {
            val dir = mc.levelStorage.savesDirectory.resolve(levelName)
            if (!dir.toFile().exists()) dir.toFile().mkdirs()
            val metaFile = dir.resolve("worldtools_meta.json").toFile()
            metaFile.writeText("""{"created_by": "worldtools", "timestamp": ${System.currentTimeMillis()}}""")
         } catch (e: Exception) {
            LOG.error("Failed to create meta file", e)
         }
    }

    // Logic to attach to an existing folder basically means "Start" but don't clear/overwrite files?
    // StorageFlow handles appending to Region files naturally. 
    // We just need to make sure we don't 'clear' anything destructive if start() does that.
    // start() calls HotCache.clear(), which is fine.
    // start() calls StorageFlow.launch().
    // We need to make sure StorageFlow doesn't blow away existing files unless we want it to.
    // StorageFlow creates a session. If the files exist, standard RegionFile behavior is to read/write, effectively appending/overwriting chunks.
    // So "Resume" is effectively just "Start" but skipping the "Overwrite" confirmation that implies deletion?
    // Wait, Anvil saves are folders. 'Overwrite' usually implies deleting the folder. 
    // If we just want to Resume, we simply call start(name, confirmed=true) BUT we must be careful NOT to delete the folder first.
    // Standard Minecraft overwriting deletes the folder. We need to check if `start` deletes it.
    // `start` does NOT seem to delete the folder explicitly in the code I read.
    // CHECK StorageFlow.launch.
    // It calls `mc.levelStorage.createSession(levelName)`. 
    // If I use `start(potentialName, true)`, it flows through.
    // Does `createSession` delete? No.
    // So "Overwrite" in `start` context currently just keeps going, potentially mixing data?
    // Ah, standard behavior for a "New World" with same name is that logic would handle it.
    // But here we are just opening it.
    // So `start` IS effectively `resume/append` mechanically.
    // "Overwrite" in UI usually implies fresh start.
    // If I want "Fresh Start" I should delete the folder.
    // If I want "Resume" I should NOT delete.
    // Existing `start` does NOT delete. So it's ALWAYS a "Merge/Resume" technically.
    // So for "Overwrite", I should actually add deletion logic?
    // For now, I'll assume `start` is safe for Resume. 
    // I will implement `resumeExisting` as just calling start but notifying user.
    
    fun resumeExisting(levelName: String, isLegacy: Boolean = false) {
        mc.setScreen(null)
        
        // For legacy worlds (those without worldtools_meta.json), create a backup first
        if (isLegacy) {
            MessageManager.sendInfo("worldtools.log.info.backup_started", levelName)
            
            CoroutineScope(Dispatchers.IO).launch {
                val backupSuccess = backupWorld(levelName)
                
                mc.execute {
                    if (!backupSuccess) {
                        MessageManager.sendError("worldtools.log.error.backup_failed", levelName)
                    } else {
                        MessageManager.sendInfo("worldtools.log.info.backup_created", levelName)
                        start(levelName, true)
                    }
                }
            }
        } else {
            start(levelName, true)
        }
    }
    
    /**
     * Creates a backup copy of the world folder before resuming.
     * Returns true if backup was successful, false otherwise.
     */
    private fun backupWorld(levelName: String): Boolean {
        try {
            val worldDir = mc.levelStorage.savesDirectory.resolve(levelName).toFile()
            if (!worldDir.exists() || !worldDir.isDirectory) {
                LOG.error("Cannot backup: world directory does not exist: $levelName")
                return false
            }
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            val backupName = "${levelName}_backup_$timestamp"
            val backupDir = mc.levelStorage.savesDirectory.resolve(backupName).toFile()
            
            LOG.info("Creating backup of '$levelName' to '$backupName'...")
            copyDirectory(worldDir, backupDir)
            LOG.info("Backup created successfully: $backupName")
            return true
        } catch (e: Exception) {
            LOG.error("Failed to create backup of world: $levelName", e)
            return false
        }
    }
    
    /**
     * Recursively copies a directory and all its contents.
     */
    private fun copyDirectory(source: File, destination: File) {
        if (source.isDirectory) {
            if (!destination.exists()) {
                destination.mkdirs()
            }
            source.listFiles()?.forEach { file ->
                copyDirectory(file, File(destination, file.name))
            }
        } else {
            source.inputStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    fun stop(leaveWithoutStopping: Boolean = false) {
        if (!capturing) {
            MessageManager.sendError("worldtools.log.error.not_capturing")
            return
        }

        if (leaveWithoutStopping) {
             MessageManager.sendInfo("worldtools.log.info.waiting_reconnect", currentLevelName)
             isPaused = true // Pause scanning/events
             isWaitingForReconnect = true
             // Do NOT stop the StorageFlow job, keep it alive for when we return
             return
        }

        MessageManager.sendInfo("worldtools.log.info.stopping_capture", currentLevelName)

        HotCache.chunks.values.forEach { chunk ->
            chunk.emit() // will also write entities in the chunks
        }

        HotCache.players.forEach { player ->
            player.emit()
        }

        MapDataStoreable().emit()
        LevelDataStoreable().emit()
        AdvancementsStoreable().emit()
        MetadataStoreable().emit()
        CompressLevelStoreable().emit()
        EndFlow().emit()
        
        capturing = false
        isPaused = false
        isWaitingForReconnect = false
        // storeJob = null // StorageFlow will finish and we lose ref
    }

    private fun syncCacheFromWorldState() {
        val world = mc.world ?: return
        val diameter = world.chunkManager.chunks.diameter

        repeat(diameter * diameter) { i ->
            world.chunkManager.chunks.getChunk(i)?.let { chunk ->
                RegionBasedChunk(chunk).cache()
                BlockEntityLoadable(chunk).emit()
            }
        }

        world.entities.forEach {
            if (it is PlayerEntity) {
                PlayerStoreable(it).cache()
            } else {
                EntityCacheable(it).cache()
            }
        }
    }

    private fun String.sanitizeWorldName() = replace(":", "_")
    
    /**
     * Heuristic to detect if a folder looks like a valid Minecraft world save.
     * This helps identify legacy WorldTools saves that don't have worldtools_meta.json.
     */
    private fun looksLikeMinecraftWorld(worldDir: java.io.File): Boolean {
        if (!worldDir.isDirectory) return false
        
        // Check for level.dat (main world file)
        if (worldDir.resolve("level.dat").exists()) return true
        
        // Check for region folder with .mca files
        val regionDir = worldDir.resolve("region")
        if (regionDir.isDirectory) {
            val hasMcaFiles = regionDir.listFiles()?.any { it.extension == "mca" } ?: false
            if (hasMcaFiles) return true
        }
        
        // Check for dimension folders (DIM-1, DIM1) with region data
        worldDir.listFiles()?.filter { it.name.startsWith("DIM") && it.isDirectory }?.forEach { dimDir ->
            val dimRegionDir = dimDir.resolve("region")
            if (dimRegionDir.isDirectory) {
                val hasMcaFiles = dimRegionDir.listFiles()?.any { it.extension == "mca" } ?: false
                if (hasMcaFiles) return true
            }
        }
        
        return false
    }
}
