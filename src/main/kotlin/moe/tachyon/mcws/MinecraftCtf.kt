package moe.tachyon.mcws

import com.github.luben.zstd.Zstd
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.warning
import taboolib.platform.BukkitPlugin
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

object MinecraftWebsocketPlugin: Plugin()
{
    val config by lazy()
    {
        val file = BukkitPlugin.getInstance().dataFolder.resolve("config.json")
        if (!file.exists())
        {
            file.parentFile?.mkdirs()
            file.writeText(Json.encodeToString(Config(port = 8080)))
            return@lazy Config(port = 8080)
        }
        else
        {
            runCatching()
            {
                Json.decodeFromString<Config>(file.readText())
            }.getOrElse()
            {
                file.writeText(Json.encodeToString(Config(port = 8080)))
                return@lazy Config(port = 8080)
            }
        }
    }

    val server by lazy { SimpleServer(config.port) }

    override fun onEnable()
    {
        server.start()
    }

    override fun onDisable()
    {
        Client.closeAll()
        runCatching { server.stop() }.onFailure(::warning)
        Client.closeAll()
    }
}

data class Config(
    val port: Int,
)

class SimpleServer(port: Int) : WebSocketServer(InetSocketAddress(port))
{
    private val bots = ConcurrentHashMap<WebSocket, Client>()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake)
    {
        info("connected from ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean)
    {
        info("connection closed from ${conn.remoteSocketAddress} with exit code $code additional info: $reason")
        close(conn)
    }

    override fun onMessage(conn: WebSocket, message: String)
    {
        val msg = runCatching()
        {
            Json.decodeFromString<ReceivedMessage>(message)
        }.getOrElse()
        {
            warning("failed to decode message from ${conn.remoteSocketAddress}: ${it.message}")
            return
        }

        when (msg)
        {
            is ReceivedMessage.Chat  -> bots[conn]?.chat(msg.message)
            is ReceivedMessage.Input -> bots[conn]?.input = msg.input
            is ReceivedMessage.Login ->
            {
                synchronized(conn)
                {
                    if (bots.containsKey(conn))
                        submit { bots[conn]?.close() }
                    val client = Client(msg.name)

                    client.init()
                    { status ->
                        runCatching()
                        {
                            conn.send(Zstd.compress(Json.encodeToString(status).toByteArray()))
                        }.onFailure()
                        {
                            warning("failed to send status to ${conn.remoteSocketAddress}: ${it.message}")
                            close(conn)
                        }
                    }

                    bots[conn] = client
                }
            }
        }
    }


    override fun onError(conn: WebSocket, ex: Exception)
    {
        info("an error occurred on connection ${conn.remoteSocketAddress}: ${ex.message}")
        conn.close()
        close(conn)
    }

    override fun onStart() = info("服务器已启动!")

    private fun close(conn: WebSocket)
    {
        val bot = synchronized(this) { bots.remove(conn) } ?: return
        submit { bot.close() }
    }
}

@Serializable
sealed interface ReceivedMessage
{
    @Serializable
    @SerialName("login")
    data class Login(val name: String): ReceivedMessage
    @Serializable
    @SerialName("input")
    data class Input(val input: PlayerInput): ReceivedMessage
    @Serializable
    @SerialName("chat")
    data class Chat(val message: String): ReceivedMessage
}

@Serializable
data class Status(
    val playerLocation: Location,
    val playerHealth: Double,
    val playerFoodLevel: Int,
    val nearbyEntities: List<EntityInfo>,
    val keepChunks: List<KeepChunkInfo>,
    val newChunks: List<NewChunkInfo>,
    val updateBlocks: List<UpdateBlockInfo>,
    val backpack: List<ItemInfo>,
    val messages: List<String>,
)
{
    @Serializable
    data class Location(val x: Double, val y: Double, val z: Double)
    @Serializable
    data class EntityInfo(
        val id: String,
        val type: String,
        val name: String?,
        val x: Double,
        val y: Double,
        val z: Double,
        val helmet: String?,
        val chestplate: String?,
        val leggings: String?,
        val boots: String?,
    )
    @Serializable
    data class KeepChunkInfo(val x: Int, val y: Int, val z: Int)
    @Serializable
    data class NewChunkInfo(
        val x: Int,
        val y: Int,
        val z: Int,
        val blocks: MutableList<BlockInfo>,
    )
    {
        override fun equals(other: Any?): Boolean = other is NewChunkInfo && x == other.x && y == other.y && z == other.z
        override fun hashCode(): Int = x * 31 * 31 + y * 31 + z
        override fun toString(): String = "NewChunkInfo(x=$x, y=$y, z=$z, blocks=[${blocks.size} blocks])"
    }

    @Serializable
    data class BlockInfo(
        val type: String,
        val passable: Boolean,
    )
    @Serializable
    data class UpdateBlockInfo(
        val x: Int,
        val y: Int,
        val z: Int,
        val block: BlockInfo,
    )

    @Serializable
    data class ItemInfo(
        val type: String,
        val amount: Int,
    )

    companion object
    {
        val EMPTY = Status(
            playerLocation = Location(0.0, 0.0, 0.0),
            playerHealth = 20.0,
            playerFoodLevel = 20,
            nearbyEntities = emptyList(),
            keepChunks = emptyList(),
            newChunks = emptyList(),
            updateBlocks = emptyList(),
            backpack = emptyList(),
            messages = emptyList(),
        )
    }
}