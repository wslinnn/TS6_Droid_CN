package dev.tsdroid.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底部悬浮胶囊 Tab Bar（iOS 风格，参考上游终版实现）：
 *
 * 覆盖在内容之上、不占布局高度——内容由调用方负责补足底边距让最后一行
 * 可达。选中态白胶囊用弹簧动画在条目之间滑动；材质走 [GlassSurface]
 * （投影抬升 + 磨砂底 + 内阴影/高光/发丝），壁纸关闭时回退为实底胶囊。
 *
 * 「胶囊」永远锚定容器底部居中，「trailing」（如独立的加号圆钮）单独锚定
 * 容器右下角——两者互为兄弟层，trailing 的出现/消失不会移动胶囊。
 */
data class FloatingTabItem(
    val icon: ImageVector,
    val label: String,
)

@Composable
fun FloatingTabBar(
    items: List<FloatingTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wallpaper: ImageBitmap? = null,
    barHeight: Dp = 64.dp,
    itemWidth: Dp = 92.dp,
    horizontalMargin: Dp = 16.dp,
    gap: Dp = 14.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val dark = isSystemInDarkTheme()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = horizontalMargin, end = horizontalMargin, bottom = 12.dp),
    ) {
        // 拥抱条目宽度，但不超过可用宽度：条目增多时槽位收缩不被裁剪
        val capsuleWidth = minOf(itemWidth * items.size, maxWidth)
        val slotWidth = capsuleWidth / items.size
        val pillInset = 6.dp
        val pillOffset by animateDpAsState(
            targetValue = slotWidth * selectedIndex + pillInset,
            animationSpec = spring(
                dampingRatio = 0.72f,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "floatingTabPill",
        )
        val contentColor = MaterialTheme.colorScheme.onSurface

        // bar 底板材：半透明奶白——身后的内容（列表条目）直接透出，内容
        // 之下才是壁纸；明确不采样壁纸（bar 不是壁纸的一部分）
        val paneColor = if (dark) {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
        } else {
            Color.White.copy(alpha = 0.55f)
        }

        // 胶囊：定死底部居中——trailing 的有无不改变它的位置
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(capsuleWidth)
                .height(barHeight),
        ) {
            GlassSurface(
                wallpaper = wallpaper,
                shape = CircleShape,
                elevation = 10.dp,
                paneColor = paneColor,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 选中态白胶囊：底板之上、条目之下，同材质的一块更亮玻璃
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = pillOffset)
                        .width(slotWidth - pillInset * 2)
                        .height(barHeight - 12.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (dark) 0.22f else 0.34f))
                        .border(
                            width = 0.75.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        ),
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    items.forEachIndexed { index, item ->
                        val selected = index == selectedIndex
                        val tint by animateColorAsState(
                            targetValue = if (selected) contentColor
                            else contentColor.copy(alpha = 0.72f),
                            animationSpec = tween(durationMillis = 200),
                            label = "floatingTabTint$index",
                        )
                        val iconScale by animateFloatAsState(
                            targetValue = if (selected) 1.08f else 1f,
                            animationSpec = spring(
                                dampingRatio = 0.6f,
                                stiffness = Spring.StiffnessMedium,
                            ),
                            label = "floatingTabScale$index",
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = { onSelect(index) },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = tint,
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    },
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = item.label,
                                color = tint,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        // 独立加号位：贴容器右下角、与胶囊同一水平线（居中于 bar 高度）
        if (trailing != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .height(barHeight),
                contentAlignment = Alignment.Center,
            ) {
                trailing()
            }
        }
    }
}
