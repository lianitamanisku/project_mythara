package com.mythara.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.analytics.ContactGraphBuilder
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Insights — a force-directed, EGO-CENTRIC relationship graph. The
 * "you" node sits pinned at the centre and every contact hangs off it;
 * extra edges layer in the structure between contacts:
 *  - Malibu  : RELATES (you → contact; label = relationship type)
 *  - Charple : KNOWS (Gemma-inferred from the user's notes + summaries)
 *  - Bok     : SIMILAR (close Big Five personalities)
 *  - Mustard : SHARED_TOPIC (overlapping conversation topics)
 *
 * The canvas pans (drag) and zooms (pinch); tapping a node opens a
 * detail panel listing that person's relationships. The cheap
 * (arithmetic) edges + heuristic RELATES labels render instantly; the
 * Gemma pass refines the RELATES labels and folds in KNOWS a moment
 * later.
 */
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val builder: ContactGraphBuilder,
) : ViewModel() {

    data class Ui(
        val loading: Boolean = true,
        val enriching: Boolean = false,
        val graph: ContactGraphBuilder.Graph =
            ContactGraphBuilder.Graph(emptyList(), emptyList(), gemmaUsed = false),
        /** Normalised [0,1] node positions, keyed by node key. */
        val positions: Map<String, Offset> = emptyMap(),
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, enriching = false)
            // Phase 1 — cheap graph (arithmetic edges only), instant.
            val cheap = runCatching { builder.buildCheap() }.getOrNull()
            if (cheap != null) {
                val capped = cap(cheap)
                _ui.value = Ui(
                    loading = false,
                    enriching = true,
                    graph = capped,
                    positions = withContext(Dispatchers.Default) { layout(capped) },
                )
            }
            // Phase 2 — fold in the Gemma KNOWS edges.
            val full = runCatching { builder.buildFull() }.getOrNull()
            if (full != null) {
                val capped = cap(full)
                _ui.value = Ui(
                    loading = false,
                    enriching = false,
                    graph = capped,
                    positions = withContext(Dispatchers.Default) { layout(capped) },
                )
            } else {
                _ui.value = _ui.value.copy(loading = false, enriching = false)
            }
        }
    }

    /** Keep the graph snappy: cap to the most-relevant nodes, drop
     *  edges that reference a culled node. The ME hub is always kept. */
    private fun cap(g: ContactGraphBuilder.Graph): ContactGraphBuilder.Graph {
        if (g.nodes.size <= MAX_NODES) return g
        val kept = g.nodes
            .sortedWith(
                compareByDescending<ContactGraphBuilder.Node> { it.key == ContactGraphBuilder.ME_KEY }
                    .thenByDescending { it.isFavorite }
                    .thenByDescending { it.messageCount },
            )
            .take(MAX_NODES)
        val keptKeys = kept.map { it.key }.toHashSet()
        return ContactGraphBuilder.Graph(
            nodes = kept,
            edges = g.edges.filter { it.fromKey in keptKeys && it.toKey in keptKeys },
            gemmaUsed = g.gemmaUsed,
        )
    }

    /**
     * Force-directed layout with hard overlap resolution.
     *
     * Each iteration runs two passes:
     *  1. Fruchterman-Reingold forces — repulsion (k²/d) between every
     *     node pair, attraction (d²/k) along edges, cooled over time.
     *  2. A SEQUENTIAL overlap-resolution pass — for every pair closer
     *     than [minSep], push both apart. This is applied pairwise in
     *     order (not summed into a displacement vector), which is the
     *     key fix: summed repulsion cancels for a node surrounded
     *     symmetrically inside a clique, so cliques collapse to a
     *     point. Sequential separation can't cancel — it always pries
     *     overlapping nodes apart.
     *
     * Result is normalised into a [0.08, 0.92] box so the canvas maps
     * it straight to pixels.
     */
    private fun layout(g: ContactGraphBuilder.Graph): Map<String, Offset> {
        val keys = g.nodes.map { it.key }
        if (keys.isEmpty()) return emptyMap()
        if (keys.size == 1) return mapOf(keys[0] to Offset(0.5f, 0.5f))

        val rnd = Random(42)
        val pos = HashMap<String, Offset>(keys.size)
        // Seed randomly inside a disc.
        for (key in keys) {
            val ang = rnd.nextFloat() * 6.2831855f
            val rad = 0.05f + rnd.nextFloat() * 0.4f
            pos[key] = Offset(0.5f + rad * cos(ang), 0.5f + rad * sin(ang))
        }
        // The ego-graph hub is pinned dead-centre and never moves — it
        // still exerts spring + repulsion forces on the contacts, it
        // just isn't displaced by them.
        val me = ContactGraphBuilder.ME_KEY
        if (me in pos) pos[me] = Offset(0.5f, 0.5f)
        // Unordered edge pairs (collapse multi-kind edges to one spring).
        val springs = g.edges.map {
            if (it.fromKey <= it.toKey) it.fromKey to it.toKey else it.toKey to it.fromKey
        }.distinct().filter { it.first in pos && it.second in pos }

        // Ideal edge length — scales down as the graph grows.
        val k = (0.95f / sqrt(keys.size.toFloat())).coerceIn(0.08f, 0.42f)
        val kSq = k * k
        val minSep = k * 0.62f

        // One sequential overlap-resolution sweep — pull every pair
        // closer than minSep apart. Sequential (not summed) so it
        // can't cancel out for a node trapped inside a symmetric clump.
        fun resolveOverlaps() {
            for (i in keys.indices) {
                for (j in i + 1 until keys.size) {
                    val a = keys[i]
                    val b = keys[j]
                    var delta = pos[a]!! - pos[b]!!
                    var dist = delta.getDistance()
                    if (dist >= minSep) continue
                    if (dist < 1e-4f) {
                        delta = Offset(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f)
                        dist = delta.getDistance().coerceAtLeast(1e-4f)
                    }
                    val dir = delta / dist
                    // ME is pinned — push the full separation onto the
                    // other node rather than splitting it.
                    when (me) {
                        a -> pos[b] = pos[b]!! - dir * (minSep - dist)
                        b -> pos[a] = pos[a]!! + dir * (minSep - dist)
                        else -> {
                            val push = (minSep - dist) / 2f
                            pos[a] = pos[a]!! + dir * push
                            pos[b] = pos[b]!! - dir * push
                        }
                    }
                }
            }
        }

        repeat(ITERATIONS) { iter ->
            val progress = iter.toFloat() / ITERATIONS
            // --- pass 1: FR forces ---
            val disp = HashMap<String, Offset>(keys.size)
            for (key in keys) disp[key] = Offset.Zero
            for (i in keys.indices) {
                for (j in i + 1 until keys.size) {
                    val a = keys[i]
                    val b = keys[j]
                    var delta = pos[a]!! - pos[b]!!
                    var dist = delta.getDistance()
                    if (dist < 1e-4f) {
                        delta = Offset(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f)
                        dist = delta.getDistance().coerceAtLeast(1e-4f)
                    }
                    val dir = delta / dist
                    val force = kSq / dist
                    disp[a] = disp[a]!! + dir * force
                    disp[b] = disp[b]!! - dir * force
                }
            }
            for ((a, b) in springs) {
                val delta = pos[a]!! - pos[b]!!
                val dist = delta.getDistance().coerceAtLeast(1e-4f)
                val dir = delta / dist
                val force = dist * dist / k
                disp[a] = disp[a]!! - dir * force
                disp[b] = disp[b]!! + dir * force
            }
            val temp = (1f - progress) * 0.14f + 0.004f
            for (key in keys) {
                if (key == me) continue // pinned hub
                val d = disp[key]!!
                val len = d.getDistance().coerceAtLeast(1e-4f)
                pos[key] = pos[key]!! + d / len * minOf(len, temp)
            }
            // --- pass 2: keep clumps loose as forces run ---
            resolveOverlaps()
        }
        // Normalise into a [0.08, 0.92] box FIRST — isolated nodes blow
        // the raw bounding box wide, so normalising squeezes everything
        // back down, which would re-overlap any clump.
        run {
            val xs = pos.values.map { it.x }
            val ys = pos.values.map { it.y }
            val minX = xs.min()
            val minY = ys.min()
            val spanX = (xs.max() - minX).coerceAtLeast(0.0005f)
            val spanY = (ys.max() - minY).coerceAtLeast(0.0005f)
            for (key in keys) {
                val p = pos[key]!!
                pos[key] = Offset(
                    0.08f + (p.x - minX) / spanX * 0.84f,
                    0.08f + (p.y - minY) / spanY * 0.84f,
                )
            }
        }
        // Normalisation shifted the hub off-centre — snap it back.
        if (me in pos) pos[me] = Offset(0.5f, 0.5f)
        // THEN run pure separation to convergence, in the final
        // coordinate space, clamping back into the box each sweep. This
        // is the pass that actually guarantees no two nodes overlap on
        // screen — it can't be undone by a later squeeze.
        repeat(OVERLAP_SWEEPS) {
            resolveOverlaps()
            for (key in keys) {
                val p = pos[key]!!
                pos[key] = Offset(p.x.coerceIn(0.04f, 0.96f), p.y.coerceIn(0.04f, 0.96f))
            }
        }
        return pos.toMap()
    }

    companion object {
        private const val MAX_NODES = 80
        private const val ITERATIONS = 400
        private const val OVERLAP_SWEEPS = 150
    }
}

