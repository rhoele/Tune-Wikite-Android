package com.auramusic.wiki.classic

import android.content.res.Configuration
import android.view.View
import android.widget.ImageView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/** 云小编中心的页面状态。 */
data class CloudEditorUi(
    val loggedIn: Boolean = false,
    val myUserId: Long = 0L,
    /** 首次加载：用于骨架屏，而不是整屏转圈 */
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val profile: CloudEditorClient.Profile? = null,
    val pointFlow: List<CloudEditorClient.PointRecord> = emptyList(),
    val rankType: Int = CloudEditorClient.RANK_DAY,
    val ranks: List<CloudEditorClient.RankEntry> = emptyList(),
    val rankLoading: Boolean = false,
    val rankUpdateTime: Long = 0L,
    val myRank: Int = 0,
    val vip: CloudEditorClient.VipState? = null,
    val lotteryChance: Int = -1,
    val lotteryDrawing: Boolean = false,
    val lotteryMessage: String? = null,
    /** 抽奖结果提示的生成时间：用于重置自动收起计时 */
    val lotteryNoticeAt: Long = 0L,
    /** 自动抽奖开关：与 WikiPrefs 同步，后续迁移到自动化设置页时直接复用 */
    val autoLottery: Boolean = false,
    /** 自动抽奖进行到第几次（0 表示未在自动抽奖） */
    val autoDrawingRound: Int = 0,
    /** 0 积分流水 / 1 排行榜 */
    val tab: Int = TAB_FLOW,
    val signing: Boolean = false,
    val claiming: Boolean = false
)

internal const val TAB_FLOW = 0
internal const val TAB_RANK = 1

/**
 * Compose 侧主题：色值全部从当前 XML 主题的 MD3 属性读取，
 * 因此调色板、深浅色、动态取色与老页面保持一致。
 */
