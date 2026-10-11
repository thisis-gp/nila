package com.nila.launcher

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal enum class MascotMood { Idle, Thinking, Talking, Celebrate, Listening, Error, Greeting, Sleeping, Walk }

private val MascotYellow = Color(0xFFFFC000)
private val MascotInk = Color(0xFF151513)
private val MascotPaper = Color(0xFFF2F0E9)
private val MascotLime = Color(0xFFD9DF36)

// The whole rig is drawn in "body radius" units: the body disc has radius 1, so stroke widths and
// positions below are fractions of the body. The canvas is scaled once; nothing is allocated per frame.
private const val TAU = 6.2831855f
private const val PI_F = 3.1415927f
private const val CLOCK_PERIOD_MS = 6000
private const val CLOCK_PERIOD_S = 6f
private const val BODY_RADIUS_OF_WIDTH = 0.28f
private const val BODY_CENTER_Y_OF_HEIGHT = 0.38f
private const val BUMP_COUNT = 8
private const val BUMP_RING = 0.74f
private const val BUMP_RADIUS = 0.46f
private const val SHOULDER_X = 0.78f
private const val SHOULDER_Y = 0.62f
private const val LEG_X = 0.17f
private const val LEG_TOP = 0.86f
private const val LEG_HEIGHT = 0.62f
private const val LEG_WIDTH = 0.26f
private const val FEET_PIVOT_Y = 1.45f
private const val SQUASH_PIVOT_Y = 0.9f
private const val EYE_Y = -0.08f
private const val LEFT_EYE_X = -0.34f
private const val RIGHT_EYE_X = 0.26f
private const val MOUTH_Y = 0.28f
private const val BLINK_CLOSED = 0.12f
private const val BLINK_MS = 110L
private const val IDLE_ACTION_COUNT = 3
private const val IDLE_MIN_WAIT_MS = 4500L
private const val IDLE_MAX_WAIT_MS = 9000L
private const val ARM_WIDTH = 0.11f
private const val WINK_WIDTH = 0.08f
private const val Z_WIDTH = 0.05f
private val IdleActionMs = intArrayOf(900, 1300, 1500)

private val ArmStroke = Stroke(width = ARM_WIDTH, cap = StrokeCap.Round)
private val MouthStroke = Stroke(width = 0.07f, cap = StrokeCap.Round)
private val EyeLidStroke = Stroke(width = 0.06f, cap = StrokeCap.Round)
private val LegOutline = Stroke(width = 0.045f)
private val RingStroke = Stroke(width = 0.05f)

private val BumpX = FloatArray(BUMP_COUNT) { cos(TAU * it / BUMP_COUNT) * BUMP_RING }
private val BumpY = FloatArray(BUMP_COUNT) { sin(TAU * it / BUMP_COUNT) * BUMP_RING }

private fun wave(t: Float, hz: Float, phase: Float = 0f): Float = sin(TAU * hz * t + phase)
private fun mix(a: Float, b: Float, f: Float): Float = a + (b - a) * f
private fun frac(x: Float): Float = x - floor(x)

/** One arm pose: where the hand is (x relative to its own shoulder side), how the elbow bows, how crossed it is. */
private class ArmKey(val hx: Float, val hy: Float, val bulge: Float, val cross: Float)

private val ArmCrossed = ArmKey(-0.46f, 0.44f, 0.30f, 1f)
private val ArmHang = ArmKey(0.66f, 1.0f, 0.25f, 0f)
private val ArmSwing = ArmKey(0.92f, 0.78f, 0.20f, 0f)
private val ArmOpen = ArmKey(1.40f, 0.30f, -0.25f, 0f)
private val ArmUp = ArmKey(1.05f, -0.70f, -0.12f, 0f)
private val ArmWave = ArmKey(1.00f, -0.45f, -0.15f, 0f)
private val ArmChin = ArmKey(0.28f, 0.74f, 0.50f, 0f)
private val ArmShrug = ArmKey(1.15f, 0.15f, -0.50f, 0f)
private val ArmTalkLow = ArmKey(0.85f, 0.85f, 0.20f, 0f)
private val ArmTalkHigh = ArmKey(1.10f, 0.10f, -0.35f, 0f)
private val ArmFidget = ArmKey(0.10f, 0.35f, 0.30f, 0.3f)

