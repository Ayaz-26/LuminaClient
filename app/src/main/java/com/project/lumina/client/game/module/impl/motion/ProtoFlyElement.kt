package com.project.lumina.client.game.module.impl.motion

import com.project.lumina.client.R
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.util.AssetManager
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.*
import org.cloudburstmc.protocol.bedrock.packet.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ProtoFlyElement(
    iconResId: Int = AssetManager.getAsset("ic_feather_black_24dp")
) : Element(
    name = "ProtoFly",
    category = CheatCategory.Motion,
    iconResId,
    displayNameResId = R.string.module_fly_display_name
) {

    /* ---------- settings – exact Protohax names / ranges ---------- */
    private var mode by choiceValue("Mode", arrayOf("Vanilla", "Mineplex", "Jetpack", "Glide", "YPort"), "Vanilla")
    private var speed by floatValue("Speed", 1.5f, 0.1f..5f)
    private var pressJump by booleanValue("PressJump", true)
    private var mineplexMotion by booleanValue("MineplexMotion", false)   // only for Mineplex mode

    /* ---------- runtime ---------- */
    private var launchY = 0f
    private var yPortFlag = true
    private var glideActive = false

    private val canFly: Boolean
        get() = !pressJump || session.localPlayer.inputData.contains(PlayerAuthInputData.JUMP_DOWN)

    /* ---------- shared ability packet (may-fly + fly-speed) ---------- */
    private val abilityPacket = UpdateAbilitiesPacket().apply {
        playerPermission = PlayerPermission.OPERATOR
        commandPermission = CommandPermission.OWNER
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries)
            abilityValues.addAll(
                arrayOf(
                    Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                    Ability.OPERATOR_COMMANDS, Ability.MAY_FLY,
                    Ability.FLY_SPEED, Ability.WALK_SPEED
                )
            )
            walkSpeed = 0.1f
            flySpeed = 0.15f
        })
    }

    /* ---------- life-cycle ---------- */
    override fun onEnabled() {
        launchY = session.localPlayer.posY
        yPortFlag = true
        glideActive = false
    }

    override fun onDisabled() {
        if (glideActive) {
            session.clientBound(
                MobEffectPacket().apply {
                    event = MobEffectPacket.Event.REMOVE
                    runtimeEntityId = session.localPlayer.runtimeEntityId
                    effectId = Effect.SLOW_FALLING
                }
            )
            glideActive = false
        }
    }

    /* ---------- main intercept ---------- */
    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        val pkt = interceptablePacket.packet

        /* 1.  always cancel server fly-state changes */
        if (pkt is RequestAbilityPacket && pkt.ability == Ability.FLYING) {
            interceptablePacket.intercept()
            return
        }
        if (pkt is UpdateAbilitiesPacket) {
            interceptablePacket.intercept()
            return
        }

        /* 2.  send ability packet on StartGame */
        if (pkt is StartGamePacket) {
            abilityPacket.uniqueEntityId = session.localPlayer.uniqueEntityId
            session.clientBound(abilityPacket)
        }

        /* 3.  handle per-mode logic */
        when (mode) {
            "Vanilla"   -> handleVanilla(pkt, interceptablePacket)
            "Mineplex"  -> handleMineplex(pkt, interceptablePacket)
            "Jetpack"   -> handleJetpack(pkt, interceptablePacket)
            "Glide"     -> handleGlide(pkt, interceptablePacket)
            "YPort"     -> handleYPort(pkt, interceptablePacket)
        }
    }

    /* ---------------------------------------------------------- */
    /* -------------------- mode handlers ----------------------- */
    /* ---------------------------------------------------------- */
    private fun handleVanilla(pkt: BedrockPacket, ip: InterceptablePacket) {
        if (pkt is PlayerAuthInputPacket && isEnabled && canFly) {
            abilityPacket.uniqueEntityId = session.localPlayer.uniqueEntityId
            session.clientBound(abilityPacket)
        }
    }

    private fun handleMineplex(pkt: BedrockPacket, ip: InterceptablePacket) {
        if (pkt is PlayerAuthInputPacket) {
            if (!canFly) {
                launchY = session.localPlayer.posY
                return
            }
            val p = session.localPlayer
            val yaw = Math.toRadians(p.rotationYaw.toDouble()).toFloat()
            val dist = speed

            if (mineplexMotion) {
                val motion = SetEntityMotionPacket().apply {
                    runtimeEntityId = p.runtimeEntityId
                    motion = Vector3f.from(-sin(yaw) * dist, 0f, cos(yaw) * dist)
                }
                session.clientBound(motion)
            } else {
                p.teleport(p.posX - sin(yaw) * dist, launchY, p.posZ + cos(yaw) * dist)
            }

            /* lock Y on outbound auth packet */
            ip.intercept()
            pkt.position = Vector3f.from(pkt.position.x, launchY, pkt.position.z)
            session.clientBound(pkt)
        }
    }

    private fun handleJetpack(pkt: BedrockPacket, ip: InterceptablePacket) {
        if (pkt is PlayerAuthInputPacket && canFly) {
            val p = session.localPlayer
            val yawRad = Math.toRadians(p.rotationYaw.toDouble())
            val pitchRad = Math.toRadians(p.rotationPitch.toDouble()) * -1

            val motion = SetEntityMotionPacket().apply {
                runtimeEntityId = p.runtimeEntityId
                motion = Vector3f.from(
                    cos(yawRad) * cos(pitchRad) * speed,
                    sin(pitchRad) * speed,
                    sin(yawRad) * cos(pitchRad) * speed
                )
            }
            session.clientBound(motion)
        }
    }

    private fun handleGlide(pkt: BedrockPacket, ip: InterceptablePacket) {
        if (pkt is PlayerAuthInputPacket && session.localPlayer.tickExists % 20 == 0L) {
            session.clientBound(
                MobEffectPacket().apply {
                    event = MobEffectPacket.Event.ADD
                    runtimeEntityId = session.localPlayer.runtimeEntityId
                    effectId = Effect.SLOW_FALLING
                    amplifier = 0
                    duration = 360000
                    isParticles = false
                }
            )
            glideActive = true
        }
    }

    private fun handleYPort(pkt: BedrockPacket, ip: InterceptablePacket) {
        if (pkt is PlayerAuthInputPacket && canFly) {
            val p = session.localPlayer
            val yaw = Math.toRadians(p.rotationYaw.toDouble()).toFloat()
            val motion = SetEntityMotionPacket().apply {
                runtimeEntityId = p.runtimeEntityId
                motion = Vector3f.from(
                    -sin(yaw) * speed,
                    if (yPortFlag) 0.42f else -0.42f,
                    cos(yaw) * speed
                )
            }
            session.clientBound(motion)
            yPortFlag = !yPortFlag
        }
    }
}