@Composable
fun WikiComposeTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    val night = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // 与 XML 页面共用「动态取色」开关：开启且系统支持时直接用壁纸取色，
    // 否则回落到主题里定义好的 MD3 调色板
    if (AppearanceManager.isDynamicPalette(context) && AppearanceManager.dynamicColorSupported()) {
        MaterialTheme(
            colorScheme = if (night) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context),
            content = content
        )
        return
    }

    fun token(attr: Int, fallback: Color): Color = try {
        Color(MaterialColors.getColor(view, attr))
    } catch (e: Throwable) {
        fallback
    }

    val primary = token(androidx.appcompat.R.attr.colorPrimary, Color(0xFF6750A4))
    val onPrimary = token(com.google.android.material.R.attr.colorOnPrimary, Color.White)
    val primaryContainer =
        token(com.google.android.material.R.attr.colorPrimaryContainer, primary)
    val onPrimaryContainer =
        token(com.google.android.material.R.attr.colorOnPrimaryContainer, onPrimary)
    val secondaryContainer =
        token(com.google.android.material.R.attr.colorSecondaryContainer, primaryContainer)
    val onSecondaryContainer =
        token(com.google.android.material.R.attr.colorOnSecondaryContainer, onPrimaryContainer)
    val tertiaryContainer =
        token(com.google.android.material.R.attr.colorTertiaryContainer, secondaryContainer)
    val onTertiaryContainer =
        token(com.google.android.material.R.attr.colorOnTertiaryContainer, onSecondaryContainer)
    val surface = token(com.google.android.material.R.attr.colorSurface, Color(0xFFFEF7FF))
    val onSurface = token(com.google.android.material.R.attr.colorOnSurface, Color(0xFF1D1B20))
    val surfaceVariant =
        token(com.google.android.material.R.attr.colorSurfaceVariant, Color(0xFFE7E0EC))
    val onSurfaceVariant =
        token(com.google.android.material.R.attr.colorOnSurfaceVariant, Color(0xFF49454F))
    val outline = token(com.google.android.material.R.attr.colorOutline, onSurfaceVariant)
    val error = token(androidx.appcompat.R.attr.colorError, Color(0xFFB3261E))
    val onError = token(com.google.android.material.R.attr.colorOnError, Color.White)

    val scheme = if (night) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            error = error,
            onError = onError,
            background = surface,
            onBackground = onSurface
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            error = error,
            onError = onError,
            background = surface,
            onBackground = onSurface
        )
    }

    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun CloudEditorScreen(
    ui: CloudEditorUi,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
    onSign: () -> Unit,
    onClaimVip: () -> Unit,
    onDrawLottery: () -> Unit,
    onSelectRankType: (Int) -> Unit,
    onSelectTab: (Int) -> Unit,
    onToggleAutoLottery: (Boolean) -> Unit,
    onDismissLottery: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!ui.loggedIn) {
        LoginRequired(onLogin = onLogin, modifier = modifier)
        return
    }

    // 根节点用 Surface：它会同时设置 LocalContentColor，
    // 否则深色主题下未显式指定颜色的文字会落到默认黑色（几乎看不见）
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        val listState = rememberLazyListState()
        val skeleton = ui.loading && ui.profile == null
        val skeletonAlpha = rememberSkeletonAlpha()

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                flingBehavior = fasterFlingBehavior(),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "dashboard") {
                    DashboardCard(
                        ui = ui,
                        skeleton = skeleton,
                        onRefresh = onRefresh,
                        onSign = onSign,
                        onClaimVip = onClaimVip,
                        onDrawLottery = onDrawLottery
                    )
                }
                item(key = "auto-lottery") {
                    AutoLotteryRow(
                        autoLottery = ui.autoLottery,
                        enabled = !ui.lotteryDrawing && ui.lotteryChance != 0,
                        remaining = ui.lotteryChance,
                        drawing = ui.lotteryDrawing,
                        onToggle = onToggleAutoLottery
                    )
                }
                ui.lotteryMessage?.let { message ->
                    item(key = "lottery-message") {
                        LotteryNotice(
                            message = message,
                            showAt = ui.lotteryNoticeAt,
                            onDismiss = onDismissLottery
                        )
                    }
                }
                ui.error?.let { message ->
                    item(key = "error") {
                        ErrorCard(
                            message = message,
                            onRetry = onRefresh,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
                item(key = "tabs") {
                    PillTabs(
                        labels = listOf(
                            string(R.string.cloud_editor_flow),
                            string(R.string.cloud_editor_tab_rank)
                        ),
                        selected = ui.tab,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onSelect = onSelectTab
                    )
                }

                if (ui.tab == TAB_RANK) {
                    rankSection(ui, skeleton, skeletonAlpha, onSelectRankType)
                } else {
                    flowSection(ui, skeleton, skeletonAlpha)
                }
            }
            // 刷新进度条叠在顶部，避免出现/消失时把整页内容顶下去
            AnimatedVisibility(
                visible = ui.refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(120))
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ----------------------------------------------------------------------
// 仪表盘：资料 + 三个核心操作（参考卡片式布局）
// ----------------------------------------------------------------------

@Composable
private fun DashboardCard(
    ui: CloudEditorUi,
    skeleton: Boolean,
    onRefresh: () -> Unit,
    onSign: () -> Unit,
    onClaimVip: () -> Unit,
    onDrawLottery: () -> Unit
) {
    val profile = ui.profile
    val view = LocalView.current
    val total = rememberAnimatedNumber(profile?.totalPoints ?: 0)
    val balance = rememberAnimatedNumber(profile?.availablePoints ?: 0)
    val today = rememberAnimatedNumber(profile?.todayPoints ?: 0)
    val myRank = ui.myRank.takeIf { it > 0 } ?: profile?.rank?.takeIf { it > 0 } ?: 0

    val signed = profile?.signed == true
    val vip = ui.vip
    // 服务端文案优先：status=30 或文案含「已领取」都视为今日已领
    val vipClaimed = vip != null && (vip.status == 30 || vip.title.contains("已领取"))
    val vipClaimable = vip != null && !vipClaimed &&
            (vip.status == 10 || vip.status == 20) &&
            (profile == null || profile.availablePoints >= vip.threshold)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile?.levelDesc?.ifEmpty { null }
                            ?: string(R.string.cloud_editor_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (profile != null) {
                        Text(
                            text = string(R.string.cloud_editor_level, profile.level),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                RefreshAction(refreshing = ui.refreshing, onRefresh = onRefresh)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 积分主区：渐变底 + 等级进度，既补足视觉层次，也让「还差多少」一眼可见
            val level = profile?.level ?: 0
            val floor = levelFloor(level)
            val ceil = levelCeil(level)
            val fraction = when {
                skeleton || floor <= 0 || ceil <= floor -> 0f
                else -> ((total - floor).toFloat() / (ceil - floor)).coerceIn(0f, 1f)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                            )
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    text = string(R.string.cloud_editor_total_points),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (skeleton) {
                    SkeletonBox(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .width(150.dp)
                            .height(40.dp)
                    )
                } else {
                    // 数字直接输出、不再套 AnimatedContent：
                    // 尺寸过渡会裁剪内容，大字号下表现为右侧被切掉一小段
                    Text(
                        text = total.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        profile == null || level <= 0 -> ""
                        profile.needPoints > 0 -> string(
                            R.string.cloud_editor_next_level,
                            level + 1,
                            profile.needPoints
                        )

                        else -> string(R.string.cloud_editor_level_max)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    label = string(R.string.cloud_editor_available_points),
                    value = if (skeleton) "—" else balance.toString(),
                    container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = string(R.string.cloud_editor_today_points),
                    value = if (skeleton) "—" else today.toString(),
                    container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = string(R.string.cloud_editor_my_rank),
                    value = if (skeleton) "—" else if (myRank > 0) "第 $myRank" else "—",
                    container = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        HapticsManager.tap(view)
                        onSign()
                    },
                    enabled = profile != null && !signed && !ui.signing,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    ButtonLabel(
                        text = when {
                            ui.signing -> string(R.string.cloud_editor_loading)
                            signed -> string(R.string.cloud_editor_signed)
                            else -> string(R.string.cloud_editor_action_sign)
                        }
                    )
                }
                FilledTonalButton(
                    onClick = {
                        HapticsManager.tap(view)
                        onClaimVip()
                    },
                    enabled = profile != null && vipClaimable && !ui.claiming,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    ButtonLabel(
                        text = when {
                            ui.claiming -> string(R.string.cloud_editor_loading)
                            vipClaimed -> string(R.string.cloud_editor_vip_claimed_short)
                            else -> string(R.string.cloud_editor_action_vip)
                        }
                    )
                }
                Button(
                    onClick = {
                        HapticsManager.tap(view)
                        onDrawLottery()
                    },
                    enabled = profile != null && !ui.lotteryDrawing && ui.lotteryChance != 0,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    ButtonLabel(
                        text = when {
                            ui.autoDrawingRound > 0 ->
                                string(R.string.cloud_editor_auto_drawing, ui.autoDrawingRound)

                            ui.lotteryDrawing -> string(R.string.cloud_editor_lottery_drawing)
                            ui.lotteryChance == 0 -> string(R.string.cloud_editor_lottery_done)
                            else -> string(R.string.cloud_editor_action_lottery)
                        }
                    )
                }
            }
            if (vip != null && vip.title.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = vip.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 按钮文案用 AnimatedContent 过渡，避免状态切换时“闪一下”。 */
@Composable
private fun ButtonLabel(text: String) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
        label = "button-label"
    ) { value ->
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 仪表盘上的小数据块：每种指标一个色调，避免整块卡片只有黑白灰。 */
@Composable
private fun StatTile(
    label: String,
    value: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content.copy(alpha = 0.75f)
        )
    }
}

/** 图4 等级门槛（Lv.1–Lv.30）：用于计算当前等级内的进度。 */
private val LEVEL_FLOOR = intArrayOf(
    0,
    0, 100, 400, 800, 1500, 3000, 6000, 10000, 15000, 20000,
    28000, 40000, 52000, 68000, 80000, 90000, 110000, 140000, 170000, 200000,
    240000, 300000, 360000, 420000, 480000, 540000, 620000, 720000, 840000, 1000000
)

private fun levelFloor(level: Int): Int = LEVEL_FLOOR.getOrElse(level) { 0 }

private fun levelCeil(level: Int): Int =
    if (level in 1 until 30) LEVEL_FLOOR[level + 1] else 0


/** 刷新入口：刷新中图标持续旋转，替代没有反馈的瞬时点击。 */
@Composable
private fun RefreshAction(refreshing: Boolean, onRefresh: () -> Unit) {
    val view = LocalView.current
    val transition = rememberInfiniteTransition(label = "refresh")
    val spinning by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "refresh-spin"
    )
    val angle = if (refreshing) spinning else 0f
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = !refreshing) {
                HapticsManager.tap(view)
                onRefresh()
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(18.dp).rotate(angle)) {
            val color = Color(0xFF9A9A9A)
            val stroke = 1.8.dp.toPx()
            val r = size.minDimension / 2f - stroke / 2f - 1f
            drawArc(
                color = color,
                startAngle = 40f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(size.width / 2f - r, size.height / 2f - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = StrokeCap.Round
                )
            )
            drawCircle(
                color = color,
                radius = stroke * 0.9f,
                center = Offset(size.width / 2f + r * 0.62f, size.height / 2f - r * 0.72f)
            )
        }
    }
}

@Composable
private fun AutoLotteryRow(
    autoLottery: Boolean,
    enabled: Boolean,
    remaining: Int,
    drawing: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = string(R.string.cloud_editor_auto_lottery),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (remaining >= 0) {
                    string(R.string.cloud_editor_lottery_remain, remaining)
                } else {
                    string(R.string.cloud_editor_lottery_limit)
                } + " · " + string(R.string.cloud_editor_auto_lottery_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = autoLottery,
            onCheckedChange = {
                HapticsManager.toggle(view)
                onToggle(it)
            },
            enabled = enabled && !drawing
        )
    }
}