@Composable
fun InsightsScreen(
    onBack: () -> Unit,
    vm: InsightsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val textMeasurer = rememberTextMeasurer()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        // ---- header ------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "${Glyph.DiamondFilled} insights",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            if (ui.enriching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MytharaColors.Bok,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = { pan = Offset.Zero; zoom = 1f }) {
                Text("reset view", color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { selectedKey = null; vm.refresh() }) {
                Text("${Glyph.Refresh} rebuild", color = MytharaColors.Charple, style = MaterialTheme.typography.bodySmall)
            }
        }

        Legend()

        // ---- graph canvas -----------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                ui.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MytharaColors.Charple)
                    }
                }
                ui.graph.nodes.size < 2 -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "${Glyph.CircleOutline} not enough people yet — Mythara needs a few " +
                                "contacts with notes or learned profiles before it can map relationships.",
                            color = MytharaColors.FgDim,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }
                else -> {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, panChange, zoomChange, _ ->
                                    zoom = (zoom * zoomChange).coerceIn(0.4f, 4f)
                                    pan += panChange
                                }
                            }
                            .pointerInput(ui.graph.nodes, ui.positions, zoom, pan) {
                                detectTapGestures { tap ->
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    var best: String? = null
                                    var bestDist = Float.MAX_VALUE
                                    for (node in ui.graph.nodes) {
                                        val n = ui.positions[node.key] ?: continue
                                        val p = Offset(n.x * w, n.y * h) * zoom + pan
                                        val d = (p - tap).getDistance()
                                        val r = node.baseRadius() * zoom + 14f
                                        if (d < r && d < bestDist) {
                                            bestDist = d
                                            best = node.key
                                        }
                                    }
                                    selectedKey = best
                                }
                            },
                    ) {
                        val w = size.width
                        val h = size.height
                        fun screen(n: Offset) = Offset(n.x * w, n.y * h) * zoom + pan

                        val sel = selectedKey
                        // edges
                        for (edge in ui.graph.edges) {
                            val a = ui.positions[edge.fromKey] ?: continue
                            val b = ui.positions[edge.toKey] ?: continue
                            val pa = screen(a)
                            val pb = screen(b)
                            val touches = sel == null || sel == edge.fromKey || sel == edge.toKey
                            val baseAlpha = 0.22f + edge.weight * 0.5f
                            val alpha = if (touches) baseAlpha else baseAlpha * 0.16f
                            val color = edgeColor(edge.kind).copy(alpha = alpha)
                            val stroke = (1f + edge.weight * 3f) * zoom
                            drawLine(color, pa, pb, strokeWidth = stroke)
                            // arrowhead at ~82% toward the target — keeps it
                            // off the node circle while still showing direction
                            val tip = pa + (pb - pa) * 0.82f
                            val dir = (pb - pa)
                            val len = dir.getDistance().coerceAtLeast(0.001f)
                            val u = dir / len
                            val left = Offset(-u.y, u.x)
                            val barb = 7f * zoom
                            drawLine(color, tip, tip - u * barb + left * barb * 0.6f, strokeWidth = stroke)
                            drawLine(color, tip, tip - u * barb - left * barb * 0.6f, strokeWidth = stroke)
                        }
                        // nodes
                        for (node in ui.graph.nodes) {
                            val n = ui.positions[node.key] ?: continue
                            val p = screen(n)
                            val r = node.baseRadius() * zoom
                            val isMe = node.key == ContactGraphBuilder.ME_KEY
                            val isSel = node.key == sel
                            val dim = sel != null && !isSel && !isMe &&
                                ui.graph.edges.none {
                                    (it.fromKey == sel && it.toKey == node.key) ||
                                        (it.toKey == sel && it.fromKey == node.key)
                                }
                            val fill = when {
                                isMe -> MytharaColors.Malibu
                                node.isFavorite -> MytharaColors.Charple
                                else -> MytharaColors.SurfaceHigh
                            }.copy(alpha = if (dim) 0.3f else 1f)
                            drawCircle(MytharaColors.Bg, r + 3f * zoom, p)
                            drawCircle(fill, r, p)
                            if (isMe) {
                                drawCircle(MytharaColors.Fg, r, p, style = Stroke(2f * zoom))
                            }
                            if (node.hasNotes) {
                                drawCircle(MytharaColors.Bok, r, p, style = Stroke(1.5f * zoom))
                            }
                            if (isSel) {
                                drawCircle(MytharaColors.Bok, r + 4f * zoom, p, style = Stroke(2.5f * zoom))
                            }
                            // label
                            val labelStyle = TextStyle(
                                color = if (dim) MytharaColors.FgDim else MytharaColors.Fg,
                                fontSize = (9f * zoom).coerceIn(7f, 13f).sp,
                            )
                            val measured = textMeasurer.measure(node.name.take(16), labelStyle)
                            drawText(
                                measured,
                                topLeft = Offset(
                                    p.x - measured.size.width / 2f,
                                    p.y + r + 2f * zoom,
                                ),
                            )
                        }
                    }
                }
            }
        }

        // ---- detail panel -----------------------------------------
        val selNode = selectedKey?.let { key -> ui.graph.nodes.firstOrNull { it.key == key } }
        if (selNode != null) {
            NodeDetailPanel(
                node = selNode,
                edges = ui.graph.edges.filter {
                    it.fromKey == selNode.key || it.toKey == selNode.key
                },
                nameOf = { k -> ui.graph.nodes.firstOrNull { it.key == k }?.name ?: k },
                onClose = { selectedKey = null },
            )
        }
    }
}

