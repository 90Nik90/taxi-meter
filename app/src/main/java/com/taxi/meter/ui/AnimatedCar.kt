package com.taxi.meter.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.cos
import kotlin.math.sin

/**
 * Силуэт седана в системе координат 64×32. Колёса рисуются отдельно,
 * чтобы их можно было крутить.
 */
private const val CAR_BODY =
    // Кузов симметричен относительно x = 32: капот и багажник зеркальны
    "M2.6,21.5 L2.6,17.8 C2.6,16.4 3.4,15.4 4.8,15.0 L17.0,11.6 " +
        "C19.4,8.6 22.6,7.0 26.4,7.0 L37.6,7.0 C41.4,7.0 44.6,8.6 47.0,11.6 " +
        "L59.2,15.0 C60.6,15.4 61.4,16.4 61.4,17.8 L61.4,21.5 " +
        "L54.5,21.5 A6.5,6.5 0 0,0 41.5,21.5 L22.5,21.5 A6.5,6.5 0 0,0 9.5,21.5 Z " +
        // Стёкла
        "M31.4,8.9 L26.0,8.9 L22.6,13.8 L31.4,13.8 Z " +
        "M32.6,8.9 L38.0,8.9 L41.4,13.8 L32.6,13.8 Z " +
        // Зазор двери и ручки
        "M31.6,15.0 L32.4,15.0 L32.4,20.6 L31.6,20.6 Z " +
        "M26.6,16.0 L29.8,16.0 L29.8,17.1 L26.6,17.1 Z " +
        "M34.2,16.0 L37.4,16.0 L37.4,17.1 L34.2,17.1 Z " +
        // Фонари
        "M4.6,18.4 L7.6,18.4 L7.6,19.9 L4.6,19.9 Z " +
        "M56.4,18.4 L59.4,18.4 L59.4,19.9 L56.4,19.9 Z " +
        // Шашечки на крыше: корпус плафона и вырезанные клетки
        "M28.4,3.0 L35.6,3.0 A0.8,0.8 0 0,1 36.4,3.8 L36.4,7.0 " +
        "L27.6,7.0 L27.6,3.8 A0.8,0.8 0 0,1 28.4,3.0 Z " +
        "M30.15,3.7 L32.0,3.7 L32.0,5.1 L30.15,5.1 Z " +
        "M33.85,3.7 L35.7,3.7 L35.7,5.1 L33.85,5.1 Z " +
        "M28.3,5.1 L30.15,5.1 L30.15,6.5 L28.3,6.5 Z " +
        "M32.0,5.1 L33.85,5.1 L33.85,6.5 L32.0,6.5 Z"

private const val VIEW_W = 64f
private const val REAR_WHEEL_X = 16f
private const val FRONT_WHEEL_X = 48f
private const val WHEEL_Y = 21.5f
private const val ROAD_Y = 29.6f

/** Длина штриха дороги плюс промежуток — период анимации фазы. */
private const val ROAD_DASH = 6f
private const val ROAD_GAP = 5f

/**
 * Машинка с холостой анимацией: кузов покачивается на подвеске,
 * колёса крутятся, дорога под ними бежит назад.
 *
 * При [moving] = false всё замирает — поездка на паузе или ещё не начата.
 */
@Composable
fun AnimatedCar(moving: Boolean, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    val body = remember {
        PathParser().parsePathString(CAR_BODY).toPath().apply {
            fillType = PathFillType.EvenOdd
        }
    }

    val transition = rememberInfiniteTransition(label = "car")
    // Колёса и дорога крутятся в сторону движения — машина едет влево
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "spin",
    )
    val bob by transition.animateFloat(
        initialValue = -0.8f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "bob",
    )
    val road by transition.animateFloat(
        initialValue = 0f,
        targetValue = -(ROAD_DASH + ROAD_GAP),
        animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing)),
        label = "road",
    )

    val angle = if (moving) spin else 0f
    val lift = if (moving) bob else 0f
    val phase = if (moving) road else 0f

    Canvas(modifier) {
        val k = size.width / VIEW_W
        withTransform({
            scale(k, k, Offset.Zero)
            // Зеркалим всю картинку: машина едет влево, дорога и колёса
            // разворачиваются вместе с ней
            scale(-1f, 1f, Offset(VIEW_W / 2f, 0f))
        }) {
            drawLine(
                color = color.copy(alpha = 0.5f),
                start = Offset(1f, ROAD_Y),
                end = Offset(VIEW_W - 1f, ROAD_Y),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(ROAD_DASH, ROAD_GAP), phase),
            )

            translate(top = lift) { drawPath(body, color) }

            // Колёса приподнимаются слабее кузова — так видно ход подвески
            val wheelY = WHEEL_Y + lift * 0.3f
            drawWheel(color, Offset(REAR_WHEEL_X, wheelY), angle)
            drawWheel(color, Offset(FRONT_WHEEL_X, wheelY), angle)
        }
    }
}

/** Шина кольцом, пять спиц и ступица — вращение видно по спицам. */
private fun DrawScope.drawWheel(color: Color, center: Offset, angleDeg: Float) {
    drawCircle(color, radius = 4.2f, center = center, style = Stroke(width = 2.6f))

    val toRad = (Math.PI / 180f).toFloat()
    repeat(5) { index ->
        val a = (angleDeg + index * 72f) * toRad
        val dx = cos(a)
        val dy = sin(a)
        drawLine(
            color = color,
            start = Offset(center.x + dx * 1.0f, center.y + dy * 1.0f),
            end = Offset(center.x + dx * 2.7f, center.y + dy * 2.7f),
            strokeWidth = 0.9f,
            cap = StrokeCap.Round,
        )
    }

    drawCircle(color, radius = 1.05f, center = center)
}