private class Arm {
    var hx = 0f
    var hy = 0f
    var bulge = 0f
    var cross = 1f

    fun set(k: ArmKey) = blend(k, k, 0f)

    fun blend(a: ArmKey, b: ArmKey, f: Float) {
        hx = mix(a.hx, b.hx, f)
        hy = mix(a.hy, b.hy, f)
        bulge = mix(a.bulge, b.bulge, f)
        cross = mix(a.cross, b.cross, f)
    }
}

/** A leg: lift shortens it (foot raised or tucked), angle swings it from the hip. */
private class Leg {
    var lift = 0f
    var angle = 0f
}

/** One-shot and looping events driven by Animatables; the continuous motion comes from the shared clock. */
private class MascotEvents {
    val idle = Animatable(1f)
    var idleKind = 0
    val hop = Animatable(0f)
    val jump = Animatable(0f)
    val squash = Animatable(1f)
    val armUp = Animatable(0f)

    suspend fun reset() {
        idle.snapTo(1f)
        hop.snapTo(0f)
        jump.snapTo(0f)
        squash.snapTo(1f)
        armUp.snapTo(0f)
    }
}

private class Rig {
    var bx = 0f
    var by = 0f
    var air = 0f
    var tilt = 0f
    var grow = 1f
    var sy = 1f
    var eyeX = 0f
    var eyeY = 0f
    var smile = 1f
    var mouthOpen = 0f
    var sad = 0f
    var sleepy = 0f
    var ring = 0f
    var zPhase = 0f
    val armL = Arm()
    val armR = Arm()
    val legL = Leg()
    val legR = Leg()

    private fun clear() {
        bx = 0f; by = 0f; air = 0f; tilt = 0f; grow = 1f; sy = 1f
        eyeX = 0f; eyeY = 0f; smile = 1f; mouthOpen = 0f; sad = 0f; sleepy = 0f
        ring = 0f; zPhase = 0f
        armL.set(ArmCrossed); armR.set(ArmCrossed)
        legL.lift = 0f; legL.angle = 0f; legR.lift = 0f; legR.angle = 0f
    }

