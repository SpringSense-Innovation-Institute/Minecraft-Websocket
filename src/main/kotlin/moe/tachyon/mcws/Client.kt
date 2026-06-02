@file:OptIn(ExperimentalUuidApi::class)

package moe.tachyon.mcws

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import taboolib.common.platform.function.submit
import taboolib.common.platform.service.PlatformExecutor.PlatformTask
import taboolib.platform.util.isNotAir
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

class Client(
    val name: String,
)
{
    var input = PlayerInput.EMPTY
        set(value) = synchronized(this) { field = value }

    @Volatile
    private var closed = false
    @Volatile
    private var init = false
    private lateinit var player: Player
    private val msg = mutableListOf<String>()
    private val statusList = mutableListOf<Status>()
    private var tickTask: PlatformTask? = null
    private var statusTask: PlatformTask? = null
    private var fullStatusQueueTicks = 0

    fun init(updateStatus: (Status) -> Unit, closeConnection: () -> Unit)
    {
        synchronized(this)
        {
            synchronized(Client)
            {
                if (init || closed) return
                init = true
                if (clients.containsKey(name)) error("Player $name already exists")
                val loc = Bukkit.getOfflinePlayer(name).location?.takeIf { it.world != null } ?: Bukkit.getWorlds()[0].spawnLocation

                player = BotNMSHandler.instance.spawnFakePlayer(name, loc)
                {
                    synchronized(this) { msg.add(it) }
                }

                clients[name] = this

                tickTask = submit(period = 1)
                {
                    if (closed)
                    {
                        this.cancel()
                        return@submit
                    }

                    val queueFull = synchronized(statusList) { statusList.size >= MAX_PENDING_STATUSES }
                    if (queueFull)
                    {
                        fullStatusQueueTicks++
                        if (fullStatusQueueTicks >= MAX_FULL_STATUS_QUEUE_TICKS)
                            closeConnection()
                        return@submit
                    }
                    fullStatusQueueTicks = 0

                    val newStatus = tick()
                    synchronized(statusList)
                    {
                        if (closed)
                            return@submit
                        statusList.add(newStatus)
                        (statusList as Object).notifyAll()
                    }
                }

                statusTask = submit(async = true)
                {
                    while (true)
                    {
                        if (closed)
                        {
                            this.cancel()
                            return@submit
                        }

                        val status = synchronized(statusList)
                        {
                            while (statusList.isEmpty() && !closed)
                                (statusList as Object).wait(1000)
                            if (closed)
                                return@submit
                            val tmp = statusList.toList()
                            statusList.clear()
                            tmp
                        }
                        status.forEach { updateStatus(it) }
                    }
                }
            }
        }
    }

    fun close()
    {
        synchronized(this)
        {
            synchronized(Client)
            {
                if (closed) return
                closed = true
                clients.remove(name)
                tickTask?.cancel()
                statusTask?.cancel()
                synchronized(statusList)
                {
                    (statusList as Object).notifyAll()
                    statusList.clear()
                }
                if (!init) return
                player.kickPlayer(null)
            }
        }
    }

    fun chat(message: String) = synchronized(this)
    {
        if (!closed && init)
            submit { player.chat(message) }
    }

    private var lastWorld = ""
    private val unupdatedBlocks = mutableSetOf<Block>()

    private val existingChunks = mutableMapOf<Status.KeepChunkInfo, MutableList<Status.BlockInfo>>()

    fun tick(): Status = synchronized(this)
    {
        if (closed || !init) return Status.EMPTY
        BotNMSHandler.instance.tickBot(player, input)
        input = input.copy(attack = null)

        if (player.world.name != lastWorld)
        {
            lastWorld = player.world.name
            existingChunks.clear()
        }

        val playerLocation = Status.Location(player.location.x, player.location.y, player.location.z)
        val playerHealth = player.health
        val playerFoodLevel = player.foodLevel
        val nearbyEntities = player.getNearbyEntities(VIEW_ENTITY_RADIUS.toDouble(), VIEW_ENTITY_RADIUS.toDouble(), VIEW_ENTITY_RADIUS.toDouble())
            .map { entity ->
                Status.EntityInfo(
                    id = entity.uniqueId.toKotlinUuid().toHexString(),
                    type = entity.type.key.toString(),
                    name = if (entity is Player) entity.name else entity.customName,
                    x = entity.location.x,
                    y = entity.location.y,
                    z = entity.location.z,
                    helmet = (entity as? LivingEntity)?.equipment?.helmet?.type?.takeUnless(Material::isAir)?.key?.toString(),
                    chestplate = (entity as? LivingEntity)?.equipment?.chestplate?.type?.takeUnless(Material::isAir)?.key?.toString(),
                    leggings = (entity as? LivingEntity)?.equipment?.leggings?.type?.takeUnless(Material::isAir)?.key?.toString(),
                    boots = (entity as? LivingEntity)?.equipment?.boots?.type?.takeUnless(Material::isAir)?.key?.toString(),
                )
            }
        val playerChunkX = player.location.blockX shr 4
        val playerChunkY = player.location.blockY shr 4
        val playerChunkZ = player.location.blockZ shr 4

        val chunks = mutableSetOf<Status.KeepChunkInfo>()

        for (dx in -LOAD_CHUNK_RADIUS..LOAD_CHUNK_RADIUS)
        {
            for (dy in -LOAD_CHUNK_RADIUS..LOAD_CHUNK_RADIUS)
            {
                for (dz in -LOAD_CHUNK_RADIUS..LOAD_CHUNK_RADIUS)
                {
                    val chunkX = playerChunkX + dx
                    val chunkY = playerChunkY + dy
                    val chunkZ = playerChunkZ + dz
                    chunks.add(Status.KeepChunkInfo(chunkX, chunkY, chunkZ))
                }
            }
        }

        for (c in existingChunks)
        {
            if (Status.KeepChunkInfo(c.key.x, c.key.y, c.key.z) !in chunks &&
                abs(c.key.x - playerChunkX) <= UNLOAD_CHUNK_RADIUS &&
                abs(c.key.y - playerChunkY) <= UNLOAD_CHUNK_RADIUS &&
                abs(c.key.z - playerChunkZ) <= UNLOAD_CHUNK_RADIUS)
            {
                chunks.add(Status.KeepChunkInfo(c.key.x, c.key.y, c.key.z))
            }
        }

        val keepChunks = chunks.filter { it in existingChunks }
        val newChunks = chunks.filter { it !in existingChunks }.take(MAX_NEW_CHUNKS_PER_STATUS).map()
        { c ->
            Status.NewChunkInfo(
                x = c.x,
                y = c.y,
                z = c.z,
                blocks = MutableList(1 shl 12)
                { i ->
                    val blockX = (c.x shl 4) + (i and 0xF)
                    val blockY = (c.y shl 4) + ((i shr 4) and 0xF)
                    val blockZ = (c.z shl 4) + ((i shr 8) and 0xF)
                    val blk = player.world.getBlockAt(blockX, blockY, blockZ)
                    Status.BlockInfo(
                        type = blk.type.key.toString(),
                        passable = blk.isPassable,
                    )
                }
            )
        }

        existingChunks.keys.removeIf { it !in keepChunks }
        existingChunks += newChunks.map { Status.KeepChunkInfo(it.x, it.y, it.z) to it.blocks }

        val updateBlocks = unupdatedBlocks.map()
        { block ->
            val chunk = existingChunks[Status.KeepChunkInfo(block.x shr 4, block.y shr 4, block.z shr 4)]
            chunk?.set(
                ((block.z and 0xF) shl 8) or ((block.y and 0xF) shl 4) or (block.x and 0xF),
                Status.BlockInfo(
                    type = block.type.key.toString(),
                    passable = block.isPassable,
                )
            )

            Status.UpdateBlockInfo(
                x = block.x,
                y = block.y,
                z = block.z,
                block = Status.BlockInfo(
                    type = block.type.key.toString(),
                    passable = block.isPassable,
                ),
            )
        }

        unupdatedBlocks.clear()

        val backpack = player.inventory.contents.filterNotNull().filter { it.type.isNotAir() }.map()
        { item ->
            Status.ItemInfo(
                type = item.type.key.toString(),
                amount = item.amount,
            )
        }

        val messages = msg.toList()
        msg.clear()

        val s = Status(
            playerLocation = playerLocation,
            playerHealth = playerHealth,
            playerFoodLevel = playerFoodLevel,
            nearbyEntities = nearbyEntities,
            keepChunks = keepChunks,
            newChunks = newChunks,
            updateBlocks = updateBlocks,
            backpack = backpack,
            messages = messages,
        )

        return s
    }

    private fun addBlockUpdate(block: Block)
    {
        synchronized(this)
        {
            if (closed || !init) return
            val blockChunkX = block.x shr 4
            val blockChunkY = block.y shr 4
            val blockChunkZ = block.z shr 4

            val x = block.x and 0xF
            val y = block.y and 0xF
            val z = block.z and 0xF

            val blockIndex = (z shl 8) or (y shl 4) or x

            val chunk = existingChunks[Status.KeepChunkInfo(blockChunkX, blockChunkY, blockChunkZ)]

            if (chunk != null && chunk[blockIndex] != Status.BlockInfo(block.type.key.toString(), block.isPassable))
                unupdatedBlocks.add(block)
        }
    }

    companion object
    {
        const val LOAD_CHUNK_RADIUS = 3
        const val UNLOAD_CHUNK_RADIUS = 4
        const val VIEW_ENTITY_RADIUS = 3 * 16
        private const val MAX_NEW_CHUNKS_PER_STATUS = 1
        private const val MAX_PENDING_STATUSES = 5
        private const val MAX_FULL_STATUS_QUEUE_TICKS = 100

        private val clients = ConcurrentHashMap<String, Client>()
        fun get(name: String): Client? = clients[name]
        fun closeAll()
        {
            clients.values.forEach(Client::close)
            clients.clear()
        }

        fun updateBlock(block: Block)
        {
            clients.values.forEach()
            { client ->
                client.addBlockUpdate(block)
            }
        }
    }
}