// ----------------------------------------------------------------------
// 抽奖结果通知
// ----------------------------------------------------------------------

/**
 * 抽奖结果用通知样式呈现：展开进入、停留数秒后收起离场，点击可立即收起。
 *
 * <p>自动抽奖会连抽三次，这里把每一轮的奖品分行列出，积分奖品带具体数值，
 * 避免只看到奖品名却不知道到底加了多少分。
 */
@Composable
private fun LotteryNotice(message: String, showAt: Long, onDismiss: () -> Unit) {
    var expanded by remember(showAt) { mutableStateOf(true) }
    LaunchedEffect(showAt) {
        expanded = true
        kotlinx.coroutines.delay(6000)
        expanded = false
    }
    // 收起动画播完再真正移除，保证点按与自动收起都有离场过渡
    LaunchedEffect(expanded) {
        if (!expanded) {
            kotlinx.coroutines.delay(240)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(260)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(220)) + fadeOut(tween(150))
    ) {
        val checkColor = MaterialTheme.colorScheme.onPrimary
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable {
                    expanded = false
                    onDismiss()
                },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(16.dp)) {
                        drawLine(
                            color = checkColor,
                            start = Offset(size.width * 0.18f, size.height * 0.55f),
                            end = Offset(size.width * 0.42f, size.height * 0.78f),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = checkColor,
                            start = Offset(size.width * 0.42f, size.height * 0.78f),
                            end = Offset(size.width * 0.84f, size.height * 0.24f),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = string(R.string.cloud_editor_lottery_result_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 标签栏（分段胶囊）
// ----------------------------------------------------------------------

@Composable
private fun PillTabs(
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val count = labels.size.coerceAtLeast(1)
    val shape = RoundedCornerShape(24.dp)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape)
            .padding(4.dp)
    ) {
        val tabWidth = maxWidth / count
        val tabWidthPx = with(density) { tabWidth.toPx() }
        val maxX = tabWidthPx * (count - 1)
        var dragging by remember { mutableStateOf(false) }
        var dragPx by remember { mutableFloatStateOf(0f) }
        // 拖动时直接跟手（snap），松手后按选中项平滑归位
        val indicatorX by animateDpAsState(
            targetValue = if (dragging) with(density) { dragPx.toDp() } else tabWidth * selected,
            animationSpec = if (dragging) snap() else tween(220),
            label = "pill-indicator"
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorX)
                .width(tabWidth)
                .height(36.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                val contentColor by animateColorAsState(
                    targetValue = if (active) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(180),
                    label = "pill-fg"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = contentColor
                    )
                }
            }
        }

        // 手势层：轻点选中，横向拖动时滑块跟手，松手吸附到最近的标签。
        // 点击与拖动必须放在同一个检测器里：两个 pointerInput 同时监听时，
        // 点击会被拖动检测吞掉，表现为「有时点不动、有时拖不动」。
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(count, tabWidthPx, selected) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        var lastX = startX
                        var mode = 0 // 0 未定 / 1 拖动 / 2 放弃（交给列表滚动）
                        var accX = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                break
                            }
                            val dx = change.position.x - lastX
                            val dy = change.position.y - down.position.y
                            lastX = change.position.x
                            accX += dx
                            if (mode == 0) {
                                // 只有横向明显占优才进入拖动；纵向优先时交还给列表滚动
                                if (kotlin.math.abs(accX) > viewConfiguration.touchSlop &&
                                    kotlin.math.abs(accX) > kotlin.math.abs(dy)
                                ) {
                                    mode = 1
                                    dragging = true
                                    dragPx = (startX - tabWidthPx / 2f)
                                        .coerceIn(0f, maxX)
                                } else if (kotlin.math.abs(dy) > viewConfiguration.touchSlop &&
                                    kotlin.math.abs(dy) > kotlin.math.abs(accX) * 1.5f
                                ) {
                                    mode = 2
                                }
                            }
                            if (mode == 1) {
                                change.consume()
                                dragPx = (dragPx + dx).coerceIn(0f, maxX)
                            }
                        }
                        when (mode) {
                            1 -> {
                                var target = ((dragPx + tabWidthPx / 2f) / tabWidthPx)
                                    .roundToInt().coerceIn(0, count - 1)
                                // 短距离快速一划也切换：位置还没到一半时按方向切到相邻页
                                if (target == selected &&
                                    kotlin.math.abs(accX) > with(density) { 24.dp.toPx() }
                                ) {
                                    target = (selected + if (accX > 0) 1 else -1)
                                        .coerceIn(0, count - 1)
                                }
                                dragging = false
                                if (target != selected) {
                                    HapticsManager.toggle(view)
                                    onSelect(target)
                                }
                            }

                            0 -> {
                                val index = (startX / tabWidthPx).toInt()
                                    .coerceIn(0, count - 1)
                                if (index != selected) {
                                    HapticsManager.toggle(view)
                                    onSelect(index)
                                } else {
                                    HapticsManager.tap(view)
                                }
                            }
                        }
                    }
                }
        )
    }
}

