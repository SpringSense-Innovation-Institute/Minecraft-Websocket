package moe.tachyon.mcws

import com.mojang.authlib.GameProfile
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.ReferenceCountUtil
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import net.minecraft.network.NetworkManager
import net.minecraft.network.protocol.EnumProtocolDirection
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.EntityPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.PlayerConnection
import net.minecraft.world.EnumHand
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.craftbukkit.v1_21_R1.CraftServer
import org.bukkit.craftbukkit.v1_21_R1.CraftWorld
import org.bukkit.craftbukkit.v1_21_R1.entity.CraftEntity
import org.bukkit.craftbukkit.v1_21_R1.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerMoveEvent
import taboolib.common.platform.function.submit
import taboolib.common.util.unsafeLazy
import taboolib.module.nms.nmsProxy
import java.net.InetSocketAddress
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
data class PlayerInput(
    val yaw: Float = 0.0f, val pitch: Float = 0.0f,
    val w: Boolean = false, val a: Boolean = false, val s: Boolean = false, val d: Boolean = false,
    val jump: Boolean = false, val sneak: Boolean = false, val sprint: Boolean = false, val fly: Boolean = false,
    val attack: String? = null,
)
{
    companion object
    {
        val EMPTY = PlayerInput(
            yaw = 0f,
            pitch = 0f,
            w = false,
            a = false,
            s = false,
            d = false,
            jump = false,
            sneak = false,
            sprint = false,
            fly = false,
            attack = null
        )
    }
}

abstract class BotNMSHandler
{
    abstract fun spawnFakePlayer(name: String, location: Location, msgListener: (msg: String) -> Unit): Player
    abstract fun tickBot(player: Player, input: PlayerInput)

    companion object
    {
        val instance by unsafeLazy { nmsProxy<BotNMSHandler>() }
    }
}

@Suppress("unused")
class BotNMSHandlerImpl: BotNMSHandler()
{
    override fun spawnFakePlayer(
        name: String,
        location: Location,
        msgListener: (msg: String) -> Unit,
    ): Player
    {
        val nmsServer = (Bukkit.getServer() as CraftServer).server
        val nmsWorld = (location.world as CraftWorld).handle
        val profile = GameProfile(Bukkit.getOfflinePlayer(name).uniqueId, name)
        val bot = EntityPlayer(nmsServer, nmsWorld, profile, ClientInformation.createDefault())

        val fakeConnection = NetworkManager(EnumProtocolDirection.SERVERBOUND)

        val channelField = NetworkManager::class.java.declaredFields.firstOrNull { it.type == Channel::class.java }
        channelField?.isAccessible = true
        channelField?.set(fakeConnection, EmbeddedChannel().apply()
        {
            pipeline().addFirst("bot_discard", object: ChannelDuplexHandler()
            {
                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise)
                {
                    try
                    {
                        when (msg)
                        {
                            is ClientboundSystemChatPacket    -> msgListener(msg.content().string)
                            is ClientboundPlayerChatPacket    -> msgListener(msg.unsignedContent()?.string ?: msg.body().content())
                            is ClientboundDisguisedChatPacket -> msgListener(msg.message().string)
                        }
                    }
                    catch (e: Throwable)
                    {
                        e.printStackTrace()
                    }
                    ReferenceCountUtil.release(msg)
                    promise.setSuccess()
                }

                override fun flush(ctx: ChannelHandlerContext?) = Unit
            })
        })
        channelField?.isAccessible = false

        val cookie = CommonListenerCookie.createInitial(profile, false)

        fakeConnection.address = InetSocketAddress(0)
        val listener = object: PlayerConnection(nmsServer, fakeConnection, bot, cookie)
        {
            override fun tick() = Unit
        }
        bot.connection = listener

        bot.setPos(location.x, location.y, location.z)
        bot.yRot = location.yaw
        bot.xRot = location.pitch

        submit { nmsServer.playerList.placeNewPlayer(fakeConnection, bot, cookie) }

        return CraftPlayer(
            Bukkit.getServer() as CraftServer,
            bot
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    override fun tickBot(player: Player, input: PlayerInput)
    {
        val nmsPlayer = (player as CraftPlayer).handle
        val oldLocation = player.location.clone()

        nmsPlayer.yRot = input.yaw
        nmsPlayer.xRot = input.pitch
        nmsPlayer.yHeadRot = input.yaw
        nmsPlayer.yBodyRot = input.yaw

        nmsPlayer.isShiftKeyDown = input.sneak
        nmsPlayer.isSprinting = input.sprint && input.w && nmsPlayer.foodData.foodLevel > 6

        nmsPlayer.abilities.flying = input.fly && nmsPlayer.abilities.mayfly

        var forward = 0f
        var strafe = 0f

        if (input.w) forward += 1f
        if (input.s) forward -= 1f
        if (input.a) strafe += 1f
        if (input.d) strafe -= 1f

        if (input.sneak)
        {
            forward *= 0.3f
            strafe *= 0.3f
        }

        nmsPlayer.xxa = strafe
        nmsPlayer.zza = forward
        nmsPlayer.setJumping(input.jump)
        nmsPlayer.aiStep()
        nmsPlayer.foodData.javaClass.methods.first { it.name == "tick" }.invoke(nmsPlayer.foodData, nmsPlayer)
        nmsPlayer.detectEquipmentUpdates()

        val newLocation = player.location.clone()

        if (oldLocation != newLocation)
        {
            val event = PlayerMoveEvent(player, oldLocation, newLocation)
            Bukkit.getPluginManager().callEvent(event)
            if (event.isCancelled)
            {
                nmsPlayer.setPos(oldLocation.x, oldLocation.y, oldLocation.z)
                nmsPlayer.yRot = oldLocation.yaw
                nmsPlayer.xRot = oldLocation.pitch
                nmsPlayer.yHeadRot = oldLocation.yaw
                nmsPlayer.yBodyRot = oldLocation.yaw
            }
        }


        val attack = runCatching { Uuid.parseHex(input.attack!!).toJavaUuid() }.getOrNull()
        if (attack != null)
        {
            val targetBukkit = Bukkit.getEntity(attack)
            if (targetBukkit != null)
            {
                val targetNMS = (targetBukkit as CraftEntity).handle
                val reach = if (nmsPlayer.isCreative) 5.0 else 3.0
                val eyePos = nmsPlayer.eyePosition
                val targetBox = targetNMS.boundingBox
                val distanceSq = targetBox.distanceToSqr(eyePos)
                if (distanceSq <= reach * reach)
                {
                    nmsPlayer.attack(targetNMS)
                    nmsPlayer.resetAttackStrengthTicker()
                    nmsPlayer.swing(EnumHand.MAIN_HAND, true)
                }
            }
        }
    }
}