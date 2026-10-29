package dev.tsdroid.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 磨砂材质（机制，参考上游终版方案）：
 *
 * 壁纸按实例一次性烘焙成半尺寸模糊副本（两次箱式盒模糊 + 均值亮度），所有
 * 面板共享这一张；面板在绘制阶段把副本按「壁纸盒尺寸」摆放、以自身在壁纸
 * 空间中的偏移反向裁切画进来——绘制期只剩一次 drawImage，不逐帧做 GPU 模
 * 糊，且在低版本系统上同样有模糊效果。
 *
 * 坐标假设显式化：壁纸铺在哪层盒、多大，由铺壁纸的屏幕通过
 * [LocalWallpaperGeometry] 显式告知；面板偏移 = 面板 positionInRoot −
 * 壁纸盒 positionInRoot，两者同换算空间，不依赖「壁纸 == 组合根」的巧合。
 * 未提供几何时退回旧的组合根假设（向后兼容）。
 */

/** 壁纸盒在组合根中的位置与尺寸；面板据此换算自己在壁纸空间中的偏移。 */
@Stable
class WallpaperGeometry(
    val originInRoot: Offset,
    val size: IntSize,
)

/**
 * 烘焙后的壁纸：模糊副本（壁纸盒的 [FROST_SCALE] 分之一尺寸）+ 平均亮度 0..1。
 */
@Stable
internal class FrostedWallpaper(
    val image: ImageBitmap,
    val meanLuma: Float,
)

private const val FROST_RADIUS_DP = 18f
private const val FROST_SCALE = 2
private const val FROST_PASSES = 2

private object FrostedWallpaperCache {
    private val bakeLock = Mutex()
    private var source: ImageBitmap? = null
    private var boxSize = IntSize.Zero
    private var value: FrostedWallpaper? = null
    private var known = false

    @Synchronized
    fun peek(source: ImageBitmap, boxSize: IntSize): FrostedWallpaper? =
        if (known && this.source === source && this.boxSize == boxSize) value else null

    suspend fun of(source: ImageBitmap, boxSize: IntSize, radiusPx: Float): FrostedWallpaper? =
        bakeLock.withLock {
            if (known && this.source === source && this.boxSize == boxSize) {
                value
            } else {
                val baked = withContext(Dispatchers.Default) {
                    bakeFrosted(source, boxSize, radiusPx)
                }
                synchronized(this) {
                    this.source = source
                    this.boxSize = boxSize
                    value = baked
                    known = true
                }
                baked
            }
        }
}

/** 壁纸变更/壁纸盒尺寸变更后构建共享模糊副本。null = 无壁纸可烘焙。 */
@Composable
internal fun rememberFrostedWallpaper(source: ImageBitmap?, boxSize: IntSize): FrostedWallpaper? {
    val density = LocalDensity.current
    val radiusPx = with(density) { FROST_RADIUS_DP.dp.toPx() }
    var frosted by remember(source, boxSize, radiusPx) {
        mutableStateOf(source?.let { FrostedWallpaperCache.peek(it, boxSize) })
    }
    LaunchedEffect(source, boxSize, radiusPx) {
        val wallpaper = source ?: return@LaunchedEffect
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        frosted = FrostedWallpaperCache.of(wallpaper, boxSize, radiusPx)
    }
    return frosted
}

private fun bakeFrosted(source: ImageBitmap, boxSize: IntSize, radiusPx: Float): FrostedWallpaper? =
    try {
        val boxWidth = boxSize.width
        val boxHeight = boxSize.height
        if (boxWidth <= 0 || boxHeight <= 0) {
            null
        } else {
            val bitmap = source.asAndroidBitmap()
            val width = max(1, boxWidth / FROST_SCALE)
            val height = max(1, boxHeight / FROST_SCALE)
            val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(cropped)
            val paint = Paint().apply { flags = Paint.FILTER_BITMAP_FLAG }

            // 与背景一致的 ContentScale.Crop 布局，只是更小：居中、短轴溢出
            val scale = max(
                cropped.width / bitmap.width.toFloat(),
                cropped.height / bitmap.height.toFloat(),
            )
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale
            val left = (cropped.width - scaledWidth) / 2f
            val top = (cropped.height - scaledHeight) / 2f
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(left, top, left + scaledWidth, top + scaledHeight),
                paint,
            )

            val pixels = IntArray(width * height)
            cropped.getPixels(pixels, 0, width, 0, 0, width, height)
            cropped.recycle()

            // 副本以 FROST_SCALE 倍画回壁纸盒，模糊强度按比例缩小保持 18dp 观感
            val radius = max(1, (radiusPx / FROST_SCALE).roundToInt())
            val scratch = IntArray(pixels.size)
            repeat(FROST_PASSES) { boxBlur(pixels, width, height, radius, scratch) }

            var lumaSum = 0.0
            for (pixel in pixels) {
                lumaSum += (0.2126 * ((pixel shr 16) and 0xFF) +
                    0.7152 * ((pixel shr 8) and 0xFF) +
                    0.0722 * (pixel and 0xFF)) / 255.0
            }

            FrostedWallpaper(
                image = Bitmap
                    .createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                    .asImageBitmap(),
                meanLuma = (lumaSum / pixels.size).toFloat(),
            )
        }
    } catch (_: Throwable) {
        null
    }