// ----------------------------------------------------------------------
// 积分流水
// ----------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.flowSection(
    ui: CloudEditorUi,
    skeleton: Boolean,
    skeletonAlpha: Float
) {
    if (skeleton || (ui.loading && ui.pointFlow.isEmpty())) {
        items(6) { SkeletonRow(withAvatar = false, alpha = skeletonAlpha) }
        return
    }
    if (ui.pointFlow.isEmpty()) {
        item(key = "flow-empty") { EmptyHint(string(R.string.cloud_editor_flow_empty)) }
        return
    }
    itemsIndexed(ui.pointFlow, key = { index, _ -> "flow-$index" }) { _, record ->
        FlowCard(
            record = record,
            modifier = Modifier.animateItem()
        )
    }
}

@Composable
private fun FlowCard(
    record: CloudEditorClient.PointRecord,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title.ifEmpty {
                        string(R.string.cloud_editor_activity_default_title)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = record.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (record.points >= 0) "+${record.points}" else record.points.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (record.points >= 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ----------------------------------------------------------------------
// 排行榜
// ----------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.rankSection(
    ui: CloudEditorUi,
    skeleton: Boolean,
    skeletonAlpha: Float,
    onSelectRankType: (Int) -> Unit
) {
    val types = listOf(
        CloudEditorClient.RANK_DAY,
        CloudEditorClient.RANK_MONTH,
        CloudEditorClient.RANK_TOTAL
    )
    item(key = "rank-tabs") {
        PillTabs(
            labels = listOf(
                string(R.string.cloud_editor_rank_day),
                string(R.string.cloud_editor_rank_month),
                string(R.string.cloud_editor_rank_total)
            ),
            selected = types.indexOf(ui.rankType).coerceAtLeast(0),
            modifier = Modifier.padding(horizontal = 16.dp),
            onSelect = { index -> onSelectRankType(types[index]) }
        )
    }
    item(key = "my-rank") {
        MyRankRow(
            rank = ui.myRank.takeIf { it > 0 } ?: ui.profile?.rank ?: 0,
            updateTime = ui.rankUpdateTime,
            loading = ui.rankLoading
        )
    }
    if (skeleton || (ui.rankLoading && ui.ranks.isEmpty())) {
        items(6) { SkeletonRow(withAvatar = true, alpha = skeletonAlpha) }
        return
    }
    if (ui.ranks.isEmpty()) {
        item(key = "rank-empty") { EmptyHint(string(R.string.cloud_editor_rank_empty)) }
        return
    }
    itemsIndexed(ui.ranks, key = { index, entry -> "rank-$index-${entry.userId}" }) { _, entry ->
        RankCard(
            entry = entry,
            highlight = entry.userId == ui.myUserId,
            modifier = Modifier.animateItem()
        )
    }
}

@Composable
private fun MyRankRow(rank: Int, updateTime: Long, loading: Boolean) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = string(R.string.cloud_editor_my_rank),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            AnimatedContent(
                targetState = rank,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) },
                label = "my-rank"
            ) { value ->
                Text(
                    text = if (value > 0) string(R.string.cloud_editor_rank_value, value) else "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = when {
                loading -> string(R.string.cloud_editor_loading)
                updateTime > 0 -> string(
                    R.string.cloud_editor_rank_updated,
                    formatClock(updateTime)
                )

                else -> string(R.string.cloud_editor_rank_updated_empty)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RankCard(
    entry: CloudEditorClient.RankEntry,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(
                        if (entry.rank <= 3) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = entry.rank.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.rank <= 3) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Avatar(url = entry.avatarUrl, sizeDp = 34)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.nickname.ifEmpty { string(R.string.cloud_editor_anonymous) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.levelDesc.isNotEmpty()) {
                    Text(
                        text = entry.levelDesc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = string(R.string.cloud_editor_rank_points, entry.points),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ----------------------------------------------------------------------
// 骨架屏与通用组件
// ----------------------------------------------------------------------

/** 骨架屏共用一个呼吸动画，避免每行各跑一个无限动画拖慢滚动。 */
@Composable
private fun rememberSkeletonAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton-alpha"
    )
    return alpha
}

/** 首次加载骨架：与真实内容同尺寸，数据到达时只做淡入，不会“从转圈跳到内容”。 */
@Composable
private fun SkeletonRow(withAvatar: Boolean, alpha: Float) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 与真实卡片的内边距对齐：榜单卡片 10dp、流水卡片 12dp，
                // 骨架和内容切换时高度不会跳变
                .padding(horizontal = 14.dp, vertical = if (withAvatar) 10.dp else 12.dp)
                .alpha(alpha),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (withAvatar) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(14.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.3f)
                        .height(10.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            SkeletonBox(
                modifier = Modifier
                    .width(44.dp)
                    .height(14.dp)
            )
        }
    }
}

@Composable
private fun SkeletonBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
    )
}

