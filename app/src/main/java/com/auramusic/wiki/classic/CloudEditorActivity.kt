package com.auramusic.wiki.classic

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/** 每日抽奖上限：官方规则为每次 200 积分、每日最多 3 次，多点无效。 */
private const val LOTTERY_MAX_PER_DAY = 3

/** 单次抽奖消耗的积分：用于从今日流水里反推已抽次数。 */
private const val LOTTERY_COST = 200

/** 首屏预热的榜单行数：与一屏可视条目相当，滚动时直接命中缓存。 */
private const val PRELOAD_RANK_ROWS = 50

/**
 * 云小编中心（Compose 试点页）。
 *
 * <p>页面职责：
 * <ul>
 *   <li>展示云小编等级 / 头衔 / 总积分 / 可用积分 / 今日积分与我的排名；</li>
 *   <li>每日签到、一日黑胶会员与活动任务领取；</li>
 *   <li>贡献排行榜（日榜 / 月榜 / 总榜）与积分流水。</li>
 * </ul>
 *
 * <p>所有请求都在工作线程完成、主线程回填 {@link CloudEditorUi}；Compose 只做渲染，
 * 这样与既有 XML 页面的线程模型一致，也避免为单个页面引入 ViewModel 依赖。
 */
class CloudEditorActivity : BaseActivity() {

    private var ui by mutableStateOf(CloudEditorUi())
    private var profileToken: Http.CancelToken? = null
    private var rankToken: Http.CancelToken? = null
    private var lotteryToken: Http.CancelToken? = null
    /** 流水后续分页沿用同一个 token，离开页面时一起取消 */
    private var flowToken: Http.CancelToken? = null
    private var loadSeq = 0
    private var rankSeq = 0

    /** 日/月/总榜的最近一次结果：切榜与离开排行榜时先渲染缓存，避免清空后闪骨架 */
    private val rankCache = HashMap<Int, CloudEditorClient.RankPage>()

