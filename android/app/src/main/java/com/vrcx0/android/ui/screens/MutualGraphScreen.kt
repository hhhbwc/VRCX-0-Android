package com.vrcx0.android.ui.screens

import com.vrcx0.android.ui.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.remote.MutualGraphFetchState
import com.vrcx0.android.data.remote.MutualGraphFetchStatus
import com.vrcx0.android.data.remote.MutualGraphSnapshotOutput
import com.vrcx0.android.data.remote.StreamFrame
import com.vrcx0.android.data.remote.wireJson
import com.vrcx0.android.data.repository.FriendsRoster
import com.vrcx0.android.data.repository.MutualGraphRepository
import com.vrcx0.android.data.repository.PersonSummary
import com.vrcx0.android.ui.components.ExpandableSection
import com.vrcx0.android.ui.components.RemoteAvatar
import com.vrcx0.android.ui.components.rememberMutualPeople
import com.vrcx0.android.ui.components.rememberRosterState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mutual-friends graph: a per-friend list and a radial canvas over the same
 * data.
 *
 * ## Where the data comes from, and why it is cached
 *
 * The snapshot is the product of a multi-minute server traversal over every
 * friend's friend list. It is the one screen in the app that cannot re-derive
 * its data on demand, so it is stored on the device ([MutualGraphCache]) and
 * this screen **renders what it has immediately** on open. Pressing 更新 is
 * what starts a fresh traversal; nothing does so uninvited.
 *
 * The previous version got the priority backwards: it refused to show anything
 * until a fetch had been started *in this session*, which meant the result of
 * last night's traversal was invisible until it had been re-run. The user's
 * words were the specification -- *"点开后请不要必须拉取才能看信息，以前又不是
 * 没拉取过，临时看以前的又不是不行"*.
 *
 * ## Names and faces
 *
 * A snapshot carries ids only. A friend's name and avatar come from the roster;
 * a mutual's exist nowhere else on the device and are resolved through
 * [com.vrcx0.android.data.repository.MutualPeopleRepository], which fills the
 * list and the graph in place as answers arrive. Ids that have not answered yet
 * show a placeholder -- never the id itself.
 *
 * ## Layout and highlighting
 *
 * A radial seeding (friends on an inner ring sized by how many mutuals they
 * have, each friend's mutuals fanned out in that friend's direction) is relaxed
 * with a force pass so real social circles separate into clumps. Tapping a node
 * highlights it with its neighbours and its community while everything else
 * recedes; the card below names it and opens the full profile.
 */