@Composable
private fun Avatar(url: String, sizeDp: Int) {
    val density = LocalDensity.current
    if (url.isEmpty()) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        return
    }
    val px = with(density) { sizeDp.dp.roundToPx() }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { view -> CoverLoader.get().load(view, url, px) },
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LoginRequired(onLogin: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = string(R.string.cloud_editor_login_required),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onLogin) {
                Text(string(R.string.cloud_editor_login_action))
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onRetry) {
                Text(string(R.string.cloud_editor_retry))
            }
        }
    }
}

/** 与设置页一致的计数动效：首次出现与数值变化都从旧值平滑推进到新值。 */
@Composable
private fun rememberAnimatedNumber(target: Int): Int {
    val context = LocalContext.current
    var shown by remember { mutableIntStateOf(0) }
    var animator by remember { mutableStateOf<android.animation.ValueAnimator?>(null) }
    LaunchedEffect(target) {
        val from = shown
        if (from == target) {
            return@LaunchedEffect
        }
        animator?.cancel()
        animator = MotionManager.counter(context) { fraction ->
            shown = from + ((target - from) * fraction).roundToInt()
        }
    }
    DisposableEffect(Unit) {
        onDispose { animator?.cancel() }
    }
    return shown
}

/** 加快列表滚动：在默认 fling 基础上提高初始速度，观感更跟手。 */
@Composable
private fun fasterFlingBehavior(): FlingBehavior {
    val base = ScrollableDefaults.flingBehavior()
    return remember(base) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                with(base) { performFling(initialVelocity * 1.35f) }
        }
    }
}

@Composable
private fun string(resId: Int): String = LocalContext.current.getString(resId)

@Composable
private fun string(resId: Int, vararg args: Any): String =
    LocalContext.current.getString(resId, *args)

private fun formatClock(millis: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "MM-dd HH:mm"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.CHINA).format(then.time)
}

/** 供 Activity 预加载头像时复用：把 dp 尺寸换算成 px。 */
internal fun avatarTargetPx(sizeDp: Int, density: Float): Int =
    (sizeDp * density).toInt()