private const val NODE_MIN_R = 9f
private const val NODE_GROW = 15f

private fun nodeRadius(messageCount: Int): Float =
    NODE_MIN_R + (messageCount.coerceAtMost(50) / 50f) * NODE_GROW

/** Un-zoomed draw radius — the ME hub is drawn larger than any contact. */
private fun ContactGraphBuilder.Node.baseRadius(): Float =
    nodeRadius(messageCount) * (if (key == ContactGraphBuilder.ME_KEY) 1.7f else 1f)

private fun edgeColor(kind: ContactGraphBuilder.EdgeKind): Color = when (kind) {
    ContactGraphBuilder.EdgeKind.RELATES -> MytharaColors.Malibu
    ContactGraphBuilder.EdgeKind.KNOWS -> MytharaColors.Charple
    ContactGraphBuilder.EdgeKind.SIMILAR -> MytharaColors.Bok
    ContactGraphBuilder.EdgeKind.SHARED_TOPIC -> MytharaColors.Mustard
}

private fun edgeKindLabel(kind: ContactGraphBuilder.EdgeKind): String = when (kind) {
    ContactGraphBuilder.EdgeKind.RELATES -> "relationship"
    ContactGraphBuilder.EdgeKind.KNOWS -> "knows"
    ContactGraphBuilder.EdgeKind.SIMILAR -> "similar"
    ContactGraphBuilder.EdgeKind.SHARED_TOPIC -> "shared topics"
}