    fun update(mood: MascotMood, t: Float, ev: MascotEvents) {
        clear()
        when (mood) {
            MascotMood.Idle -> idle(t, ev)
            MascotMood.Sleeping -> {
                sy = 1f + 0.03f * wave(t, 1f / 3f)
                tilt = 6f
                sleepy = 1f
                armL.set(ArmHang); armR.set(ArmHang)
                zPhase = frac(t * 0.5f)
            }
            MascotMood.Listening -> {
                // Leans toward you, head tipped, arms open and receptive.
                bx = 0.10f
                grow = 1.05f
                tilt = 5f + 1.5f * wave(t, 1f / 3f)
                sy = 1.02f + 0.012f * wave(t, 1f)
                eyeY = -0.05f
                val open = wave(t, 1f)
                armL.set(ArmOpen); armR.set(ArmOpen)
                armL.hy += 0.04f * open; armR.hy -= 0.04f * open
                ring = frac(t / 1.2f)
            }
            MascotMood.Thinking -> {
                // Hand on chin, slow sway, weight shifting from foot to foot.
                val sway = wave(t, 1f / 3f)
                bx = 0.07f * sway
                tilt = 3f * sway
                sy = 1f + 0.012f * wave(t, 0.5f)
                eyeX = 0.08f; eyeY = -0.06f
                smile = 0.35f
                legL.lift = 0.07f * max(0f, sway)
                legR.lift = 0.07f * max(0f, -sway)
                armR.set(ArmChin)
                armR.hy += 0.02f * wave(t, 1f)
            }
            MascotMood.Talking -> {
                val beat = wave(t, 1.5f)
                mouthOpen = (0.5f + 0.5f * wave(t, 3f)) * (0.7f + 0.3f * wave(t, 1f, 1.2f))
                by = -0.03f * abs(beat)
                tilt = 1.5f * wave(t, 0.5f)
                armL.blend(ArmTalkLow, ArmTalkHigh, 0.5f + 0.5f * beat)
                armR.blend(ArmTalkLow, ArmTalkHigh, 0.5f + 0.5f * wave(t, 1.5f, 2.2f))
            }
            MascotMood.Greeting -> {
                val hop = ev.hop.value
                air = hop
                legL.lift = 0.25f * hop; legR.lift = 0.25f * hop
                sy = ev.squash.value + 0.02f * wave(t, 2f)
                tilt = -3f + 1.5f * wave(t, 1f)
                mouthOpen = 0.4f
                armL.set(ArmHang)
                armL.hy += 0.03f * wave(t, 1f)
                armR.set(ArmWave)
                armR.hx += 0.22f * wave(t, 2f)
                armR.hy += 0.05f * abs(wave(t, 2f))
            }
            MascotMood.Celebrate -> {
                val up = ev.armUp.value
                air = ev.jump.value
                sy = ev.squash.value
                tilt = 3f * up * wave(t, 1.5f)
                mouthOpen = 0.9f * up
                legL.lift = 0.30f * air; legR.lift = 0.30f * air
                legL.angle = 10f * air; legR.angle = -10f * air
                armL.blend(ArmCrossed, ArmUp, up)
                armR.blend(ArmCrossed, ArmUp, up)
                armL.hy += 0.06f * up * wave(t, 3f); armR.hy -= 0.06f * up * wave(t, 3f)
            }
            MascotMood.Error -> {
                // A shrug every few seconds: shoulders up, palms out, head tipped; then back to a slump.
                val shrug = min(1f, max(0f, wave(t, 1f / 3f) * 2.2f))
                sad = 1f
                eyeY = 0.03f
                sy = 1f - 0.04f * shrug
                tilt = 5f * shrug
                armL.blend(ArmCrossed, ArmShrug, shrug)
                armR.blend(ArmCrossed, ArmShrug, shrug)
                armL.hy -= 0.08f * shrug; armR.hy -= 0.08f * shrug
            }
            MascotMood.Walk -> {
                val step = wave(t, 1.5f)
                by = -0.06f * abs(step)
                bx = 0.05f * step
                tilt = 3f * step
                legL.lift = 0.22f * max(0f, step); legL.angle = 18f * step
                legR.lift = 0.22f * max(0f, -step); legR.angle = 18f * step
                mouthOpen = 0.15f
                armL.blend(ArmHang, ArmSwing, 0.5f + 0.5f * step)
                armR.blend(ArmHang, ArmSwing, 0.5f - 0.5f * step)
            }
        }
        sy = max(sy, 0.5f)
    }

    private fun idle(t: Float, ev: MascotEvents) {
        sy = 1f + 0.022f * wave(t, 0.5f)
        eyeX = 0.05f * wave(t, 1f / 6f)
        val progress = ev.idle.value
        val env = sin(PI_F * progress)
        when (ev.idleKind) {
            0 -> { // foot tap
                val tap = abs(sin(3f * PI_F * progress))
                legR.lift = 0.14f * tap
                legR.angle = -6f * tap
                tilt = 1.2f * tap
            }
            1 -> { // weight shift
                bx = 0.09f * env
                tilt = 3f * env
                legR.lift = 0.07f * env
                sy -= 0.015f * env
            }
            else -> { // hand fidget
                armR.blend(ArmCrossed, ArmFidget, min(1f, env * 3f))
                armR.hy += 0.05f * sin(6f * PI_F * progress) * env
            }
        }
    }
}

/**
 * A rigged vector mascot: body, eyes, mouth, arms and legs are each posed independently every frame from
 * one shared clock plus a few spring-driven events (hop, jump, landing squash, idle actions). Motion
 * principles only: anticipation, squash and stretch, overlapping follow-through. No image frames.
 */
