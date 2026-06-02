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

@Serializable
data class Config(
    val port: Int,
)

class SimpleServer(port: Int) : WebSocketServer(InetSocketAddress(port))
{
    private val bots = ConcurrentHashMap<WebSocket, Client>()
    private val bufferedStatusTicks = ConcurrentHashMap<WebSocket, Int>()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake)
    {
        info("connected from ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean)
    {
        info("connection closed from ${conn.remoteSocketAddress} with exit code $code additional info: $reason")
        bufferedStatusTicks.remove(conn)
        close(conn)
    }

    override fun onMessage(conn: WebSocket, message: String)
    {
        if (message.length > MAX_MESSAGE_CHARS)
        {
            warning("closing ${conn.remoteSocketAddress}: websocket message exceeds $MAX_MESSAGE_CHARS characters")
            conn.close()
            close(conn)
            return
        }

        val msg = runCatching()
        {
            Json.decodeFromString<ReceivedMessage>(message)
        }.getOrElse()
        {
            warning("failed to decode message from ${conn.remoteSocketAddress}: ${it.message}")
            return
        }

        if (!validate(conn, msg))
            return

        when (msg)
        {
            is ReceivedMessage.Chat  -> bots[conn]?.chat(msg.message)
            is ReceivedMessage.Input -> bots[conn]?.input = msg.input
            is ReceivedMessage.Login -> submit { login(conn, msg) }
        }
    }

    private fun validate(conn: WebSocket, msg: ReceivedMessage): Boolean
    {
        val reason = when (msg)
        {
            is ReceivedMessage.Login -> if (PLAYER_NAME.matches(msg.name)) null else "invalid player name"
            is ReceivedMessage.Chat  -> if (msg.message.length <= MAX_CHAT_CHARS) null else "chat message exceeds $MAX_CHAT_CHARS characters"
            is ReceivedMessage.Input -> when
            {
                msg.input.attack == null -> null
                ATTACK_ID.matches(msg.input.attack) -> null
                else -> "invalid attack id"
            }
        }

        if (reason == null)
            return true

        warning("closing ${conn.remoteSocketAddress}: $reason")
        conn.close()
        close(conn)
        return false
    }


    override fun onError(conn: WebSocket?, ex: Exception)
    {
        if (conn == null)
        {
            warning("websocket server error: ${ex.message}")
            return
        }

        info("an error occurred on connection ${conn.remoteSocketAddress}: ${ex.message}")
        conn.close()
        close(conn)
    }

    override fun onStart() = info("服务器已启动!")

    private fun login(conn: WebSocket, msg: ReceivedMessage.Login)
    {
        synchronized(conn)
        {
            if (!conn.isOpen) return

            runCatching { bots.remove(conn)?.close() }.onFailure(::warning)

            val client = Client(msg.name)
            runCatching()
            {
                client.init()
                { status -> sendStatus(conn, status) }
            }.onSuccess()
            {
                if (conn.isOpen)
                    bots[conn] = client
                else
                    client.close()
            }.onFailure()
            {
                warning("failed to initialize player ${msg.name} from ${conn.remoteSocketAddress}: ${it.message}")
                client.close()
                conn.close()
            }
        }
    }

    private fun sendStatus(conn: WebSocket, status: Status)
    {
        if (!conn.isOpen)
        {
            close(conn)
            return
        }

        if (conn.hasBufferedData())
        {
            val bufferedTicks = bufferedStatusTicks.compute(conn)
            { _, value -> (value ?: 0) + 1 } ?: 1
            if (bufferedTicks >= MAX_BUFFERED_STATUS_TICKS)
            {
                warning("closing slow status client ${conn.remoteSocketAddress}: websocket output stayed buffered for $bufferedTicks ticks")
                conn.close()
                close(conn)
            }
            return
        }

        bufferedStatusTicks.remove(conn)
        runCatching()
        {
            conn.send(Zstd.compress(Json.encodeToString(status).toByteArray()))
        }.onFailure()
        {
            warning("failed to send status to ${conn.remoteSocketAddress}: ${it.message}")
            close(conn)
        }
    }

    private fun close(conn: WebSocket)
    {
        bufferedStatusTicks.remove(conn)
        val bot = synchronized(conn) { bots.remove(conn) } ?: return
        submit { bot.close() }
    }

    companion object
    {
        private const val MAX_MESSAGE_CHARS = 16384
        private const val MAX_CHAT_CHARS = 8192
        private const val MAX_BUFFERED_STATUS_TICKS = 100
        private val PLAYER_NAME = Regex("^[A-Za-z0-9_]{1,16}$")
        private val ATTACK_ID = Regex("^[0-9a-fA-F]{32}$")
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
