package com.vrcx0.android.ui.screens

/**
 * Louvain community detection, ported from the desktop app's
 * `mutualFriendsCommunities.ts` (which wraps `graphology-communities-louvain`).
 *
 * The desktop graph is undirected and unweighted; degrees drive the modularity
 * gain. The port keeps the same seeded PRNG and greedy local-move loop so the
 * result is deterministic: the same friend graph always yields the same
 * community assignment (the graph must not rearrange on every recomposition).
 *
 * Pure arithmetic with no Android deps, so it is directly unit-testable on the
 * JVM.
 */
object MutualFriendsCommunities {

    const val SEED = 0x9e3779b9.toInt()
    const val MIN_NAMED_COMMUNITY_SIZE = 2

    /** A single detected community, ready for the palette + legend. */
    data class Community(
        val index: Int,
        val memberIds: List<String>,
        val anchorId: String
    ) {
        val size: Int get() = memberIds.size
    }

    /** Result: every node's community index plus the ranked community list. */
    data class Assignment(
        val communityIndexById: Map<String, Int>,
        val communities: List<Community>
    )

    /**
     * Runs Louvain over an undirected graph and returns ranked communities.
     *
     * @param nodeIds all node ids (order preserved for determinism)
     * @param degreeById degree per node (already computed by the caller)
     * @param edges undirected edges (each unique pair once)
     */
    fun assign(
        nodeIds: List<String>,
        degreeById: Map<String, Int>,
        edges: List<Pair<String, String>>
    ): Assignment {
        if (nodeIds.isEmpty()) return Assignment(emptyMap(), emptyList())

        val raw = louvain(nodeIds, edges)
        val members = LinkedHashMap<String, MutableList<String>>()
        for (id in nodeIds) {
            val bucket = raw[id] ?: "isolated"
            members.getOrPut(bucket) { mutableListOf() }.add(id)
        }

        // Rank communities by size desc, tie-break by bucket id for stability.
        val ranked = members.entries.sortedWith(
            compareByDescending<Map.Entry<String, MutableList<String>>> { it.value.size }
                .thenBy { it.key }
        )

        val communityIndexById = HashMap<String, Int>(nodeIds.size)
        val communities = ArrayList<Community>(ranked.size)
        ranked.forEachIndexed { index, (_, memberIds) ->
            for (memberId in memberIds) communityIndexById[memberId] = index
            // Anchor: the highest-degree member names the community.
            val anchor = memberIds.maxByOrNull { degreeById[it] ?: 0 } ?: memberIds.first()
            communities.add(Community(index = index, memberIds = memberIds, anchorId = anchor))
        }
        return Assignment(communityIndexById, communities)
    }

    /**
     * Greedy modularity maximisation (Louvain's first pass, no coarsening).
     *
     * Each node starts in its own community and repeatedly moves to the
     * neighbour community that most improves modularity, until no move helps.
     * The seeded PRNG shuffles iteration order deterministically rather than
     * adding randomness to the result.
     */
    private fun louvain(
        nodeIds: List<String>,
        edges: List<Pair<String, String>>
    ): Map<String, String> {
        val neighbourIds = HashMap<String, MutableList<String>>(nodeIds.size)
        for (id in nodeIds) neighbourIds[id] = mutableListOf()
        for ((a, b) in edges) {
            neighbourIds[a]?.add(b)
            neighbourIds[b]?.add(a)
        }

        // community id per node, init to self
        val community = HashMap<String, String>(nodeIds.size)
        for (id in nodeIds) community[id] = id

        // members per community (community id -> set of node ids)
        val members = HashMap<String, MutableSet<String>>(nodeIds.size)
        for (id in nodeIds) members[id] = mutableSetOf(id)
        // sum of degrees per community (for the modularity ΔQ denominator term)
        val degreeSum = HashMap<String, Int>(nodeIds.size)
        for (id in nodeIds) degreeSum[id] = neighbourIds[id]?.size ?: 0

        val m = edges.size.toDouble()
        val random = SeededRandom(SEED)

        var moved = true
        var passes = 0
        val maxPasses = 60
        while (moved && passes < maxPasses) {
            moved = false
            passes++

            // Deterministic order: shuffle a copy with the seeded PRNG.
            val order = nodeIds.toMutableList()
            for (i in order.indices.reversed()) {
                val j = random.nextInt(i + 1)
                val tmp = order[i]; order[i] = order[j]; order[j] = tmp
            }

            for (node in order) {
                val current = community[node] ?: continue
                val nodeDegree = neighbourIds[node]?.size ?: 0
                // Neighbour communities and the number of links to each.
                val targetLinks = HashMap<String, Int>()
                for (nb in neighbourIds[node].orEmpty()) {
                    val c = community[nb] ?: continue
                    targetLinks[c] = (targetLinks[c] ?: 0) + 1
                }

                var bestCommunity = current
                var bestGain = 0.0
                for ((candidate, links) in targetLinks) {
                    if (candidate == current) continue
                    val gain = modularityGain(
                        links = links,
                        m = m,
                        degree = nodeDegree,
                        candidateDegreeSum = degreeSum[candidate] ?: 0,
                        currentDegreeSum = degreeSum[current] ?: 0
                    )
                    if (gain > bestGain) {
                        bestGain = gain
                        bestCommunity = candidate
                    }
                }

                if (bestCommunity != current) {
                    members[current]?.remove(node)
                    members.getOrPut(bestCommunity) { mutableSetOf() }.add(node)
                    degreeSum[current] = (degreeSum[current] ?: 0) - nodeDegree
                    degreeSum[bestCommunity] = (degreeSum[bestCommunity] ?: 0) + nodeDegree
                    community[node] = bestCommunity
                    moved = true
                }
            }
        }

        // Rename community ids to stable sequential labels for reproducibility.
        val labelById = LinkedHashMap<String, String>()
        for ((node, cid) in community) labelById.getOrPut(cid) { "c${labelById.size}" }
        return community.mapValues { (_, cid) -> labelById.getValue(cid) }
    }

    /**
     * ΔQ of moving `node` (degree `degree`, `links` edges into the candidate
     * community) out of its current community into the candidate. Standard
     * unweighted Louvain modularity-gain formula, using per-community degree
     * sums for the correction terms.
     */
    private fun modularityGain(
        links: Int,
        m: Double,
        degree: Int,
        candidateDegreeSum: Int,
        currentDegreeSum: Int
    ): Double {
        if (m <= 0.0) return 0.0
        val twoM = 2.0 * m
        val kIn = links.toDouble() / twoM
        val k = degree.toDouble() / twoM
        val sigmaCandidate = candidateDegreeSum.toDouble() / twoM
        val sigmaCurrent = (currentDegreeSum - degree).toDouble() / twoM
        return kIn - k * sigmaCandidate - k * sigmaCurrent
    }

    /** Mulberry32-style PRNG matching the desktop's `createSeededRandom`. */
    private class SeededRandom(seed: Int) {
        private var state = seed and 0xffffffffL.toInt()

        fun nextInt(bound: Int): Int {
            val value = next() * bound
            return value.toInt().coerceAtMost(bound - 1)
        }

        private fun next(): Double {
            state += 0x6d2b79f5
            var t = state
            t = mulberry32(t, t xor (t ushr 15))
            t = mulberry32(t, t xor (t ushr 7)) xor t
            return (t xor (t ushr 14)).toLong().and(0xffffffffL) / 4294967296.0
        }

        private fun mulberry32(a: Int, b: Int): Int =
            (a.toLong() * b.toLong()).toInt()
    }
}