    /** 首屏多个互不依赖的请求并行执行，页面离开时统一退出。 */
    private val ioExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                loadAll()
            } else {
                refreshLoginState()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_editor)
        bindToolbar(R.string.cloud_editor_title)

        val compose = findViewById<ComposeView>(R.id.compose_cloud_editor)
        compose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        // 先铺上一次会话的快照：再次进入时立刻有内容，网络请求只负责校正
        val cached = Cache.ui
        ui = (cached ?: CloudEditorUi()).copy(
            loggedIn = SessionManager.isLoggedIn(this),
            myUserId = SessionManager.userId(this),
            loading = false,
            refreshing = cached != null && SessionManager.isLoggedIn(this),
            error = null,
            autoLottery = WikiPrefs.autoLottery(this)
        )

        compose.setContent {
            WikiComposeTheme {
                CloudEditorScreen(
                    ui = ui,
                    onRefresh = { loadAll() },
                    onLogin = { openLogin() },
                    onSign = { sign() },
                    onClaimVip = { claimVip() },
                    onDrawLottery = { drawLottery() },
                    onSelectRankType = { type -> selectRank(type) },
                    onSelectTab = { index -> selectTab(index) },
                    onToggleAutoLottery = { enabled -> toggleAutoLottery(enabled) },
                    onDismissLottery = { dismissLotteryNotice() }
                )
            }
        }

        loadAll()
    }

    override fun onDestroy() {
        loadSeq++
        rankSeq++
        profileToken?.cancel()
        rankToken?.cancel()
        lotteryToken?.cancel()
        flowToken?.cancel()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    private fun refreshLoginState() {
        ui = ui.copy(
            loggedIn = SessionManager.isLoggedIn(this),
            myUserId = SessionManager.userId(this)
        )
    }

    private fun openLogin() {
        loginLauncher.launch(Intent(this, LoginActivity::class.java))
    }

    private fun loadAll() {
        if (!SessionManager.isLoggedIn(this)) {
            Cache.ui = null
            ui = CloudEditorUi(loggedIn = false)
            return
        }
        profileToken?.cancel()
        flowToken?.cancel()
        val token = Http.CancelToken()
        profileToken = token
        flowToken = token
        val seq = ++loadSeq
        // 资料刷新会同时重建榜单首屏，两者共用一个序号，避免旧请求覆盖新数据
        rankSeq++
        val rankType = ui.rankType
        // 榜单最多上千条：只有用户当前就在排行榜标签页、或已有数据需要刷新时才请求，
        // 点进页面默认停在流水页，可以省掉这一次大请求
        val wantRank = ui.tab == TAB_RANK || ui.ranks.isNotEmpty()

        ui = ui.copy(
            loggedIn = true,
            myUserId = SessionManager.userId(this),
            loading = ui.profile == null,
            refreshing = ui.profile != null,
            error = null,
            rankLoading = wantRank && ui.ranks.isEmpty()
        )

        Thread({
            var error: String? = null
            var profile: CloudEditorClient.Profile? = null
            try {
                profile = CloudEditorClient.fetchProfile(this, token)
            } catch (e: WikiException) {
                if (e.kind == WikiException.Kind.CANCELLED) {
                    return@Thread
                }
                error = e.message ?: getString(R.string.login_err_unknown)
            }

            var flow: List<CloudEditorClient.PointRecord> = emptyList()
            var rankPage: CloudEditorClient.RankPage? = null
            var vip: CloudEditorClient.VipState? = null
            var chance = -1
            if (profile != null) {
                // 首屏只取流水第一页（200 条）；历史分页在界面渲染后继续补齐，
                // 避免进入页面时要等二十来次串行请求
                val flowTask = ioExecutor.submit(java.util.concurrent.Callable {
                    runCatching {
                        CloudEditorClient.fetchPointFlowPage(
                            this, 0, CloudEditorClient.FLOW_PAGE_SIZE, token
                        )
                    }.getOrDefault(emptyList())
                })
                val rankTask = if (wantRank) {
                    ioExecutor.submit(java.util.concurrent.Callable {
                        runCatching {
                            CloudEditorClient.fetchRank(this, rankType, token)
                        }.getOrNull()
                    })
                } else {
                    null
                }
                // 权益与抽奖次数属于「有则展示」的附加信息，失败不影响主数据
                val vipTask = ioExecutor.submit(java.util.concurrent.Callable {
                    runCatching { CloudEditorClient.fetchVipState(this, token) }.getOrNull()
                })
                flow = runCatching { flowTask.get() }.getOrDefault(emptyList())
                rankPage = rankTask?.let { runCatching { it.get() }.getOrNull() }
                vip = runCatching { vipTask.get() }.getOrNull()
                chance = CloudEditorClient.fetchLotteryChance(this, token)
            }

            runOnUiThread {
                if (seq != loadSeq || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                profileToken = null
                // 加载期间用户可能已经切换榜单维度：此时保留用户选择，不用旧维度的结果覆盖
                val sameRankType = ui.rankType == rankType
                ui = ui.copy(
                    loading = false,
                    refreshing = false,
                    error = error,
                    myUserId = SessionManager.userId(this),
                    profile = profile ?: ui.profile,
                    pointFlow = flow,
                    rankType = ui.rankType,
                    // 没请求榜单或请求失败时保留旧数据，避免列表被清空后闪一下骨架
                    ranks = if (sameRankType && rankPage != null) rankPage.items else ui.ranks,
                    rankUpdateTime = if (sameRankType && rankPage != null) rankPage.updateTime
                    else ui.rankUpdateTime,
                    myRank = if (sameRankType && rankPage != null) {
                        rankPage.currentUser?.rank ?: ui.myRank
                    } else {
                        ui.myRank
                    },
                    rankLoading = if (sameRankType) false else ui.rankLoading,
                    vip = vip ?: ui.vip,
                    lotteryChance = effectiveChance(chance, flow)
                )
                Cache.ui = ui
                if (sameRankType && rankPage != null) {
                    rankCache[rankType] = rankPage
                }
                // 首屏头像预热：滚动到榜单条目时直接命中缓存，不再等一次网络
                preloadAvatars(ui.profile, ranked = if (sameRankType) rankPage else null)
                if (flow.size >= CloudEditorClient.FLOW_PAGE_SIZE) {
                    continueFlow(seq, flow.size, token)
                }
            }
        }, "wiki-cloud-editor").start()
    }

    /** 流水历史分页：首屏渲染完成后逐页补齐，边滚边到，不阻塞进入页面。 */
    private fun continueFlow(seq: Int, start: Int, token: Http.CancelToken) {
        Thread({
            var offset = start
            repeat(200) {
                if (token.isCancelled) {
                    return@Thread
                }
                val batch = try {
                    CloudEditorClient.fetchPointFlowPage(
                        this, offset, CloudEditorClient.FLOW_PAGE_SIZE, token
                    )
                } catch (e: WikiException) {
                    return@Thread
                }
                if (batch.isEmpty()) {
                    return@Thread
                }
                runOnUiThread {
                    if (seq != loadSeq || isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    ui = ui.copy(pointFlow = ui.pointFlow + batch)
                    Cache.ui = ui
                }
                if (batch.size < CloudEditorClient.FLOW_PAGE_SIZE) {
                    return@Thread
                }
                offset += batch.size
            }
        }, "wiki-cloud-flow").start()
    }

    private fun selectRank(type: Int) {
        if (type == ui.rankType && ui.ranks.isNotEmpty()) {
            return
        }
        // 切榜时优先渲染该维度的缓存，再在后台校正，避免列表闪空
        ui = ui.copy(rankType = type)
        applyCachedRank(type)
        loadRank(type)
    }

    /** 标签切换：离开排行榜时维度与「我的排名」一起回到日榜。 */
    private fun selectTab(index: Int) {
        if (index == ui.tab) {
            return
        }
        if (ui.tab == TAB_RANK && index == TAB_FLOW) {
            // 取消在途请求，避免离开排行榜后旧维度的结果又写回来
            rankSeq++
            rankToken?.cancel()
            ui = ui.copy(tab = TAB_FLOW, rankType = CloudEditorClient.RANK_DAY)
            applyCachedRank(CloudEditorClient.RANK_DAY)
            return
        }
        ui = ui.copy(tab = index)
        if (index == TAB_RANK) {
            applyCachedRank(ui.rankType)
            if (ui.ranks.isEmpty() && !ui.rankLoading) {
                loadRank(ui.rankType)
            }
        }
    }

    /** 用缓存里的榜单结果立即填充界面；没有缓存时清空，等待请求返回。 */
    private fun applyCachedRank(type: Int) {
        val page = rankCache[type]
        if (page == null) {
            ui = ui.copy(
                ranks = emptyList(),
                myRank = 0,
                rankUpdateTime = 0L,
                rankLoading = false
            )
            return
        }
        ui = ui.copy(
            ranks = page.items,
            myRank = page.currentUser?.rank ?: 0,
            rankUpdateTime = page.updateTime,
            rankLoading = false
        )
    }

    /** 自动抽奖开关：与 WikiPrefs 同步，迁移到自动化设置页时无需改存储结构。 */
    private fun toggleAutoLottery(enabled: Boolean) {
        WikiPrefs.setAutoLottery(this, enabled)
        ui = ui.copy(autoLottery = enabled)
    }

    /** 榜单接口一次返回整页（最多 1000 条），页面直接懒加载渲染，不再分批。 */
    private fun loadRank(type: Int) {
        if (!SessionManager.isLoggedIn(this)) {
            return
        }
        rankToken?.cancel()
        val token = Http.CancelToken()
        rankToken = token
        val seq = ++rankSeq
        ui = ui.copy(rankLoading = true)

        Thread({
            try {
                val page = CloudEditorClient.fetchRank(this, type, token)
                runOnUiThread {
                    if (seq != rankSeq || isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    rankToken = null
                    rankCache[type] = page
                    ui = ui.copy(
                        rankType = type,
                        ranks = page.items,
                        rankUpdateTime = page.updateTime,
                        myRank = page.currentUser?.rank ?: 0,
                        rankLoading = false
                    )
                    Cache.ui = ui
                }
            } catch (e: WikiException) {
                if (e.kind == WikiException.Kind.CANCELLED) {
                    return@Thread
                }
                val message = e.message ?: getString(R.string.login_err_unknown)
                runOnUiThread {
                    if (seq != rankSeq || isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    rankToken = null
                    ui = ui.copy(rankLoading = false)
                    Feedback.showLong(this, message)
                }
            }
        }, "wiki-cloud-rank").start()
    }

    // ------------------------------------------------------------------
    // 签到与权益
    // ------------------------------------------------------------------

    /**
     * 剩余抽奖次数。
     *
     * <p>服务端返回的次数并不可靠（实测当日次数用尽后仍返回可用，点一次仍扣 200 积分），
     * 因此取三者的最保守值：服务端剩余次数、本地当日计数、今日流水里的 -200 记录条数。
     */
    private fun effectiveChance(server: Int, flow: List<CloudEditorClient.PointRecord>): Int {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(java.util.Date())
        val fromFlow = flow.count { it.points <= -LOTTERY_COST && it.time.startsWith(day) }
        val used = maxOf(fromFlow, WikiPrefs.lotteryUsedToday(this))
        val left = (LOTTERY_MAX_PER_DAY - used).coerceAtLeast(0)
        return if (server in 0..LOTTERY_MAX_PER_DAY) minOf(server, left) else left
    }

    /** 预加载首屏头像（资料卡 + 榜单首段）：只入队，真正的下载/解码由 CoverLoader 线程池处理。 */
    private fun preloadAvatars(
        profile: CloudEditorClient.Profile?,
        ranked: CloudEditorClient.RankPage?
    ) {
        val urls = ArrayList<String>()
        profile?.avatarUrl?.takeIf { it.isNotEmpty() }?.let { urls.add(it) }
        ranked?.items?.take(PRELOAD_RANK_ROWS)?.forEach {
            if (it.avatarUrl.isNotEmpty()) urls.add(it.avatarUrl)
        }
        if (urls.isEmpty()) {
            return
        }
        // 与 RankCard 里 Avatar 的实际解码边长保持一致，否则内存键不同、预热等于白做
        CoverLoader.get().preload(urls, avatarTargetPx(34, resources.displayMetrics.density))
    }

    private fun sign() {
        if (ui.signing) {
            return
        }
        val token = Http.CancelToken()
        ui = ui.copy(signing = true)
        Thread({
            try {
                val result = CloudEditorClient.sign(this, token)
                val message = if (result.points > 0) {
                    getString(R.string.cloud_editor_sign_ok, result.points)
                } else {
                    getString(R.string.login_success)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    ui = ui.copy(signing = false)
                    Feedback.show(this, message)
                    loadAll()
                }
            } catch (e: WikiException) {
                if (e.kind == WikiException.Kind.CANCELLED) {
                    return@Thread
                }
                val text = e.message ?: getString(R.string.login_err_unknown)
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    ui = ui.copy(signing = false)
                    Feedback.showLong(this, getString(R.string.cloud_editor_sign_failed, text))
                }
            }
        }, "wiki-cloud-sign").start()
    }

    private fun claimVip() {
        if (ui.claiming) {
            return
        }
        ui = ui.copy(claiming = true)
        Thread({
            try {
                CloudEditorClient.claimVip(this, Http.CancelToken())
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    // 先乐观更新为「已领取」，避免服务端状态回写延迟时按钮仍显示可领
                    val claimed = CloudEditorClient.VipState().also {
                        it.status = 30
                        it.message = ui.vip?.message ?: ""
                    }
                    ui = ui.copy(claiming = false, vip = claimed)
                    Feedback.show(this, R.string.cloud_editor_claim_ok)
                    loadAll()
                }
            } catch (e: WikiException) {
                if (e.kind == WikiException.Kind.CANCELLED) {
                    return@Thread
                }
                val text = e.message ?: getString(R.string.login_err_unknown)
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    ui = ui.copy(claiming = false)
                    Feedback.showLong(this, getString(R.string.cloud_editor_claim_failed, text))
                    // 失败常见于「今日已领取」这类状态漂移，重新同步一次再决定按钮状态
                    loadAll()
                }
            }
        }, "wiki-cloud-vip").start()
    }

    /**
     * 抽奖：单次点按抽一次，开启「自动抽奖」后一次点按连续抽到当日次数用尽（最多 3 次）。
     *
     * <p>官方 App 的转盘只是本地动画，接口本身没有冷却时间；这里不做额外延时，
     * 每轮抽完立即回填剩余次数，让按钮文案的「自动抽奖中…（n/3）」与实际进度一致。
     */
    private fun drawLottery() {
        if (ui.lotteryDrawing || ui.lotteryChance == 0) {
            return
        }
        val auto = ui.autoLottery
        val rounds = if (auto) LOTTERY_MAX_PER_DAY else 1
        var remaining = ui.lotteryChance
        lotteryToken?.cancel()
        val token = Http.CancelToken()
        lotteryToken = token
        ui = ui.copy(
            lotteryDrawing = true,
            lotteryMessage = null,
            autoDrawingRound = if (auto) 1 else 0
        )
        Thread({
            val prizes = ArrayList<String>()
            var failure: String? = null
            for (round in 1..rounds) {
                if (token.isCancelled) {
                    return@Thread
                }
                if (remaining == 0) {
                    break
                }
                if (round > 1) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            return@runOnUiThread
                        }
                        ui = ui.copy(autoDrawingRound = round)
                    }
                }
                try {
                    val result = CloudEditorClient.drawLottery(this, token)
                    prizes.add(formatPrize(result.prizeName, result.points))
                    if (result.remainChance >= 0) {
                        remaining = result.remainChance
                    }
                    // 本地计数兜底：即使服务端次数失真，也不会超过每日 3 次
                    val used = WikiPrefs.markLotteryUsed(this)
                    val left = (LOTTERY_MAX_PER_DAY - used).coerceAtLeast(0)
                    remaining = if (remaining in 0..LOTTERY_MAX_PER_DAY) {
                        minOf(left, remaining)
                    } else {
                        left
                    }
                    val done = prizes.joinToString("、")
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            return@runOnUiThread
                        }
                        ui = ui.copy(
                            lotteryChance = remaining,
                            lotteryMessage = done,
                            // 每轮刷新时间戳：自动抽奖时通知会重新开始收起计时
                            lotteryNoticeAt = System.currentTimeMillis()
                        )
                    }
                    if (remaining == 0) {
                        break
                    }
                } catch (e: WikiException) {
                    if (e.kind == WikiException.Kind.CANCELLED) {
                        return@Thread
                    }
                    failure = e.message ?: getString(R.string.login_err_unknown)
                    break
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                lotteryToken = null
                ui = ui.copy(lotteryDrawing = false, autoDrawingRound = 0)
                failure?.let {
                    Feedback.showLong(this, getString(R.string.cloud_editor_lottery_failed, it))
                }
                loadAll()
            }
        }, "wiki-cloud-lottery").start()
    }

    /**
     * 奖品文案：积分奖品补上具体数值。
     *
     * <p>部分奖品名本身已含数量（如「云小编新人抽奖1000积分」），此时不重复追加。
     */
    private fun formatPrize(name: String, points: Int): String {
        val label = name.ifEmpty { getString(R.string.cloud_editor_lottery_thanks) }
        if (points <= 0 || label.contains(points.toString())) {
            return label
        }
        return "$label（+$points 积分）"
    }

    /** 用户点按或停留超时后收起抽奖结果通知。 */
    private fun dismissLotteryNotice() {
        if (ui.lotteryMessage == null) {
            return
        }
        ui = ui.copy(lotteryMessage = null, lotteryNoticeAt = 0L)
    }

    /**
     * 跨 Activity 生命周期的轻量快照。
     *
     * <p>只保留展示所需的数据，不持有 Context；返回值随每次成功加载更新，
     * 这样第二次进入页面可以直接渲染旧数据，请求只负责校正，不再出现整页骨架。
     */
    private object Cache {
        @Volatile
        var ui: CloudEditorUi? = null
    }
}
