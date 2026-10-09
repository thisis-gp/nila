package com.nila.launcher

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal enum class MascotMood { Idle, Thinking, Talking, Celebrate, Listening, Error, Greeting, Sleeping }

private val MascotYellow = Color(0xFFFFC000)
private val MascotInk = Color(0xFF151513)

/**
 * A rigged vector mascot: body, eyes, mouth, arms and legs are each drawn and
 * animated independently (blink, talk-mouth, arm lift, body squash) rather than
 * one flat image being scaled or rotated as a whole.
 */
@Composable
internal fun NilaMascot(
    mood: MascotMood,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val motion = rememberInfiniteTransition(label = "nila-mascot")

    val bob by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val breathe by motion.animateFloat(
        0.97f, 1.03f,
        infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val thinkTilt by motion.animateFloat(
        -7f, 7f,
        infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "think-tilt",
    )
    val talkMouth by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(260, easing = LinearEasing), RepeatMode.Reverse),
        label = "talk-mouth",
    )
    val listenPulse by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "listen-pulse",
    )
    val errorShake by motion.animateFloat(
        -1f, 1f,
        infiniteRepeatable(tween(220, easing = LinearEasing), RepeatMode.Reverse),
        label = "error-shake",
    )
    val waveSwing by motion.animateFloat(
        -1f, 1f,
        infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wave-swing",
    )
    val sleepDrift by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "sleep-drift",
    )

    // Blink is a discrete event, not a continuous wave: eyes stay open most of the time.
    var blink by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(mood) {
        if (mood != MascotMood.Sleeping) {
            while (true) {
                delay(Random.nextLong(1800, 3400))
                blink = 0.12f
                delay(110)
                blink = 1f
            }
        }
    }

    val celebrateLift = remember { Animatable(0f) }
    val celebrateSquash = remember { Animatable(1f) }
    LaunchedEffect(mood) {
        if (mood == MascotMood.Celebrate) {
            celebrateLift.snapTo(0f)
            celebrateSquash.snapTo(1f)
            celebrateLift.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
            celebrateSquash.animateTo(1.18f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessHigh))
            celebrateSquash.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
            delay(500)
            celebrateLift.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
        }
    }

    val tilt = when (mood) {
        MascotMood.Thinking -> thinkTilt
        MascotMood.Error -> errorShake * 3f // restrained: a small sad shake, not a big flail
        MascotMood.Sleeping -> 6f // head tipped over, resting
        else -> 0f
    }
    val mouthOpen = when (mood) {
        MascotMood.Talking -> talkMouth
        MascotMood.Celebrate -> celebrateLift.value
        MascotMood.Greeting -> 0.4f
        else -> 0f
    }
    val sad = if (mood == MascotMood.Error) 1f else 0f
    val sleepy = if (mood == MascotMood.Sleeping) 1f else 0f
    val armLift = if (mood == MascotMood.Celebrate) celebrateLift.value else 0f
    val squash = if (mood == MascotMood.Celebrate) celebrateSquash.value else if (mood == MascotMood.Sleeping) 1f else breathe
    val bobPx = if (mood == MascotMood.Idle || mood == MascotMood.Talking) bob else 0.5f
    val listenRing = if (mood == MascotMood.Listening) listenPulse else 0f

    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w * 0.5f
        val cy = h * 0.36f + (bobPx - 0.5f) * h * 0.03f
        val bodyRadius = w * 0.30f * squash

        if (listenRing > 0f) {
            // Attentive pulse: an expanding, fading ring behind the body while NILA is listening.
            drawCircle(
                Color(0xFFD9DF36).copy(alpha = (1f - listenRing) * 0.35f),
                radius = bodyRadius * (1.05f + listenRing * 0.5f),
                center = Offset(cx, cy),
                style = Stroke(width = bodyRadius * 0.05f),
            )
        }

        rotate(tilt, pivot = Offset(cx, cy)) {
            // Body: a ring of same-color circles fakes the scalloped blob silhouette.
            drawCircle(MascotYellow, radius = bodyRadius, center = Offset(cx, cy))
            val bumps = 8
            repeat(bumps) { i ->
                val angle = (360f / bumps) * i
                val bumpCenter = Offset(
                    cx + cos(Math.toRadians(angle.toDouble())).toFloat() * bodyRadius * 0.74f,
                    cy + sin(Math.toRadians(angle.toDouble())).toFloat() * bodyRadius * 0.74f,
                )
                drawCircle(MascotYellow, radius = bodyRadius * 0.46f, center = bumpCenter)
            }

            // Eyes: closed sleepy curves while sleeping, otherwise the usual dot + wink.
            val eyeY = cy - bodyRadius * 0.08f
            if (mood == MascotMood.Sleeping) {
                listOf(cx - bodyRadius * 0.34f, cx + bodyRadius * 0.26f).forEach { ex ->
                    val closedEye = Path().apply {
                        moveTo(ex - bodyRadius * 0.12f, eyeY)
                        quadraticBezierTo(ex, eyeY + bodyRadius * 0.08f, ex + bodyRadius * 0.12f, eyeY)
                    }
                    drawPath(closedEye, MascotInk, style = Stroke(width = bodyRadius * 0.06f, cap = StrokeCap.Round))
                }
            } else {
                val eyeDotR = bodyRadius * 0.11f * blink
                drawCircle(MascotInk, radius = eyeDotR.coerceAtLeast(1f), center = Offset(cx - bodyRadius * 0.34f, eyeY))
                val winkPath = Path().apply {
                    val wx = cx + bodyRadius * 0.26f
                    moveTo(wx + bodyRadius * 0.16f, eyeY - bodyRadius * 0.10f)
                    lineTo(wx, eyeY)
                    lineTo(wx + bodyRadius * 0.16f, eyeY + bodyRadius * 0.10f)
                }
                drawPath(winkPath, MascotInk, style = Stroke(width = bodyRadius * 0.08f, cap = StrokeCap.Round))
            }

            // Mouth: a calm smile that opens when talking/celebrating, droops sad on error,
            // goes small and neutral when sleeping.
            val mouthY = cy + bodyRadius * 0.28f
            val curve = (bodyRadius * (0.18f + mouthOpen * 0.30f) * (1f - sad) - sad * bodyRadius * 0.14f) * (1f - sleepy * 0.7f)
            val mouthPath = Path().apply {
                moveTo(cx - bodyRadius * 0.22f * (1f - sleepy * 0.4f), mouthY)
                quadraticBezierTo(cx, mouthY + curve, cx + bodyRadius * 0.22f * (1f - sleepy * 0.4f), mouthY)
            }
            drawPath(mouthPath, MascotInk, style = Stroke(width = bodyRadius * 0.07f, cap = StrokeCap.Round))

            // Arms: crossed by default, lifted for celebrate, hanging relaxed when asleep,
            // one arm raised and waving back and forth for a greeting.
            val armY = cy + bodyRadius * 0.62f
            val raise = armLift * bodyRadius * 0.55f
            val armStroke = Stroke(width = bodyRadius * 0.11f, cap = StrokeCap.Round)

            if (mood == MascotMood.Sleeping) {
                listOf(-1f, 1f).forEach { side ->
                    val hang = Path().apply {
                        moveTo(cx + side * bodyRadius * 0.78f, armY - bodyRadius * 0.1f)
                        quadraticBezierTo(
                            cx + side * bodyRadius * 0.72f, armY + bodyRadius * 0.20f,
                            cx + side * bodyRadius * 0.60f, armY + bodyRadius * 0.38f,
                        )
                    }
                    drawPath(hang, MascotInk, style = armStroke)
                }
            } else {
                val armLeft = Path().apply {
                    moveTo(cx - bodyRadius * 0.78f, armY - raise * 0.4f)
                    cubicTo(
                        cx - bodyRadius * 0.20f, armY + bodyRadius * 0.22f - raise,
                        cx + bodyRadius * 0.10f, armY - bodyRadius * 0.05f - raise,
                        cx + bodyRadius * 0.46f, armY - bodyRadius * 0.18f - raise,
                    )
                }
                drawPath(armLeft, MascotInk, style = armStroke)

                if (mood == MascotMood.Greeting) {
                    // A raised arm swinging side to side, like a hand waving hello.
                    val shoulder = Offset(cx + bodyRadius * 0.55f, armY - bodyRadius * 0.20f)
                    val swingAngle = Math.toRadians((waveSwing * 22f).toDouble())
                    val handLen = bodyRadius * 0.95f
                    val hand = Offset(
                        shoulder.x + sin(swingAngle).toFloat() * handLen * 0.6f,
                        shoulder.y - cos(swingAngle).toFloat() * handLen,
                    )
                    val wave = Path().apply {
                        moveTo(shoulder.x, shoulder.y)
                        quadraticBezierTo(shoulder.x + bodyRadius * 0.25f, shoulder.y - handLen * 0.5f, hand.x, hand.y)
                    }
                    drawPath(wave, MascotInk, style = armStroke)
                } else {
                    val armRight = Path().apply {
                        moveTo(cx + bodyRadius * 0.78f, armY - raise * 0.4f)
                        cubicTo(
                            cx + bodyRadius * 0.20f, armY + bodyRadius * 0.22f - raise,
                            cx - bodyRadius * 0.10f, armY - bodyRadius * 0.05f - raise,
                            cx - bodyRadius * 0.46f, armY - bodyRadius * 0.18f - raise,
                        )
                    }
                    drawPath(armRight, MascotInk, style = armStroke)
                }
            }


            // Legs.
            val legTop = cy + bodyRadius * 0.86f
            val legHeight = bodyRadius * 0.62f
            val legWidth = bodyRadius * 0.26f
            listOf(cx - bodyRadius * 0.30f, cx + bodyRadius * 0.04f).forEach { legX ->
                drawRoundRect(
                    color = Color(0xFFF2F0E9),
                    topLeft = Offset(legX, legTop),
                    size = androidx.compose.ui.geometry.Size(legWidth, legHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(legWidth * 0.3f, legWidth * 0.3f),
                )
                drawRoundRect(
                    color = MascotInk,
                    topLeft = Offset(legX, legTop),
                    size = androidx.compose.ui.geometry.Size(legWidth, legHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(legWidth * 0.3f, legWidth * 0.3f),
                    style = Stroke(width = bodyRadius * 0.045f),
                )
            }

            if (mood == MascotMood.Sleeping) {
                // Three drifting "z" marks rising from her head, fading as they go.
                repeat(3) { i ->
                    val phase = (sleepDrift + i * 0.33f) % 1f
                    val zAlpha = (1f - phase).coerceIn(0f, 1f) * 0.8f
                    val zy = cy - bodyRadius * (0.9f + phase * 0.8f)
                    val zx = cx + bodyRadius * (0.55f + phase * 0.25f)
                    val zSize = bodyRadius * 0.16f
                    val zPath = Path().apply {
                        moveTo(zx - zSize, zy - zSize)
                        lineTo(zx + zSize, zy - zSize)
                        lineTo(zx - zSize, zy + zSize)
                        lineTo(zx + zSize, zy + zSize)
                    }
                    drawPath(zPath, MascotInk.copy(alpha = zAlpha), style = Stroke(width = bodyRadius * 0.05f, cap = StrokeCap.Round))
                }
            }
        }
    }
}