@Composable
internal fun NilaMascot(
    mood: MascotMood,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // "Remove animations" in system settings: draw a still pose and run no loops.
    val still = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val motion = rememberInfiniteTransition(label = "nila-mascot")
    val clock by motion.animateFloat(
        0f, CLOCK_PERIOD_S,
        infiniteRepeatable(tween(CLOCK_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "clock",
    )

    // Blink is a discrete event, not a continuous wave: eyes stay open most of the time.
    var blink by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(mood, still) {
        if (still || mood == MascotMood.Sleeping) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(1800, 3400))
            blink = BLINK_CLOSED
            delay(BLINK_MS)
            blink = 1f
        }
    }

    val events = remember { MascotEvents() }
    LaunchedEffect(mood, still) {
        events.reset()
        if (still) {
            if (mood == MascotMood.Celebrate) events.armUp.snapTo(1f)
            return@LaunchedEffect
        }
        when (mood) {
            MascotMood.Idle -> while (true) {
                delay(Random.nextLong(IDLE_MIN_WAIT_MS, IDLE_MAX_WAIT_MS))
                events.idleKind = Random.nextInt(IDLE_ACTION_COUNT)
                events.idle.snapTo(0f)
                events.idle.animateTo(1f, tween(IdleActionMs[events.idleKind], easing = LinearEasing))
            }
            MascotMood.Greeting -> while (true) {
                events.hop.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
                events.hop.animateTo(0f, tween(150, easing = FastOutLinearInEasing))
                events.squash.snapTo(0.86f)
                events.squash.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
                delay(2400)
            }
            MascotMood.Celebrate -> while (true) {
                // Anticipation crouch, then jump with a stretch, hang, land with a squash and settle.
                events.squash.animateTo(0.84f, tween(130, easing = FastOutSlowInEasing))
                coroutineScope {
                    launch { events.armUp.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
                    launch { events.jump.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
                    launch {
                        events.squash.animateTo(1.14f, tween(120))
                        events.squash.animateTo(1f, tween(160))
                    }
                }
                delay(120)
                events.jump.animateTo(0f, tween(240, easing = FastOutLinearInEasing))
                events.squash.snapTo(0.8f)
                events.squash.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
                delay(900)
            }
            else -> Unit
        }
    }

    val rig = remember { Rig() }
    val scratch = remember { Path() }

    Canvas(modifier.size(size)) {
        // Reading the clock here (draw phase) redraws without recomposing.
        val t = if (still) 0f else clock
        rig.update(mood, t, events)

        val radius = this.size.width * BODY_RADIUS_OF_WIDTH
        val centerX = this.size.width * 0.5f
        val centerY = this.size.height * BODY_CENTER_Y_OF_HEIGHT
        val air = rig.air
        val sy = rig.sy
        val sx = 1f / sqrt(sy)
        val sleeping = mood == MascotMood.Sleeping

        withTransform({
            translate(centerX, centerY)
            scale(radius, radius, Offset.Zero)
        }) {
            // Ground shadow shrinks as NILA leaves the ground.
            val shadowW = 1.5f * (1f - 0.3f * air)
            drawOval(
                MascotInk.copy(alpha = 0.16f * (1f - 0.4f * air)),
                topLeft = Offset(-shadowW / 2f, 1.44f),
                size = Size(shadowW, 0.16f),
            )

            withTransform({ translate(0f, -0.5f * air) }) {
                if (rig.ring > 0f) {
                    // Attentive pulse: an expanding, fading ring behind the body while NILA is listening.
                    drawCircle(
                        MascotLime.copy(alpha = (1f - rig.ring) * 0.35f),
                        radius = 1.05f + rig.ring * 0.5f,
                        center = Offset.Zero,
                        style = RingStroke,
                    )
                }

                withTransform({
                    translate(rig.bx, rig.by)
                    rotate(rig.tilt, pivot = Offset(0f, FEET_PIVOT_Y))
                    scale(sx * rig.grow, sy * rig.grow, pivot = Offset(0f, SQUASH_PIVOT_Y))
                }) {
                    drawBody()
                    drawFace(rig, blink, sleeping, scratch)
                    drawArm(scratch, -1f, rig.armL)
                    drawArm(scratch, 1f, rig.armR)
                    if (sleeping) drawZs(rig.zPhase)
                }

                val legTop = LEG_TOP + rig.by * 0.5f
                drawLeg(-LEG_X + rig.bx * 0.3f, legTop, rig.legL)
                drawLeg(LEG_X + rig.bx * 0.3f, legTop, rig.legR)
            }
        }
    }
}

private fun DrawScope.drawBody() {
    // A ring of same-color circles fakes the scalloped blob silhouette.
    drawCircle(MascotYellow, radius = 1f, center = Offset.Zero)
    for (i in 0 until BUMP_COUNT) {
        drawCircle(MascotYellow, radius = BUMP_RADIUS, center = Offset(BumpX[i], BumpY[i]))
    }
}

private fun DrawScope.drawFace(rig: Rig, blink: Float, sleeping: Boolean, scratch: Path) {
    val eyeY = EYE_Y + rig.eyeY
    val leftX = LEFT_EYE_X + rig.eyeX
    val rightX = RIGHT_EYE_X + rig.eyeX
    if (sleeping) {
        // Closed sleepy curves.
        drawClosedEye(scratch, leftX, eyeY)
        drawClosedEye(scratch, rightX, eyeY)
    } else {
        drawCircle(MascotInk, radius = 0.11f * blink, center = Offset(leftX, eyeY))
        // The wink: a small chevron, NILA's signature.
        drawLine(MascotInk, Offset(rightX + 0.16f, eyeY - 0.10f), Offset(rightX, eyeY), WINK_WIDTH, StrokeCap.Round)
        drawLine(MascotInk, Offset(rightX, eyeY), Offset(rightX + 0.16f, eyeY + 0.10f), WINK_WIDTH, StrokeCap.Round)
    }

    // Mouth: a calm smile that opens when talking, droops on error, small and neutral asleep.
    val curve = ((0.18f * rig.smile + rig.mouthOpen * 0.30f) * (1f - rig.sad) - rig.sad * 0.14f) * (1f - rig.sleepy * 0.7f)
    val half = 0.22f * (1f - rig.sleepy * 0.4f)
    val mouthX = rig.eyeX * 0.5f
    scratch.rewind()
    scratch.moveTo(mouthX - half, MOUTH_Y)
    scratch.quadraticTo(mouthX, MOUTH_Y + curve, mouthX + half, MOUTH_Y)
    drawPath(scratch, MascotInk, style = MouthStroke)
}

private fun DrawScope.drawClosedEye(scratch: Path, x: Float, y: Float) {
    scratch.rewind()
    scratch.moveTo(x - 0.12f, y)
    scratch.quadraticTo(x, y + 0.08f, x + 0.12f, y)
    drawPath(scratch, MascotInk, style = EyeLidStroke)
}

private fun DrawScope.drawArm(scratch: Path, side: Float, arm: Arm) {
    val shoulderX = side * SHOULDER_X
    val handX = side * arm.hx
    val dx = handX - shoulderX
    val dy = arm.hy - SHOULDER_Y
    // Control point: the midpoint pushed sideways along the arm's normal, mirrored per side.
    val controlX = (shoulderX + handX) * 0.5f + dy * arm.bulge * side
    val controlY = (SHOULDER_Y + arm.hy) * 0.5f - dx * arm.bulge * side
    scratch.rewind()
    scratch.moveTo(shoulderX, SHOULDER_Y)
    scratch.quadraticTo(controlX, controlY, handX, arm.hy)
    drawPath(scratch, MascotInk, style = ArmStroke)
    val handRadius = 0.095f * (1f - arm.cross)
    if (handRadius > 0.01f) drawCircle(MascotInk, radius = handRadius, center = Offset(handX, arm.hy))
}

private fun DrawScope.drawLeg(x: Float, top: Float, leg: Leg) {
    withTransform({
        translate(x, top)
        rotate(leg.angle, pivot = Offset.Zero)
    }) {
        val topLeft = Offset(-LEG_WIDTH / 2f, 0f)
        val legSize = Size(LEG_WIDTH, LEG_HEIGHT - leg.lift)
        val corner = CornerRadius(LEG_WIDTH * 0.3f, LEG_WIDTH * 0.3f)
        drawRoundRect(MascotPaper, topLeft, legSize, corner)
        drawRoundRect(MascotInk, topLeft, legSize, corner, style = LegOutline)
    }
}

private fun DrawScope.drawZs(phase: Float) {
    // Three drifting "z" marks rising from the head, fading as they go.
    for (i in 0 until 3) {
        val p = (phase + i * 0.33f) % 1f
        val color = MascotInk.copy(alpha = (1f - p).coerceIn(0f, 1f) * 0.8f)
        val zy = -(0.9f + p * 0.8f)
        val zx = 0.55f + p * 0.25f
        val s = 0.16f
        drawLine(color, Offset(zx - s, zy - s), Offset(zx + s, zy - s), Z_WIDTH, StrokeCap.Round)
        drawLine(color, Offset(zx + s, zy - s), Offset(zx - s, zy + s), Z_WIDTH, StrokeCap.Round)
        drawLine(color, Offset(zx - s, zy + s), Offset(zx + s, zy + s), Z_WIDTH, StrokeCap.Round)
    }
}
