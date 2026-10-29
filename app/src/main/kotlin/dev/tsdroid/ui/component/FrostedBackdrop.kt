package dev.tsdroid.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
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
 * 面板共享这一张；面板在绘制阶段把副本按「root 尺寸」摆放、以自身几何反向
 * 偏移地裁切画进来——绘制期只剩一次 drawImage，不逐帧做 GPU 模糊，且在
 * 低版本系统上同样有模糊效果。偏移取 positionInRoot、几何取面板绘制空间：
 * 两者相差一个状态栏/导航栏的高度，混用会让面板底部漏出一条裸壁纸。
 */

/**
 * 烘焙后的壁纸：模糊副本（root 的 [FROST_SCALE] 分之一尺寸）+ 平均亮度 0..1。
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
    private var rootSize = IntSize.Zero
    private var value: FrostedWallpaper? = null
    private var known = false

    @Synchronized
    fun peek(source: ImageBitmap, rootSize: IntSize): FrostedWallpaper? =
        if (known && this.source === source && this.rootSize == rootSize) value else null

    suspend fun of(source: ImageBitmap, rootSize: IntSize, radiusPx: Float): FrostedWallpaper? =
        bakeLock.withLock {
            if (known && this.source === source && this.rootSize == rootSize) {
                value
            } else {
                val baked = withContext(Dispatchers.Default) {
                    bakeFrosted(source, rootSize, radiusPx)
                }
                synchronized(this) {
                    this.source = source
                    this.rootSize = rootSize
                    value = baked
                    known = true
                }
                baked
            }
        }
}

/** 壁纸变更/根尺寸变更后构建共享模糊副本。null = 无壁纸可烘焙（面板画回退色）。 */
@Composable
internal fun rememberFrostedWallpaper(source: ImageBitmap?, rootSize: IntSize): FrostedWallpaper? {
    val density = LocalDensity.current
    val radiusPx = with(density) { FROST_RADIUS_DP.dp.toPx() }
    var frosted by remember(source, rootSize, radiusPx) {
        mutableStateOf(source?.let { FrostedWallpaperCache.peek(it, rootSize) })
    }
    LaunchedEffect(source, rootSize, radiusPx) {
        val wallpaper = source ?: return@LaunchedEffect
        if (rootSize.width <= 0 || rootSize.height <= 0) return@LaunchedEffect
        frosted = FrostedWallpaperCache.of(wallpaper, rootSize, radiusPx)
    }
    return frosted
}

private fun bakeFrosted(source: ImageBitmap, rootSize: IntSize, radiusPx: Float): FrostedWallpaper? =
    try {
        val rootWidth = rootSize.width
        val rootHeight = rootSize.height
        if (rootWidth <= 0 || rootHeight <= 0) {
            null
        } else {
            val bitmap = source.asAndroidBitmap()
            val width = max(1, rootWidth / FROST_SCALE)
            val height = max(1, rootHeight / FROST_SCALE)
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

            // 副本以 FROST_SCALE 倍画回 root，模糊强度按比例缩小保持 18dp 观感
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

/** 走 parentLayout 链到组合根：全屏壁纸铺的就是这个空间。 */
private fun LayoutCoordinates.rootSizeOf(): IntSize {
    var current: LayoutCoordinates = this
    while (true) {
        current = current.parentLayoutCoordinates ?: return current.size
    }
}

/**
 * 面板磨砂底（modifier 形态）：把烘焙模糊壁纸按 root 尺寸反向偏移地画进
 * 面板自身区域，叠加乳白可读性罩层与发丝描边；内容色不做改写（试点阶段
 * 保持子项既有配色）。无壁纸或副本未就绪时画 [fallbackColor]——即面板
 * 原本的半透明底色，磨砂烘焙完成后再替换，视觉上无空窗。
 *
 * 假设壁纸与面板同一组合根全屏铺开（当前服务器页满足），否则偏移不一致。
 */
fun Modifier.frostedBackdrop(
    wallpaper: ImageBitmap?,
    shape: Shape,
    fallbackColor: Color = Color.Unspecified,
): Modifier = composed {
    val dark = isSystemInDarkTheme()

    var paneLeft by remember { mutableIntStateOf(0) }
    var paneTop by remember { mutableIntStateOf(0) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val frosted = rememberFrostedWallpaper(wallpaper, rootSize)

    val veil = if (dark) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.46f)
    // 与 AnimeBackground 的合成对齐：壁纸层 alpha 0.75 + 白色渐变罩
    val backdropAlpha = 0.75f

    val lineBase = this.onGloballyPositioned { coordinates ->
        val position = coordinates.positionInRoot()
        paneLeft = position.x.toInt()
        paneTop = position.y.toInt()
        rootSize = coordinates.rootSizeOf()
    }.drawWithContent {
        val image = frosted?.image
        val root = rootSize
        when {
            image != null && root.width > 0 && root.height > 0 -> {
                clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                    translate(left = -paneLeft.toFloat(), top = -paneTop.toFloat()) {
                        drawImage(
                            image = image,
                            dstSize = root,
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
                            size = Size(root.width.toFloat(), root.height.toFloat()),
                            alpha = backdropAlpha,
                        )
                    }
                }
                drawRect(color = veil)
            }
            else -> {
                // 壁纸关闭或副本未就绪：画回退色（即面板既有的半透明底色）
                if (fallbackColor != Color.Unspecified) drawRect(fallbackColor)
            }
        }
        drawContent()
    }

    // 发丝描边只在磨砂真正生效（壁纸可用）时加上；回退态维持面板原样
    if (wallpaper != null) {
        lineBase.border(0.75.dp, Color.Black.copy(alpha = 0.10f), shape)
    } else {
        lineBase
    }
}
