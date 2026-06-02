@file:OptIn(ExperimentalUuidApi::class)

package moe.tachyon.mcws

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import taboolib.common.platform.function.submit
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
    private var init = false
    private lateinit var player: Player
    private val msg = mutableListOf<String>()
    private val statusLock = Object()
    private var pendingStatus: Status? = null

    fun init(updateStatus: (Status) -> Unit)
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

                submit(period = 1)
                {
                    if (closed) return@submit cancel()
                    val newStatus = tick()
                    synchronized(statusLock)
                    {
                        val pending = pendingStatus
                        pendingStatus = when
                        {
                            pending == null -> newStatus
                            pending.newChunks.isNotEmpty() -> pending
                            else -> newStatus
                        }
                        statusLock.notifyAll()
                    }
                }

                submit(async = true)
                {
                    while (true)
                    {
                        if (closed) return@submit
                        val status = synchronized(statusLock)
                        {
                            while (pendingStatus == null && !closed)
                                statusLock.wait(1000)
                            if (closed) return@submit
                            val tmp = pendingStatus
                            pendingStatus = null
                            tmp
                        }
                        status?.let(updateStatus)
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
                synchronized(statusLock)
                {
                    pendingStatus = null
                    statusLock.notifyAll()
                }
                clients.remove(name)
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

    @Volatile
    private var lastWorld = ""
    private val unupdatedBlocks = mutableSetOf<Block>()

    private val existingChunks = mutableSetOf<Status.KeepChunkInfo>()

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
            for (dy in -LOAD_CHUNK_VERTICAL_RADIUS..LOAD_CHUNK_VERTICAL_RADIUS)
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
            if (c !in chunks &&
                abs(c.x - playerChunkX) <= UNLOAD_CHUNK_RADIUS &&
                abs(c.y - playerChunkY) <= UNLOAD_CHUNK_RADIUS &&
                abs(c.z - playerChunkZ) <= UNLOAD_CHUNK_RADIUS)
            {
                chunks.add(c)
            }
        }

        val keepChunks = chunks.filter { it in existingChunks }
        val newChunks = chunks
            .asSequence()
            .filter { it !in existingChunks }
            .sortedWith(compareBy<Status.KeepChunkInfo>
            {
                abs(it.x - playerChunkX) + abs(it.y - playerChunkY) + abs(it.z - playerChunkZ)
            }.thenBy { it.x }.thenBy { it.y }.thenBy { it.z })
            .take(MAX_NEW_CHUNKS_PER_STATUS)
            .map()
        { c ->
            Status.NewChunkInfo(
                x = c.x,
                y = c.y,
                z = c.z,
                blocks = getChunkBlocks(player.world, c)
            )
        }.toList()

        existingChunks.retainAll(chunks)
        existingChunks += newChunks.map { Status.KeepChunkInfo(it.x, it.y, it.z) }

        val updateBlocks = unupdatedBlocks.mapNotNull()
        { block ->
            val chunk = Status.KeepChunkInfo(block.x shr 4, block.y shr 4, block.z shr 4)
            if (chunk !in existingChunks) return@mapNotNull null

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
        if (lastWorld != block.world.name) return
        val blockChunk = Status.KeepChunkInfo(block.x shr 4, block.y shr 4, block.z shr 4)
        synchronized(this)
        {
            if (closed || !init) return
            if (blockChunk in existingChunks)
                unupdatedBlocks.add(block)
        }
    }

    companion object
    {
        const val LOAD_CHUNK_RADIUS = 3
        const val LOAD_CHUNK_VERTICAL_RADIUS = 0
        const val UNLOAD_CHUNK_RADIUS = 4
        const val VIEW_ENTITY_RADIUS = 3 * 16
        const val MAX_NEW_CHUNKS_PER_STATUS = 4

        private val clients = ConcurrentHashMap<String, Client>()
        private data class ChunkCacheKey(val world: String, val x: Int, val y: Int, val z: Int)
        private val chunkCache = ConcurrentHashMap<ChunkCacheKey, List<Status.BlockInfo>>()

        private fun getChunkBlocks(world: World, chunk: Status.KeepChunkInfo): List<Status.BlockInfo>
        {
            val key = ChunkCacheKey(world.name, chunk.x, chunk.y, chunk.z)
            return chunkCache.computeIfAbsent(key)
            {
                List(1 shl 12)
                { i ->
                    val blockX = (chunk.x shl 4) + (i and 0xF)
                    val blockY = (chunk.y shl 4) + ((i shr 4) and 0xF)
                    val blockZ = (chunk.z shl 4) + ((i shr 8) and 0xF)
                    val blk = world.getBlockAt(blockX, blockY, blockZ)
                    Status.BlockInfo(
                        type = blk.type.key.toString(),
                        passable = blk.isPassable,
                    )
                }
            }
        }

        fun get(name: String): Client? = clients[name]
        fun closeAll()
        {
            clients.values.forEach(Client::close)
            clients.clear()
        }

        fun updateBlock(block: Block)
        {
            chunkCache.remove(ChunkCacheKey(block.world.name, block.x shr 4, block.y shr 4, block.z shr 4))
            clients.values.forEach()
            { client ->
                client.addBlockUpdate(block)
            }
        }
    }
}