/** 可分离盒模糊，边缘 clamp 不淡出；跑两遍足够呈现磨砂质感。 */
private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, scratch: IntArray) {
    if (radius <= 0 || width <= 0 || height <= 0) return
    val window = radius * 2 + 1

    for (y in 0 until height) {
        val row = y * width
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val pixel = pixels[row + i.coerceIn(0, width - 1)]
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
        }
        for (x in 0 until width) {
            scratch[row + x] = (0xFF shl 24) or
                ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val leaving = pixels[row + (x - radius).coerceIn(0, width - 1)]
            val entering = pixels[row + (x + radius + 1).coerceIn(0, width - 1)]
            r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            b += (entering and 0xFF) - (leaving and 0xFF)
        }
    }

    for (x in 0 until width) {
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val pixel = scratch[i.coerceIn(0, height - 1) * width + x]
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
        }
        for (y in 0 until height) {
            pixels[y * width + x] = (0xFF shl 24) or
                ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val leaving = scratch[(y - radius).coerceIn(0, height - 1) * width + x]
            val entering = scratch[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            b += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

/** 未提供 [LocalWallpaperGeometry] 时的回退：走 parentLayout 链到组合根。 */
private fun LayoutCoordinates.rootSizeOf(): IntSize {
    var current: LayoutCoordinates = this
    while (true) {
        current = current.parentLayoutCoordinates ?: return current.size
    }
}

/**
 * 面板磨砂底（modifier 形态）：把烘焙模糊壁纸按壁纸盒尺寸反向偏移地画进
 * 面板自身区域，叠加乳白可读性罩层；[decorations] 时附加内阴影与顶部高光
 * （玻璃面板的完整材质），无壁纸时画 [fallbackColor]（面板既有底色）。
 *
 * [paneColor] 指定时走「半透明奶白底板」路径：不采样壁纸，只画一层
 * 半透明底色透出身后的内容（内容之下自然透出壁纸），玻璃细节照旧——
 * 悬浮 bar 用它：bar 身后的东西应该先看到列表条目、再透出壁纸。
 *
 * 几何优先取 AnimeWallpaperState.activeGeometry；面板偏移 = positionInRoot
 * − 壁纸盒原点。
 */
fun Modifier.frostedBackdrop(
    wallpaper: ImageBitmap?,
    shape: Shape,
    fallbackColor: Color = Color.Unspecified,
    decorations: Boolean = false,
    paneColor: Color? = null,
): Modifier = composed {
    val dark = isSystemInDarkTheme()
    // 奶白底板路径不采样壁纸：跳过烘焙
    val sampledSource = if (paneColor != null) null else wallpaper
    val geo = AnimeWallpaperState.activeGeometry

    // 面板在壁纸空间中的偏移：只在绘制阶段读取，滚动每帧变化只重绘不重组
    var paneLeft by remember { mutableIntStateOf(0) }
    var paneTop by remember { mutableIntStateOf(0) }
    var boxSize by remember { mutableStateOf(geo?.size ?: IntSize.Zero) }
    val frosted = rememberFrostedWallpaper(sampledSource, boxSize)

    val veil = if (dark) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.46f)
    val topHighlight = if (dark) 0.24f else 0.12f
    val insetShadow = if (dark) 0.14f else 0.07f
    // 与 AnimeBackground 的合成对齐：壁纸层 alpha 0.75 + 白色渐变罩
    val backdropAlpha = 0.75f

    val lineBase = this.onGloballyPositioned { coordinates ->
        val position = coordinates.positionInRoot()
        val origin = geo?.originInRoot ?: Offset.Zero
        paneLeft = (position.x - origin.x).toInt()
        paneTop = (position.y - origin.y).toInt()
        geo?.let { boxSize = it.size } ?: run { boxSize = coordinates.rootSizeOf() }
    }.drawWithContent {
        val image = frosted?.image
        val box = boxSize
        when {
            paneColor != null -> {
                // 半透明奶白底板：身后的内容（列表条目）直接透出，内容之下
                // 是壁纸——分层语义「先列表、下层壁纸」，不采样壁纸本身
                drawRect(color = paneColor)
                if (decorations) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.62f to Color.Transparent,
                            1f to Color.Black.copy(alpha = insetShadow),
                        ),
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.White.copy(alpha = topHighlight),
                            0.16f to Color.Transparent,
                        ),
                    )
                }
            }
            image != null && box.width > 0 && box.height > 0 -> {
                clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                    translate(left = -paneLeft.toFloat(), top = -paneTop.toFloat()) {
                        drawImage(
                            image = image,
                            dstSize = box,
                            filterQuality = FilterQuality.Low,
                            alpha = backdropAlpha,
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.08f),
                                0.5f to Color.White.copy(alpha = 0.03f),
                                1f to Color.White.copy(alpha = 0.08f),
                            ),
                            topLeft = Offset.Zero,
                            size = Size(box.width.toFloat(), box.height.toFloat()),
                            alpha = backdropAlpha,
                        )
                    }
                }
                drawRect(color = veil)
                if (decorations) {
                    // 内阴影：底缘渐暗（参考 0 -10px 25px inset rgba(0,0,0,.15)）
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.62f to Color.Transparent,
                            1f to Color.Black.copy(alpha = insetShadow),
                        ),
                    )
                    // 顶部高光带
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.White.copy(alpha = topHighlight),
                            0.16f to Color.Transparent,
                        ),
                    )
                }
            }
            else -> {
                // 壁纸关闭或副本未就绪：画回退色（即面板既有的底色）
                if (fallbackColor != Color.Unspecified) drawRect(fallbackColor)
            }
        }
        drawContent()
    }

    // 发丝描边只在磨砂/奶白底板生效时加上；回退态维持面板原样。
    // border 在 frostedBackdrop 之后：绘制时位于磨砂层之上、子内容之下
    if (wallpaper != null || paneColor != null) {
        lineBase.border(0.75.dp, Color.Black.copy(alpha = 0.10f), shape)
    } else {
        lineBase
    }
}

