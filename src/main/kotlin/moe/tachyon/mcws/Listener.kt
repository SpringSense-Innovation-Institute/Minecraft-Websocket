package moe.tachyon.mcws

import org.bukkit.event.block.*
import org.bukkit.event.entity.EntityChangeBlockEvent
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent

@SubscribeEvent(ignoreCancelled = true, priority = EventPriority.MONITOR)
fun onBlockBreak(event: BlockBreakEvent) = Client.updateBlock(event.block)

@SubscribeEvent(ignoreCancelled = true, priority = EventPriority.MONITOR)
fun onBlockPlace(event: BlockPlaceEvent) = Client.updateBlock(event.block)

@SubscribeEvent(ignoreCancelled = true, priority = EventPriority.MONITOR)
fun onBlockPhysics(event: BlockPhysicsEvent) = Client.updateBlock(event.block)

@SubscribeEvent(ignoreCancelled = true, priority = EventPriority.MONITOR)
fun onEntityChangeBlock(event: EntityChangeBlockEvent) = Client.updateBlock(event.block)

@SubscribeEvent(ignoreCancelled = true, priority = EventPriority.MONITOR)
fun onBlockForm(event: BlockFormEvent) = Client.updateBlock(event.block)

@SubscribeEvent(ignoreCancelled = true, priority = EventPriority.MONITOR)
fun onBlockFade(event: BlockFadeEvent) = Client.updateBlock(event.block)