@Composable
fun MutualGraphScreen(
    services: SessionServices?,
    streamFrames: kotlinx.coroutines.flow.SharedFlow<StreamFrame>,
    userId: String,
    friendIds: List<String>,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    // Prefer the session's instance so the graph writes through the same cache
    // the rest of the app uses; fall back to a bare one if services are absent.
    val repo = services?.mutualGraph ?: services?.runner?.let { MutualGraphRepository(it) }

    var status by remember { mutableStateOf<MutualGraphFetchStatus?>(null) }
    var snapshot by remember { mutableStateOf<MutualGraphSnapshotOutput?>(null) }
    var view by remember { mutableStateOf(GraphView.LIST) }
    var error by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    // Epoch millis of the stored copy, so the header can say how old it is.
    // Cleared once a fresh fetch has landed in this session.
    var cachedAt by remember { mutableStateOf(0L) }
    // The friend whose mutual list is expanded in the list view.
    var expanded by remember { mutableStateOf<String?>(null) }
    // The node currently highlighted in the graph view.
    var selectedNode by remember { mutableStateOf<String?>(null) }

    // ---- restore before anything else ----
    //
    // Synchronous read of a small local file, done once per user in a
    // `remember` rather than in a `LaunchedEffect`: an effect would let one
    // frame of "还没拉取过关系图" flash before the stored graph appeared, which
    // is exactly the empty state this is meant to replace.
    val restored = remember(userId, services) { repo?.cached(userId) }
    LaunchedEffect(restored) {
        val local = restored ?: return@LaunchedEffect
        // Only fill in what is still missing: a fetch that already completed in
        // this session is newer than the file.
        if (snapshot == null) snapshot = local.snapshot
        if (status == null) status = local.status
        cachedAt = local.savedAt
    }

    LaunchedEffect(streamFrames) {
        streamFrames.collect { frame ->
            if (frame is StreamFrame.Event && frame.event == "mutualGraphFetchStatus") {
                frame.payload?.let { decodeStatus(it)?.let { status = it } }
            }
        }
    }

    // Poll while a fetch runs so the graph fills even without stream events.
    //
    // Gated on `started` in *this* session: the stored snapshot is already on
    // screen, and re-polling for it would be a stream of pointless round trips.
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        var ticks = 0
        while (ticks < 120) {
            runCatching { repo?.snapshot(userId, status) }.onSuccess {
                snapshot = it
                cachedAt = 0L
                val s = status
                if (s != null && s.status in TERMINAL_STATES) return@LaunchedEffect
            }
            delay(1500)
            ticks++
        }
    }

    val roster = rememberRosterState(services)
    val people = rememberMutualPeople(services)

    // One entry point for the fetch, so the empty state, the graph header and
    // the list all behave identically.
    fun startFetch() {
        started = true
        error = null
        scope.launch {
            runCatching { repo?.startFetch(userId, friendIds) }
                .onFailure { error = it.message }
        }
    }

    Column(modifier.fillMaxSize()) {
        // ---- header ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回".tr())
            }
            Text(
                text = "共同好友关系图".tr(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = view == GraphView.LIST,
                onClick = { view = GraphView.LIST },
                label = { Text("列表".tr()) }
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = view == GraphView.GRAPH,
                onClick = { view = GraphView.GRAPH },
                label = { Text("关系图".tr()) }
            )
        }

        // Progress gets a line of its own: squeezed next to a chip it read as
        // a static label, which is what the user reported.
        if (status != null && status!!.status !in TERMINAL_STATES) {
            LinearProgressIndicator(
                progress = {
                    val s = status!!
                    if (s.totalFriends > 0) s.processedFriends.toFloat() / s.totalFriends else 0f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            Text(
                text = statusLabel(status),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        } else if (error != null) {
            Text(
                text = error!!,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        val snap = snapshot
        when {
            // ---- genuinely nothing on this device yet ----
            snap == null && !started -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "还没拉取过关系图".tr(),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "拉取需要几分钟，服务器会逐个好友收集共同好友。".tr(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { startFetch() },
                        enabled = repo != null && friendIds.isNotEmpty()
                    ) {
                        Text("开始拉取".tr())
                    }
                }
            }

            snap == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(statusLabel(status), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            view == GraphView.LIST -> MutualGraphList(
                services = services,
                snap = snap,
                people = people,
                roster = roster,
                cachedAt = cachedAt,
                onStartFetch = { startFetch() },
                expanded = expanded,
                onExpand = { expanded = if (expanded == it) null else it },
                onOpenProfile = onOpenProfile,
                modifier = Modifier.fillMaxSize()
            )

            else -> MutualGraphGraphView(
                services = services,
                snap = snap,
                roster = roster,
                people = people,
                cachedAt = cachedAt,
                selectedNode = selectedNode,
                onSelect = { selectedNode = if (selectedNode == it) null else it },
                onOpenProfile = onOpenProfile,
                onStartFetch = { startFetch() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private val TERMINAL_STATES = setOf(
    MutualGraphFetchState.Completed,
    MutualGraphFetchState.Cancelled,
    MutualGraphFetchState.Error
)

@Composable
private fun statusLabel(status: MutualGraphFetchStatus?): String {
    if (status == null) return "空闲".tr()
    val done = status.processedFriends
    val total = status.totalFriends
    return when (status.status) {
        MutualGraphFetchState.Idle -> "空闲".tr()
        MutualGraphFetchState.Running -> "拉取中 " + done + " / " + total
        MutualGraphFetchState.Cancelling -> "正在取消…".tr()
        MutualGraphFetchState.Completed -> "完成（" + done + " / " + total + "）"
        MutualGraphFetchState.Cancelled -> "已取消".tr()
        MutualGraphFetchState.Error -> "出错：" + (status.lastError ?: "未知")
    }
}

private fun decodeStatus(payload: kotlinx.serialization.json.JsonElement): MutualGraphFetchStatus? =
    runCatching {
        wireJson.decodeFromString<MutualGraphFetchStatus>(payload.toString())
    }.getOrNull()

// ---------------------------------------------------------------- layout

/**
 * The world square the layout is normalised into.
 *
 * `buildGraphGeometry` maps every node into `0..[WORLD_SIZE]` on both axes, so
 * the canvas can pan/zoom in one coordinate system regardless of how many nodes
 * there are. Named because several places now need to convert between world
 * distance and *screen* distance, and a magic `1000f` scattered around is how
 * that conversion gets skipped.
 */
private const val WORLD_SIZE = 1000f

/**
 * Baseline screen radii, in **device pixels at scale 1**.
 *
 * ## What was wrong
 *
 * A node's radius used to live in *world* units (`6f + 8f * count / maxCount`
 * for friends, a flat `4f` for mutuals) and was multiplied by the zoom when
 * drawn. That conflates two different things: how far apart nodes are, and how
 * big a person is drawn. At the old default zoom of 1, a friend was therefore
 * drawn **6-14 physical pixels across** while its label was `11.sp` (~29 px) --
 * and a *mutual* got the same `4f` as the least-connected friend, so the graph
 * was a field of near-identical specks. The user's words were *"一个光点都比得
 * 上一个人名大了，看着特别特别拥挤"*, and "拥挤" is exactly right: density came
 * from nodes being normalised into the same square the labels had to live in,
 * with no notion of how many pixels a name actually needs.
 *
 * ## What replaces it
 *
 * Radius is a function of connectivity ([nodeScreenRadius]) applied in screen
 * space. The differences are deliberately small ([FRIEND_SPAN_PX]) because a 2x
 * size range is unreadable at these scales -- the signal for "this person is
 * central" is the halo, not a bigger dot. What the split *does* buy is telling
 * a friend from a mutual without reading a label.
 */
private const val FRIEND_BASE_PX = 2.6f
private const val FRIEND_SPAN_PX = 2.2f
private const val MUTUAL_BASE_PX = 1.5f
private const val FRIEND_FLOOR_PX = 2.0f
private const val MUTUAL_FLOOR_PX = 1.2f

/**
 * A node may never be drawn wider than this fraction of its own label's height.
 *
 * This is the invariant the user reported broken, expressed as one number so a
 * regression is caught by [MutualGraphRadiusTest] rather than by eye.
 */
private const val MAX_RADIUS_TO_TEXT = 0.5f

/** Never draw below this many pixels, or a dot vanishes into a smudge. */
private const val ABS_MIN_RADIUS_PX = 1.1f

/**
 * Minimum drawn radius of a faded node while a selection is active.
 *
 * Nodes recede when something is selected, but must stay visible as context --
 * dropping them to nothing makes the selection read as "everything else
 * disappeared" instead of "this is the one you picked".
 */
private const val FADED_MIN_RADIUS_PX = 1.6f

/**
 * Screen-space radius of a node, in **device pixels**.
 *
 * Split out of the draw loop so it can be unit-tested: "a node must never be
 * drawn larger than the label naming it" is the invariant that broke, and a
 * pure function is the only way to pin it down.
 *
 * The result grows with [scale] so zooming in enlarges nodes with the layout,
 * but is clamped against [textPx] so a node can never outgrow its own label.
 * The floor keeps the graph tappable in the zoomed-out overview, where an
 * unclamped formula would shrink hubs below a finger's precision.
 *
 * @param mutualCount a friend's number of mutuals; 0 for a mutual node.
 * @param isFriend friends draw from a larger base -- they are the nodes this
 *   screen exists to show, and they are the ones carrying labels.
 * @param textPx the label size this node's name will be drawn at.
 * @param selected the selected node is the answer to the tap: it gets a fixed,
 *   comfortably tappable size and is exempt from the text ceiling.
 * @param faded a node outside the current selection recedes but stays visible.
 */
internal fun nodeScreenRadius(
    isFriend: Boolean,
    mutualCount: Int,
    maxMutualCount: Int,
    scale: Float,
    textPx: Float,
    selected: Boolean = false,
    faded: Boolean = false
): Float {
    // How connected this person is relative to the busiest friend, 0..1.
    val t = if (isFriend && maxMutualCount > 0) {
        (mutualCount.toFloat() / maxMutualCount).coerceIn(0f, 1f)
    } else {
        0f
    }
    val base = if (isFriend) FRIEND_BASE_PX + FRIEND_SPAN_PX * t else MUTUAL_BASE_PX
    val floorPx = if (isFriend) FRIEND_FLOOR_PX else MUTUAL_FLOOR_PX

    // Zoom grows the drawing, but never below the floor for that zoom.
    var r = (base * scale).coerceAtLeast(floorPx * scale)
    when {
        selected -> r = (r * 1.9f).coerceAtLeast(5.5f)
        faded -> r = (r * 0.55f).coerceAtLeast(FADED_MIN_RADIUS_PX)
    }
    if (!selected) r = r.coerceAtMost(textPx * MAX_RADIUS_TO_TEXT)
    return r.coerceAtLeast(ABS_MIN_RADIUS_PX)
}

/** A laid-out node in world coordinates (0..[WORLD_SIZE] square). */
private data class GraphNode(
    val id: String,
    val name: String,
    val x: Float,
    val y: Float,
    val radius: Float,
    val isFriend: Boolean,
    val mutualCount: Int,
    /** Louvain community index; -1 = unassigned (isolated/degree-0). */
    val community: Int = -1,
    /** Last fetch time for this friend's mutuals (friend nodes only). */
    val lastFetchedAt: String = "",
    /** True if this friend opted out / mutuals are unavailable. */
    val optedOut: Boolean = false
)

/**
 * Community colour palette, matched to the desktop app's
 * `mutualFriendsPalette.ts` (light mode). Named communities (the top 6 by
 * size, size >= 2) get a hue; the rest and isolated nodes fall back to a
 * neutral gray.
 */
private val COMMUNITY_PALETTE = listOf(
    0xFF4F5FD9, 0xFF2F9E63, 0xFFC08321, 0xFFD2545F,
    0xFF2F8FC4, 0xFF8A5CD0, 0xFF1F9A92, 0xFFCF6B32, 0xFFC85AA0
).map { androidx.compose.ui.graphics.Color(it) }

private val NEUTRAL_COMMUNITY_COLOR = androidx.compose.ui.graphics.Color(0xFFA1A8B3)

private const val NAMED_COMMUNITY_LIMIT = 6

private data class GraphGeometry(
    val nodes: Map<String, GraphNode>,
    val edges: List<Pair<Offset, Offset>>,
    /** Edge endpoints as node ids, parallel to [edges], for community checks. */
    val edgeIds: List<Pair<String, String>> = emptyList(),
    val neighbours: Map<String, Set<String>>,
    /** 0 = most label-worthy. The canvas labels ids whose rank is small. */
    val labelRank: Map<String, Int> = emptyMap(),
    /** Community index per node (Louvain); missing = unassigned. */
    val communityIndex: Map<String, Int> = emptyMap(),
    /** Ranked communities for the palette + legend. */
    val communities: List<MutualFriendsCommunities.Community> = emptyList(),
    /**
     * The largest `mutualCount` among friend nodes.
     *
     * Carried out of the builder because [nodeScreenRadius] needs it as the
     * denominator for "how connected is this person relative to the busiest
     * one", and the draw loop has no other way to recover it.
     */
    val maxMutualCount: Int = 1,
    /** Node ids that should be looked up because no name has arrived yet. */
    val unresolvedIds: List<String> = emptyList()
)

/**
 * Radial seeding + force-directed relaxation.
 *
 * The first version placed friends on a ring and mutuals on an outer ring.
 * With 242 friends the *name labels* joined end-to-end into a giant circle of
 * text -- the "names form a big ring" report. The desktop app solves this with
 * ForceAtlas2 (sigma.js): nodes drift toward their connected cluster and
 * clusters separate, so dense groups become visible clumps with gaps between.
 *
 * The radial placement is kept as the *initial* position (deterministic and
 * already roughly right), then relaxed with a grid-accelerated
 * Fruchterman-Reingold pass: repulsion between near nodes, attraction along
 * edges, a weak pull to the centre, displacement capped and cooled per
 * iteration. ~150 iterations over ~800 nodes is a few milliseconds of pure
 * arithmetic, computed once per snapshot.
 *
 * ## Names here are placeholders, never ids
 *
 * A friend's name comes from the roster; a mutual's only from the server. When
 * neither has answered yet the node is labelled [PENDING_NAME] -- an earlier
 * version fell back to a truncated id (`usr_a1b2c3…`), which is what the user
 * reported seeing: *"点击展开后为什么名字显示的是 usr 什么什么的"*. A raw id is
 * never the right thing to put on screen, and the ids that need looking up are
 * collected into [GraphGeometry.unresolvedIds] so the caller can request them.
 */
private fun buildGraphGeometry(
    snap: MutualGraphSnapshotOutput,
    friendNames: Map<String, String>,
    mutualNames: Map<String, String>
): GraphGeometry {
    val counts = snap.links.groupingBy { it.friendId }.eachCount()
    val maxCount = (counts.values.maxOrNull() ?: 1).coerceAtLeast(1)
    val metaById = snap.meta.associateBy { it.friendId }

    val friendIds = snap.friendIds.ifEmpty { snap.meta.map { it.friendId } }
    val center = WORLD_SIZE / 2f
    val rInner = 300f
    val rOuter = 480f

    val nodes = HashMap<String, GraphNode>(friendIds.size * 2)
    val unresolved = LinkedHashSet<String>()
    val friendAngle = HashMap<String, Double>()

    friendIds.forEachIndexed { index, id ->
        val angle = 2.0 * Math.PI * index / friendIds.size
        friendAngle[id] = angle
        val count = counts[id] ?: 0
        // Drop friends with no mutuals entirely: a node with no connection is
        // noise on a "mutual friends" graph. This also un-clutters the layout
        // (they were the ones the force layout pushed to the far edge).
        if (count == 0) return@forEachIndexed
        val name = friendNames[id]
        if (name == null) unresolved.add(id)
        // Radius here is a *relative weight* only; the drawn size is computed
        // in screen space by nodeScreenRadius. Keeping the ratio means the
        // force layout and the halo still know who the hubs are.
        val meta = metaById[id]
        nodes[id] = GraphNode(
            id = id,
            name = name ?: PENDING_NAME,
            x = center + (rInner * cos(angle)).toFloat(),
            y = center + (rInner * sin(angle)).toFloat(),
            radius = 1f + count.toFloat() / maxCount,
            isFriend = true,
            mutualCount = count,
            lastFetchedAt = meta?.lastFetchedAt ?: "",
            optedOut = meta?.optedOut ?: false
        )
    }

    // Mutuals: average the angles of the friends linking to them (as vectors,
    // so a person spanning the 0/2π seam is not wrapped the wrong way). A
    // mutual shared by many friends sits a bit inward -- "between friends".
    val mutualVectors = HashMap<String, MutableList<Double>>()
    for (link in snap.links) {
        val a = friendAngle[link.friendId] ?: continue
        mutualVectors.getOrPut(link.mutualId) { mutableListOf() }.add(a)
    }
    mutualVectors.forEach { (id, angles) ->
        if (id in nodes) return@forEach
        val sx = angles.sumOf { cos(it) }
        val sy = angles.sumOf { sin(it) }
        val angle = atan2(sy, sx)
        val spread = angles.size.coerceAtLeast(1)
        val r = rOuter - min(120.0, 24.0 * (spread - 1))
        val name = mutualNames[id]
        if (name == null) unresolved.add(id)
        nodes[id] = GraphNode(
            id = id,
            name = name ?: PENDING_NAME,
            x = center + (r * cos(angle)).toFloat(),
            y = center + (r * sin(angle)).toFloat(),
            radius = 1f,
            isFriend = false,
            mutualCount = 0
        )
    }

    val neighbours = HashMap<String, MutableSet<String>>()
    for (link in snap.links) {
        neighbours.getOrPut(link.friendId) { mutableSetOf() }.add(link.mutualId)
        neighbours.getOrPut(link.mutualId) { mutableSetOf() }.add(link.friendId)
    }

    // Relax from the radial seeding: connected nodes pull together, the rest
    // push apart, and the ring dissolves into clusters with real gaps.
    val relaxed = forceRelax(
        ids = nodes.keys.toList(),
        initial = nodes.mapValues { Offset(it.value.x, it.value.y) },
        edges = snap.links.mapNotNull { link ->
            val a = nodes[link.friendId] ?: return@mapNotNull null
            val b = nodes[link.mutualId] ?: return@mapNotNull null
            a.id to b.id
        }
    )
    for ((id, position) in relaxed) {
        val node = nodes.getValue(id)
        nodes[id] = node.copy(x = position.x, y = position.y)
    }

    // Label rank: nodes sorted by "how much they matter" (friend mutual count,
    // then friends before mutuals). The canvas labels only the top slice,
    // growing the slice as the user zooms in.
    val labelRank = HashMap<String, Int>(nodes.size)
    nodes.values
        .sortedWith(
            compareByDescending<GraphNode> { it.mutualCount }
                .thenByDescending { it.isFriend }
        )
        .forEachIndexed { index, node -> labelRank[node.id] = index }

    // Community detection (Louvain), ported from the desktop app. This is what
    // turns "nodes that happen to sit near each other" into real social circles
    // with a name (anchor) and a colour each. Run over the undirected friend/
    // mutual graph with degrees derived from the link counts.
    val degree = HashMap<String, Int>(nodes.size)
    for (link in snap.links) {
        degree[link.friendId] = (degree[link.friendId] ?: 0) + 1
        degree[link.mutualId] = (degree[link.mutualId] ?: 0) + 1
    }
    val undirectedEdges = buildList {
        val seen = HashSet<String>()
        for (link in snap.links) {
            val key = if (link.friendId < link.mutualId)
                link.friendId + "\u0000" + link.mutualId
            else
                link.mutualId + "\u0000" + link.friendId
            if (seen.add(key)) add(link.friendId to link.mutualId)
        }
    }
    val communities = MutualFriendsCommunities.assign(
        nodeIds = nodes.keys.toList(),
        degreeById = degree,
        edges = undirectedEdges
    )
    val communityIndex = communities.communityIndexById

    // Normalise the relaxed layout to fill the 0..WORLD_SIZE square. Without
    // this the cluster can settle into a small corner of the world (force
    // relax has no viewport notion), leaving the canvas mostly empty at the
    // initial zoom -- which reads as "the graph is tiny and cramped".
    val normalised = normaliseBounds(nodes)

    val communityNodeIds = normalised.mapValues { (id, node) ->
        node.copy(community = communityIndex[id] ?: -1)
    }

    // Edges are built LAST, from the normalised final positions. Two earlier
    // orderings were wrong: (1) before forceRelax -> lines pointed at the old
    // radial positions while dots drifted away; (2) after forceRelax but
    // before normaliseBounds -> the bounds-normalisation then moved the dots
    // again. Only these, from the settled node map, line up with the dots.
    val edges = snap.links.mapNotNull { link ->
        val a = communityNodeIds[link.friendId] ?: return@mapNotNull null
        val b = communityNodeIds[link.mutualId] ?: return@mapNotNull null
        Offset(a.x, a.y) to Offset(b.x, b.y)
    }
    val edgeIds = snap.links.mapNotNull { link ->
        if (link.friendId in communityNodeIds && link.mutualId in communityNodeIds)
            link.friendId to link.mutualId else null
    }

    return GraphGeometry(
        nodes = communityNodeIds,
        edges = edges,
        edgeIds = edgeIds,
        neighbours = neighbours,
        labelRank = labelRank,
        communityIndex = communityIndex,
        communities = communities.communities,
        maxMutualCount = maxCount,
        unresolvedIds = unresolved.filter { it in communityNodeIds }
    )
}

/**
 * Scales + recentres nodes so their bounding box fills the 0..[WORLD_SIZE]
 * square with a margin, keeping the aspect ratio. Returns a new node map with
 * updated x/y (edges are rebuilt from these by the caller).
 */
private fun normaliseBounds(nodes: Map<String, GraphNode>): Map<String, GraphNode> {
    if (nodes.isEmpty()) return nodes
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (node in nodes.values) {
        minX = minOf(minX, node.x); maxX = maxOf(maxX, node.x)
        minY = minOf(minY, node.y); maxY = maxOf(maxY, node.y)
    }
    val width = (maxX - minX).coerceAtLeast(1f)
    val height = (maxY - minY).coerceAtLeast(1f)
    val margin = 60f
    val target = WORLD_SIZE - margin * 2
    val scale = (target / width).coerceAtMost(target / height)
    val centerX = (minX + maxX) / 2f
    val centerY = (minY + maxY) / 2f
    val half = WORLD_SIZE / 2f
    return nodes.mapValues { (_, node) ->
        node.copy(
            x = half + (node.x - centerX) * scale,
            y = half + (node.y - centerY) * scale
        )
    }
}

/**
 * The label shown for a node whose name has not been resolved yet.
 *
 * Deliberately not the id. Any `usr_...` string on screen is a bug: it is both
 * unreadable and a leak of the exact thing the id was standing in for. The
 * placeholder is honest about what is happening (a lookup is in flight) and the
 * real name replaces it in place.
 */
private const val PENDING_NAME = "…"

// ---------------------------------------------------------------- canvas metrics
//
// Everything below is in **device pixels at scale 1**, not world units. Mixing
// the two coordinate systems is what produced the original "dot as big as a
// name" report, so the rule is: anything describing how something *looks* is in
// pixels; only node positions are in world units.

/** Opening zoom. At 1 the world square maps 1:1 onto a ~1000 px canvas. */
private const val DEFAULT_SCALE = 2.4f

/** Enough to see the whole graph; not so little that nodes collapse to dust. */
private const val MIN_SCALE = 0.5f

/** Past this the layout has no more detail to reveal. */
private const val MAX_SCALE = 12f

/** Tap tolerance around a node centre, in screen pixels (~18 dp on this panel). */
private const val TOUCH_SLOP_PX = 54f

/** Ordinary label size. 11 sp is legible; the previous floors were smaller. */
private const val LABEL_SP = 11f

/** Labels on a selected node or one of its neighbours. */
private const val LABEL_KEY_SP = 11.5f

private const val LABEL_SELECTED_SP = 13f

/** Gap between a node's edge and the baseline of its label, in pixels. */
private const val LABEL_GAP_PX = 5f

/** Halo stroke drawn under a label so it survives crossing an edge. */
private const val LABEL_HALO_PX = 3f

/** Halo radius around the selected node. */
private const val SELECTED_HALO_PX = 7f

/**
 * Screen area one label is allowed to occupy, in px².
 *
 * ~`60 * 24`: a name is roughly 55-70 px wide at 11 sp and needs ~18 px of
 * height plus breathing room. The budget is then the viewport area divided by
 * this, which is the number of names that can *fit* -- as opposed to the old
 * `8 * scale²`, which grew without any reference to the canvas and relied on
 * the collision check to clean up the mess.
 */
private const val LABEL_AREA_PX = 1400f

/** Floor and ceiling on the label budget, so extremes stay sane. */
private const val MIN_LABELS = 6
private const val MAX_LABELS = 42

/** Avatar sizes. List rows match the friends tab; nested people are smaller. */
private const val LIST_AVATAR_DP = 40
private const val PERSON_AVATAR_DP = 28

/** "2026-09-19T02:39:12.345Z" -> "09-19 02:39" (substring surgery, no java.time). */
private fun shortTime(iso: String): String =
    if (iso.length < 16) iso else iso.substring(5, 10) + " " + iso.substring(11, 16)

/** True if [rect] overlaps any rect already placed (label collision check). */
private fun labelBlocked(
    rect: androidx.compose.ui.geometry.Rect,
    placed: List<androidx.compose.ui.geometry.Rect>
): Boolean = placed.any { it.overlaps(rect) }

/**
 * Human name of a community's anchor node (never falls back to an id).
 *
 * Returns a literal rather than a user-visible string: `.tr()` is composable,
 * and this is a plain function. "未命名" is already the Chinese original, so ZH
 * renders it directly and the caller translates it where it is displayed.
 */
private fun MutualFriendsCommunities.Community.anchorName(geometry: GraphGeometry): String =
    geometry.nodes[anchorId]?.name?.takeIf { it != PENDING_NAME } ?: "未命名"

private fun edgeTouchesSelection(
    geometry: GraphGeometry,
    selectedNode: String?,
    a: Offset,
    b: Offset
): Boolean {
    if (selectedNode == null) return false
    val sel = geometry.nodes[selectedNode] ?: return false
    val selP = Offset(sel.x, sel.y)
    if (a == selP || b == selP) return true
    return false
}

// ---------------------------------------------------------------- graph view

@Composable
private fun MutualGraphGraphView(
    services: SessionServices?,
    snap: MutualGraphSnapshotOutput,
    roster: FriendsRoster?,
    people: Map<String, PersonSummary>,
    cachedAt: Long,
    selectedNode: String?,
    onSelect: (String?) -> Unit,
    onOpenProfile: (String) -> Unit,
    onStartFetch: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Roster first (free), then the server-resolved people. A friend is almost
    // always in the roster, but not always -- an unfriended-but-still-known id
    // can appear in an old snapshot -- so both sources feed the same map and
    // the resolved lookup wins, being the fresher of the two.
    val names = remember(roster, people) {
        val out = HashMap<String, String>()
        roster?.friends?.forEach { (id, record) ->
            record.displayName.takeIf { it.isNotBlank() }?.let { out[id] = it }
        }
        people.forEach { (id, person) ->
            person.displayName.takeIf { it.isNotBlank() }?.let { out[id] = it }
        }
        out
    }
    val geometry = remember(snap, names) {
        buildGraphGeometry(snap, names, names)
    }

    // Resolve whoever is still unnamed -- friends as well as mutuals. The old
    // version only asked for mutuals, so a friend missing from the roster kept
    // its truncated id forever.
    LaunchedEffect(geometry.unresolvedIds) {
        val missing = geometry.unresolvedIds
        if (missing.isEmpty()) return@LaunchedEffect
        services?.mutualPeople?.ensure(missing)
    }

    // Opening zoom. The old default of 1 showed a 1000-unit-wide world inside
    // ~1000 px, i.e. 1:1 -- a 3 px node. Two notches in gives nodes and labels
    // room to be read, and the user can still pinch out to the overview.
    var scale by remember { mutableStateOf(DEFAULT_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        offset += panChange
    }
    // Tapping the canvas has to be able to take focus *away* from the search
    // field, and to actually lower the keyboard. `clearFocus()` alone is not
    // enough: the IME is its own window, and on this device it overlays the
    // canvas rather than resizing it, so the graph's lower half stays covered
    // and every subsequent tap goes to the keyboard instead of to a node. The
    // explicit `hide()` is what closes it.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val selected = selectedNode?.let { geometry.nodes[it] }
    val neighbours = selectedNode?.let { geometry.neighbours[it] }.orEmpty()

    // Focused community (from the legend tap): every node in it stays lit, not
    // just the selected node's direct neighbours. -1 = none focused.
    var focusedCommunity by remember { mutableStateOf(-1) }
    val focusedCommunityIds = remember(focusedCommunity, geometry) {
        if (focusedCommunity < 0) emptySet()
        else geometry.communities.getOrNull(focusedCommunity)?.memberIds?.toSet().orEmpty()
    }

    // Search: type a name, the matching node is selected and the view recentres
    // on it so its connections are readable.
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchMatches = remember(searchQuery, geometry) {
        if (searchQuery.isBlank()) emptyList()
        else geometry.nodes.values
            .filter { it.name != PENDING_NAME && it.name.contains(searchQuery, ignoreCase = true) }
            .sortedByDescending { it.mutualCount }
            .take(8)
    }
    // Focus the top match: select it and recentre the viewport on it.
    fun focusNode(node: GraphNode) {
        onSelect(node.id)
        // Recentre so the node sits at the canvas centre.
        val cWorld = Offset(WORLD_SIZE / 2f, WORLD_SIZE / 2f)
        val delta = (Offset(node.x, node.y) - cWorld) * scale
        offset = -delta
    }
    // When the query yields a single clear match, auto-focus it.
    LaunchedEffect(searchQuery) {
        if (searchMatches.size == 1) focusNode(searchMatches.first())
    }

    Column(modifier) {
        // Search bar: find a friend and recentre the graph on them.
        //
        // Collapsible on purpose. This screen is a canvas first, and a text
        // field that is always sitting at the top is a permanent invitation to
        // open the IME -- which on this device *overlays* the graph instead of
        // resizing it, hiding the lower half. Collapsed by default, so the
        // canvas owns the screen until the user asks to search.
        if (searchOpen) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("搜索好友…".tr()) },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            onSelect(null)
                            offset = Offset.Zero
                            scale = DEFAULT_SCALE
                        }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "清除".tr())
                        }
                    }
                }
            )
        } else {
            TextButton(
                onClick = { searchOpen = true },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text("搜索好友…".tr(), style = MaterialTheme.typography.labelMedium)
            }
        }
        // Search matches as quick chips: tap one to focus it.
        if (searchMatches.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                searchMatches.forEach { match ->
                    AssistChip(
                        onClick = { focusNode(match) },
                        label = { Text(match.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .transformable(state)
        ) {
            // Colours are read in composition (the draw lambda is not
            // composable) and captured here.
            val faint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.05f)
            val edgeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
            val crossEdgeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            val highlight = MaterialTheme.colorScheme.primary
            val selectedFill = MaterialTheme.colorScheme.primary
            val neighbourFill = MaterialTheme.colorScheme.tertiaryContainer
            val labelArgb = MaterialTheme.colorScheme.onSurface.toArgb()
            // Labels are drawn over dots and edges, so they carry a thin halo
            // in the canvas background colour. Without it a name crossing a
            // line reads as noise -- which is what made the dense middle of
            // the graph illegible.
            val labelHaloArgb = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()
            // Selected node gets a bright halo ring so it pops against its own
            // neighbours; neighbours get a distinct fill + subtle ring.
            val selectedRing = MaterialTheme.colorScheme.primary
            val neighbourRing = MaterialTheme.colorScheme.tertiary

            // Community colour for a node: named communities get their palette
            // hue, the rest fall back to neutral. Alpha-scaled when a selection
            // is active and this node is not the selected one or a neighbour.
            fun communityFill(community: Int): androidx.compose.ui.graphics.Color {
                if (community < 0) return NEUTRAL_COMMUNITY_COLOR
                val named = community < NAMED_COMMUNITY_LIMIT &&
                    (geometry.communities.getOrNull(community)?.size ?: 0) >=
                    MutualFriendsCommunities.MIN_NAMED_COMMUNITY_SIZE
                if (!named) return NEUTRAL_COMMUNITY_COLOR
                return COMMUNITY_PALETTE[community % COMMUNITY_PALETTE.size]
            }

            // Tap handling lives on the Canvas, not on the parent Box.
            //
            // Measured on device: a `pointerInput` attached to the Box above
            // never ran -- the Box composed and measured (1080x1444) but the
            // gesture coroutine's first statement never executed across a dozen
            // taps, at Log.e level, while a tap on the sibling legend row
            // worked normally. Moving the *same* modifier down to the Canvas
            // leaf, which is the node that actually draws the graph and so owns
            // a real draw surface, delivered every tap immediately.
            //
            // The Box must also keep `transformable` for pan/zoom; the two do
            // not conflict because they are separate nodes in the tree, so the
            // Box's transformable sees the multi-touch stream while the Canvas
            // sees the single-finger taps that reach it.
            //
            // Corollary: any future tap target inside this Box belongs on a
            // leaf, and the hit test has to translate screen -> world by
            // inverting the same formula the draw pass uses.
            val tapModifier = Modifier.pointerInput(geometry, scale, offset) {
                detectTapGestures { position ->
                    // Any tap on the canvas dismisses the search field and
                    // lowers the IME -- otherwise the keyboard overlays the
                    // lower half of the graph and every later tap goes to the
                    // keyboard window instead of to a node.
                    focusManager.clearFocus()
                    keyboard?.hide()
                    searchOpen = false
                    // Screen point -> world point (inverse transform).
                    //
                    // The hit radius is measured in *screen* pixels and
                    // converted back to world units, so the tap target stays a
                    // constant ~18 dp however far the canvas is zoomed. The old
                    // form added a fixed world-space slop that shrank to
                    // nothing when zoomed out.
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val world = (position - c - offset) / scale + c
                    val slopWorld = TOUCH_SLOP_PX / scale
                    // Pick the nearest node within the slop. Ties go to the
                    // closer dot, and a friend (already named, already in the
                    // roster) beats a mutual at equal distance because its
                    // radius is larger -- the radius term in the comparison is
                    // what expresses that.
                    var best: String? = null
                    var bestDist = Float.MAX_VALUE
                    for ((id, node) in geometry.nodes) {
                        val d = (world - Offset(node.x, node.y)).getDistance()
                        if (d < node.radius * WORLD_SIZE / 100f + slopWorld && d < bestDist) {
                            best = id
                            bestDist = d
                        }
                    }
                    onSelect(best)
                }
            }
            Canvas(Modifier.fillMaxSize().then(tapModifier)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                fun toScreen(p: Offset) = (p - c) * scale + c + offset
                // Bounding rects of labels already placed this frame, used to
                // drop labels that would collide (see labelBlocked).
                val labelRects = mutableListOf<androidx.compose.ui.geometry.Rect>()

                // Edges first, nodes on top. Cross-community edges (a link
                // between two different circles) are drawn heavier and darker:
                // they are the "bridges" worth noticing.
                val crossEdges = geometry.edgeIds.map { (a, b) ->
                    val ca = geometry.communityIndex[a] ?: -1
                    val cb = geometry.communityIndex[b] ?: -1
                    ca >= 0 && cb >= 0 && ca != cb
                }
                geometry.edges.forEachIndexed { index, (a, b) ->
                    val cross = crossEdges.getOrNull(index) == true
                    val touchesSelection =
                        selectedNode != null && edgeTouchesSelection(geometry, selectedNode, a, b)
                    val color = when {
                        touchesSelection -> highlight.copy(alpha = 0.9f)
                        selectedNode == null && !cross -> edgeColor
                        selectedNode == null -> crossEdgeColor
                        else -> faint
                    }
                    // Edge width is in screen pixels, not world units: a line
                    // scaled by the zoom turns into a slab when the user zooms
                    // in, which is harmless on its own but made the highlighted
                    // edges dominate the canvas.
                    val width = when {
                        touchesSelection -> 2.4f
                        cross -> 1.3f
                        else -> 0.7f
                    }
                    drawLine(color, toScreen(a), toScreen(b), strokeWidth = width, cap = StrokeCap.Round)
                }

                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = labelArgb
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val haloPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = labelHaloArgb
                    textAlign = android.graphics.Paint.Align.CENTER
                    style = android.graphics.Paint.Style.STROKE
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }

                val hasSelection = selectedNode != null || focusedCommunity >= 0
                // Label budget: how many nodes may carry a name at this zoom.
                //
                // The old `8 * scale²` was calibrated against nothing: at two
                // neighbours' worth of zoom it labelled hundreds of nodes into
                // a canvas that had room for tens, and the collision check then
                // did the real work -- expensively, and in an arbitrary order.
                // Budgeting from the *viewport area* instead means the number
                // of labels tracks how much room there actually is, and the
                // collision check only has to settle ties.
                val eligible = geometry.nodes.values.count { !it.isFriend || it.mutualCount > 0 }
                val room = (size.width * size.height / LABEL_AREA_PX)
                    .toInt()
                    .coerceIn(MIN_LABELS, eligible.coerceAtMost(MAX_LABELS))
                val budget = (room * scale * scale).toInt().coerceIn(MIN_LABELS, MAX_LABELS)

                for ((_, node) in geometry.nodes) {
                    val p = toScreen(Offset(node.x, node.y))
                    if (p.x < -60 || p.y < -60 || p.x > size.width + 60 || p.y > size.height + 60) continue
                    val isSel = node.id == selectedNode
                    val isNb = node.id in neighbours
                    val inFocusedCommunity = node.id in focusedCommunityIds
                    val isRelated = isSel || isNb || inFocusedCommunity
                    val isFaded = hasSelection && !isRelated

                    // Drawn radius. Screen-space, clamped against the label
                    // size -- see nodeScreenRadius for why that clamp is the
                    // whole point of this change.
                    val textPx = (if (isSel) LABEL_SELECTED_SP else LABEL_SP).sp.toPx()
                    val r = nodeScreenRadius(
                        isFriend = node.isFriend,
                        mutualCount = node.mutualCount,
                        maxMutualCount = geometry.maxMutualCount,
                        scale = scale,
                        textPx = textPx,
                        selected = isSel,
                        faded = isFaded
                    )

                    val base = communityFill(node.community)
                    val fill = when {
                        isSel -> selectedFill
                        isNb -> neighbourFill
                        inFocusedCommunity -> base
                        isFaded -> NEUTRAL_COMMUNITY_COLOR.copy(alpha = 0.18f)
                        else -> base
                    }
                    drawCircle(fill, radius = r, center = p)

                    // Emphasis is carried by the halo, not by size. The old
                    // code multiplied the radius by 1.6/1.25, which on a 3 px
                    // dot is a difference of a couple of pixels -- invisible.
                    // A ring reads at any size.
                    when {
                        isSel -> {
                            drawCircle(
                                fill.copy(alpha = 0.22f),
                                radius = r + SELECTED_HALO_PX,
                                center = p
                            )
                            drawCircle(
                                selectedRing,
                                radius = r + 2.5f,
                                center = p,
                                style = Stroke(width = 2f)
                            )
                        }
                        isNb || inFocusedCommunity ->
                            drawCircle(
                                neighbourRing.copy(alpha = 0.85f),
                                radius = r + 2f,
                                center = p,
                                style = Stroke(width = 1.4f)
                            )
                    }

                    // Label density + collision, the way sigma.js does it:
                    // zoomed out only the most-connected nodes are labelled,
                    // and labels are dropped if they would overlap an already-
                    // placed one. The selected node and its neighbours bypass
                    // both the budget AND the collision check: they are the
                    // relationship the user just asked to see, so they must
                    // stay labelled (and bold) even when dense.
                    val rank = geometry.labelRank[node.id] ?: Int.MAX_VALUE
                    val isKey = isSel || isNb || inFocusedCommunity
                    val show = isKey || rank < budget
                    if (show) {
                        val sizeSp = when {
                            isSel -> LABEL_SELECTED_SP
                            isKey -> LABEL_KEY_SP
                            else -> LABEL_SP
                        }
                        paint.textSize = sizeSp.sp.toPx()
                        paint.typeface = if (isKey)
                            android.graphics.Typeface.create(
                                android.graphics.Typeface.DEFAULT,
                                android.graphics.Typeface.BOLD
                            )
                        else android.graphics.Typeface.DEFAULT
                        val textWidth = paint.measureText(node.name)
                        // Labels sit above the dot, offset by the dot's own
                        // screen radius so they never overlap it.
                        val baseline = p.y - r - LABEL_GAP_PX
                        val rect = androidx.compose.ui.geometry.Rect(
                            left = p.x - textWidth / 2f,
                            top = baseline - paint.textSize,
                            right = p.x + textWidth / 2f,
                            bottom = baseline + paint.descent()
                        )
                        // Key nodes bypass the collision check so they are
                        // always readable; ordinary labels still thin out.
                        if (isKey || !labelBlocked(rect, labelRects)) {
                            labelRects.add(rect)
                            // Halo first: a 2 px stroke in the surface colour,
                            // so a name stays readable where it crosses an edge
                            // or another node.
                            haloPaint.textSize = paint.textSize
                            haloPaint.typeface = paint.typeface
                            haloPaint.strokeWidth = LABEL_HALO_PX
                            drawContext.canvas.nativeCanvas.drawText(node.name, p.x, baseline, haloPaint)
                            drawContext.canvas.nativeCanvas.drawText(node.name, p.x, baseline, paint)
                        }
                    }
                }
            }
        }

        // selected node card: who it is and a path to the full profile
        selected?.let { node ->
            val person = people[node.id]
            val record = roster?.friends?.get(node.id)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The face behind the dot. Without it the card named someone
                    // the user had no way to recognise.
                    RemoteAvatar(
                        services = services,
                        rawUrl = person?.imageUrl
                            ?: record?.iconUrl?.takeIf { it.isNotBlank() }
                            ?: record?.currentAvatarThumbnailImageUrl,
                        name = node.name.takeIf { it != PENDING_NAME },
                        size = 44.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = when {
                            node.optedOut -> "不可用"
                            node.isFriend -> "共 " + node.mutualCount + " 位共同好友" +
                                node.lastFetchedAt.let {
                                    if (it.isNotBlank()) " · 更新于 " + shortTime(it) else ""
                                }
                            else -> "共同好友".tr()
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (node.optedOut)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { onOpenProfile(node.id) }) {
                        Text("查看资料".tr())
                    }
                }
            }
        }

        // Legend: named communities with a colour swatch + anchor name + size.
        // Only the top named communities are shown (isolated/neutral nodes are
        // the grey remainder and are not listed individually). Tapping a legend
        // entry focuses that community: its nodes stay lit, the rest recede.
        //
        // Scrollable horizontally: six entries at six names each overflowed a
        // phone's width, and a clipped legend is worse than a scrollable one
        // because the hidden entries look like they do not exist.
        val legendCommunities = geometry.communities
            .filter { it.size >= MutualFriendsCommunities.MIN_NAMED_COMMUNITY_SIZE }
            .take(NAMED_COMMUNITY_LIMIT)
        if (legendCommunities.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                legendCommunities.forEach { community ->
                    val swatch = COMMUNITY_PALETTE[community.index % COMMUNITY_PALETTE.size]
                    val isFocused = focusedCommunity == community.index
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable {
                                if (isFocused) {
                                    // Toggle off: clear focus + selection.
                                    focusedCommunity = -1
                                    onSelect(null)
                                } else {
                                    focusedCommunity = community.index
                                    onSelect(community.anchorId)
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Box(
                            Modifier
                                .width(10.dp)
                                .height(10.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(swatch)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = community.anchorName(geometry).tr() + "·" + community.size,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                            color = if (isFocused)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildString {
                    append("共 ").append(geometry.nodes.size).append(" 人 · ")
                        .append(geometry.edges.size).append(" 条连接")
                    // Coverage: total friends vs those whose mutuals have been
                    // fetched. `snap.meta` only holds rows for friends that WERE
                    // fetched, so the denominator is `snap.friendIds` (the full
                    // roster), not meta.size -- an unfetched friend has no meta
                    // row at all and would otherwise be miscounted as "present".
                    val total = snap.friendIds.size
                    if (total > 0) {
                        val fetched = snap.meta.count { it.lastFetchedAt.isNotBlank() }
                        val pending = (total - fetched).coerceAtLeast(0)
                        append(" · 已拉取 ").append(fetched).append("/").append(total)
                        if (pending > 0) append("（未拉取 ").append(pending).append("）")
                    }
                    // Where this graph came from, since it is no longer
                    // necessarily from this session.
                    if (cachedAt > 0) {
                        append(" · 本地缓存 ").append(cachedLabel(cachedAt))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            AssistChip(
                onClick = { scale = DEFAULT_SCALE; offset = Offset.Zero },
                label = { Text("重置视图".tr()) }
            )
            AssistChip(onClick = onStartFetch, label = { Text("更新".tr()) })
        }
    }
}

// ---------------------------------------------------------------- list view

/**
 * The per-friend list: one row per friend, expandable into the people you both
 * know.
 *
 * Every row shows the person's avatar. That is not decoration -- the list is a
 * wall of names a user may not recognise, and the graph is the one screen where
 * a face is the fastest way to identify someone. [RemoteAvatar] handles the
 * three cases that matter: a resolved photo, an in-flight fetch (initial
 * letter), and a person with no picture at all (also an initial letter, in a
 * stable per-person colour), so a row is never a blank hole.
 */
@Composable
private fun MutualGraphList(
    services: SessionServices?,
    snap: MutualGraphSnapshotOutput,
    people: Map<String, PersonSummary>,
    roster: FriendsRoster?,
    cachedAt: Long,
    onStartFetch: () -> Unit,
    expanded: String?,
    onExpand: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val byFriend = remember(snap) {
        snap.links.groupBy({ it.friendId }, { it.mutualId })
    }
    val rows = remember(byFriend, snap) {
        snap.friendIds.sortedByDescending { (byFriend[it] ?: emptyList()).size }
    }

    // A friend's name and face are already in the roster in the common case, so
    // the first pass is free. This asks the server only for whatever the roster
    // did not cover -- and, in particular, for the *mutuals*, which exist
    // nowhere else on the device.
    LaunchedEffect(snap, roster) {
        val known = roster?.friends?.keys.orEmpty()
        val wanted = buildList {
            addAll(snap.links.map { it.mutualId })
            addAll(snap.friendIds.filter { it !in known })
        }
        if (wanted.isNotEmpty()) services?.mutualPeople?.ensure(wanted)
    }

    if (rows.isEmpty()) {
        EmptyGraphState(
            title = "还没有共同好友数据".tr(),
            body = "拉取一次后，这里会按好友列出你们共同认识的人。".tr(),
            onStartFetch = onStartFetch,
            modifier = modifier
        )
        return
    }

    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
        // Provenance first: this list may be from days ago, and a stale graph
        // presented as current is worse than one honestly labelled.
        if (cachedAt > 0) {
            item(key = "cached-at") {
                Text(
                    text = "以下为本地缓存（" + cachedLabel(cachedAt) + "）".tr(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        items(rows, key = { it }) { friendId ->
            val record = roster?.friends?.get(friendId)
            val person = people[friendId]
            val name = record?.displayName?.takeIf { it.isNotBlank() }
                ?: person?.displayName?.takeIf { it.isNotBlank() }
            val avatarUrl = person?.imageUrl
                ?: record?.iconUrl?.takeIf { it.isNotBlank() }
                ?: record?.currentAvatarThumbnailImageUrl
            val mutuals = byFriend[friendId].orEmpty()
            val isExpanded = expanded == friendId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 3.dp),
                onClick = { onExpand(friendId) },
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RemoteAvatar(
                            services = services,
                            rawUrl = avatarUrl,
                            name = name,
                            size = LIST_AVATAR_DP.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = name ?: PENDING_NAME,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = mutuals.size.toString() + " 位共同好友",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isExpanded) "▾" else "▸",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ExpandableSection(expanded = isExpanded) {
                        Column(Modifier.padding(top = 4.dp)) {
                            if (mutuals.isEmpty()) {
                                Text(
                                    "该好友已退出关系图共享".tr(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                mutuals.forEach { mutualId ->
                                    val mutual = people[mutualId]
                                    PersonRow(
                                        services = services,
                                        name = mutual?.displayName?.takeIf { it.isNotBlank() },
                                        avatarUrl = mutual?.imageUrl,
                                        onClick = { onOpenProfile(mutualId) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One named person inside an expanded row: avatar, name, tap to open.
 *
 * The avatar matters most here. An expanded row is the moment the user is
 * asking "who are these people", and a list of bare names answers that question
 * badly.
 */
@Composable
private fun PersonRow(
    services: SessionServices?,
    name: String?,
    avatarUrl: String?,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 44 dp minimum touch target, per the accessibility floor. The old
            // row was a bare Text with 4 dp of padding -- around 24 dp tall,
            // well under a reliable tap.
            .heightIn(min = 44.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        RemoteAvatar(
            services = services,
            rawUrl = avatarUrl,
            name = name,
            size = PERSON_AVATAR_DP.dp
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = name ?: PENDING_NAME,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** The "nothing to show" state, with a way out of it. */
@Composable
private fun EmptyGraphState(
    title: String,
    body: String,
    onStartFetch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onStartFetch) { Text("开始拉取".tr()) }
    }
}

/** "3 分钟前" describing how old the stored snapshot is. */
private fun cachedLabel(savedAt: Long): String {
    val minutes = ((System.currentTimeMillis() - savedAt) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> "刚刚"
        minutes < 60L -> minutes.toString() + " 分钟前"
        minutes < 60L * 24 -> (minutes / 60L).toString() + " 小时前"
        else -> (minutes / (60L * 24)).toString() + " 天前"
    }
}

/** The two ways to read the graph: a per-friend list and the radial canvas. */
enum class GraphView { LIST, GRAPH }


/**
 * Fruchterman-Reingold relaxation, grid-hashed so repulsion only considers
 * near neighbours. Pure and deterministic: the same snapshot always produces
 * the same layout, which is what keeps the graph from re-shuffling itself.
 */
internal fun forceRelax(
    ids: List<String>,
    initial: Map<String, Offset>,
    edges: List<Pair<String, String>>,
    iterations: Int = 150
): Map<String, Offset> {
    if (ids.isEmpty()) return emptyMap()
    val positions = HashMap<String, Offset>(ids.size)
    val centre = WORLD_SIZE / 2f
    for (id in ids) positions[id] = initial[id] ?: Offset(centre, centre)

    // Ideal edge length scales with node count so sparse and dense graphs both
    // end up readable inside the 0..WORLD_SIZE square. Boosted (1500 vs 1100)
    // and the repulsion coefficient (1.4x) so clusters breathe instead of
    // collapsing into a dense blob whose labels overlap.
    val k = 1500f / sqrt(maxOf(1f, ids.size.toFloat()))
    val cell = k
    val buckets = HashMap<Long, MutableList<String>>()
    fun bucketKey(x: Float, y: Float): Long =
        (x.toLong() / cell.toLong() shl 32) or (y.toLong() / cell.toLong() and 0xFFFFFFFFL)

    val displacement = HashMap<String, Offset>(ids.size)
    val cooling = 9f / iterations

    for (iteration in 0 until iterations) {
        val maxStep = (10f - iteration * cooling).coerceAtLeast(1.5f)
        displacement.clear()
        buckets.clear()
        for ((id, pos) in positions) {
            buckets.getOrPut(bucketKey(pos.x, pos.y)) { mutableListOf() }.add(id)
        }

        // Repulsion: only against nodes in the same or neighbouring cells.
        for ((id, pos) in positions) {
            var fx = 0f
            var fy = 0f
            val cx = (pos.x / cell).toInt()
            val cy = (pos.y / cell).toInt()
            for (gx in cx - 1..cx + 1) {
                for (gy in cy - 1..cy + 1) {
                    val bucket = buckets.getOrDefault(
                        (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL),
                        emptyList()
                    )
                    for (other in bucket) {
                        if (other == id) continue
                        val otherPos = positions.getValue(other)
                        var dx = pos.x - otherPos.x
                        var dy = pos.y - otherPos.y
                        var dist = sqrt(dx * dx + dy * dy)
                        if (dist < 0.01f) {
                            // Perfect overlap: nudge apart deterministically.
                            dx = ((id.hashCode() % 7) - 3) * 0.5f + 0.1f
                            dy = ((other.hashCode() % 7) - 3) * 0.5f + 0.1f
                            dist = sqrt(dx * dx + dy * dy)
                        }
                        val force = k * k / dist
                        fx += dx / dist * force
                        fy += dy / dist * force
                    }
                }
            }
            displacement[id] = Offset(fx, fy)
        }

        // Attraction: springs along edges, proportional to distance (FA2-style).
        for ((a, b) in edges) {
            val pa = positions.getValue(a)
            val pb = positions.getValue(b)
            val dx = pb.x - pa.x
            val dy = pb.y - pa.y
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
            val force = dist * dist / k
            displacement[a] = (displacement[a] ?: Offset.Zero) +
                Offset(dx / dist * force, dy / dist * force)
            displacement[b] = (displacement[b] ?: Offset.Zero) -
                Offset(dx / dist * force, dy / dist * force)
        }

        // Apply, capped and cooled, with a weak pull toward the centre.
        for ((id, pos) in positions) {
            val d = displacement.getValue(id)
            val magnitude = sqrt(d.x * d.x + d.y * d.y)
            val capped = if (magnitude > maxStep) {
                Offset(d.x / magnitude * maxStep, d.y / magnitude * maxStep)
            } else {
                d
            }
            val next = pos + capped + Offset(centre - pos.x, centre - pos.y) * 0.002f
            positions[id] = Offset(
                next.x.coerceIn(20f, WORLD_SIZE - 20f),
                next.y.coerceIn(20f, WORLD_SIZE - 20f)
            )
        }
    }
    return positions
}