/**
 * 玻璃面板（完整材质）：投影抬升浮出内容 + 剪裁 + 磨砂底 + 内阴影/高光 +
 * 发丝描边。缺省从壁纸采样磨砂；[paneColor] 指定时改为「半透明奶白底板」
 * ——不采样壁纸，身后的内容先透过来、内容之下才是壁纸（悬浮 bar 语义）。
 * 无壁纸也无奶白时回退为 [backgroundColor] 的实底面板，仍读作一个表面。
 *
 * 内容色不做改写：子项按各自既有配色绘制（如需自适应亮度由调用方处理）。
 */
@Composable
fun GlassSurface(
    wallpaper: ImageBitmap?,
    shape: Shape,
    modifier: Modifier = Modifier,
    elevation: Dp = 10.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    paneColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val hairline = if (dark) {
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.30f),
            0.55f to Color.White.copy(alpha = 0.06f),
            1f to Color.White.copy(alpha = 0.16f),
        )
    } else {
        Brush.linearGradient(
            0f to Color.Black.copy(alpha = 0.12f),
            0.55f to Color.Black.copy(alpha = 0.04f),
            1f to Color.Black.copy(alpha = 0.08f),
        )
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.22f),
                spotColor = Color.Black.copy(alpha = 0.25f),
            )
            .clip(shape)
            .frostedBackdrop(
                wallpaper = wallpaper,
                shape = shape,
                fallbackColor = backgroundColor,
                decorations = true,
                paneColor = paneColor,
            )
            .border(0.75.dp, hairline, shape),
    ) {
        content()
    }
}