@Composable
private fun Legend() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(MytharaColors.Malibu, "relationship")
        LegendDot(MytharaColors.Charple, "knows")
        LegendDot(MytharaColors.Bok, "similar")
        LegendDot(MytharaColors.Mustard, "topics")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(label, color = MytharaColors.FgDim, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NodeDetailPanel(
    node: ContactGraphBuilder.Node,
    edges: List<ContactGraphBuilder.Edge>,
    nameOf: (String) -> String,
    onClose: () -> Unit,
) {
    val isMe = node.key == ContactGraphBuilder.ME_KEY
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .padding(12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, if (isMe) MytharaColors.Malibu else MytharaColors.Charple, RoundedCornerShape(12.dp))
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = node.name,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (node.isFavorite) {
                    Text(
                        text = "  ${Glyph.DiamondFilled}",
                        color = MytharaColors.Charple,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = onClose) {
                Text("${Glyph.Cross} close", color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(
            text = if (isMe) {
                "${edges.size} connections"
            } else {
                "${node.messageCount} interactions" +
                    (if (node.topics.isNotEmpty()) " · ${node.topics.take(4).joinToString(", ")}" else "")
            },
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )

        node.summary?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "${Glyph.DiamondOutline} relationships (${edges.size})",
            color = MytharaColors.FgMute,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(4.dp))
        if (edges.isEmpty()) {
            Text(
                "No derived relationships yet — add notes on this person, or refresh after more conversations.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            edges.sortedByDescending { it.weight }.forEach { edge ->
                val otherKey = if (edge.fromKey == node.key) edge.toKey else edge.fromKey
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(edgeColor(edge.kind)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "${Glyph.Arrow} ${nameOf(otherKey)}",
                            color = MytharaColors.Fg,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${edgeKindLabel(edge.kind)} · ${edge.label}",
                            color = MytharaColors.FgDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
