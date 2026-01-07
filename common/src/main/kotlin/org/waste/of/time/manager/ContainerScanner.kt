package org.waste.of.time.manager

import kotlinx.coroutines.*
import net.minecraft.block.entity.LockableContainerBlockEntity
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import org.waste.of.time.WorldTools.config
import org.waste.of.time.WorldTools.mc
import kotlin.coroutines.CoroutineContext

object ContainerScanner {
    private var scanJob: Job? = null
    var isScanning = false
        private set

    private val MainDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            mc.execute(block)
        }
    }

    fun toggleScan() {
        if (isScanning) stopScan() else startScan()
    }

    private fun startScan() {
        if (isScanning) return
        val player = mc.player ?: return
        val world = mc.world ?: return
        
        isScanning = true
        MessageManager.sendInfo("worldtools.log.info.scanner_started")

        scanJob = CoroutineScope(Dispatchers.IO).launch {
            val radius = config.general.containerScanner.radius
            val playerPos = player.blockPos
            
            // Find all candidate container positions
            val containers = mutableListOf<BlockPos>()
            
            // Iterate systematically through the volume
            for (x in -radius..radius) {
                for (y in -radius..radius) {
                    for (z in -radius..radius) {
                        val pos = playerPos.add(x, y, z)
                        // We need to check if it's a container. Accessing world state from IO thread is risky/illegal.
                        // We must collect potential positions and filter carefully or use read execution.
                        containers.add(pos)
                    }
                }
            }

            // Filter containers on main thread to be safe with World access
            val validContainers = withContext(MainDispatcher) {
                containers.filter { pos ->
                    world.getBlockEntity(pos) is LockableContainerBlockEntity
                }.sortedBy { it.getSquaredDistance(playerPos) }
            }

            MessageManager.sendInfo("worldtools.log.info.scanner_found", validContainers.size)

            for (pos in validContainers) {
                if (!isScanning) break
                
                // Open container
                withContext(MainDispatcher) {
                    val interactionManager = mc.interactionManager
                    if (interactionManager != null) {
                         // Simulate interaction
                         interactionManager.interactBlock(
                             player,
                             net.minecraft.util.Hand.MAIN_HAND,
                             BlockHitResult(Vec3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5), Direction.UP, pos, false)
                         )
                    }
                }
                
                // Wait for GUI to open (network lag)
                delay(config.general.containerScanner.delayMs)
                
                // Close container (ESC)
                withContext(MainDispatcher) {
                    if (mc.currentScreen != null) {
                        mc.player?.closeHandledScreen()
                        mc.setScreen(null)
                    }
                }
                
                delay(100) // Small buffer
            }

            isScanning = false
            MessageManager.sendInfo("worldtools.log.info.scanner_finished")
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        isScanning = false
        MessageManager.sendInfo("worldtools.log.info.scanner_stopped")
    }
}
